package com.carlmanning.carlsbrain.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.carlmanning.carlsbrain.domain.UserContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.carlmanning.carlsbrain.CarlsBrainApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class DriveRepository(context: Context) {

    private val authManager = GoogleAuthManager(context)
    private val httpClient = CarlsBrainApp.httpClient

    /**
     * Binary uploads (meeting audio, photos, file attachments) can legitimately run past the
     * shared client's 60s call timeout on a slow regional connection, so they get their own
     * derived client with a generous ceiling. Everything else on this repository is small
     * JSON/markdown and stays on the shared bounded client.
     */
    private val mediaUploadClient = CarlsBrainApp.httpClient.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
    private val json = appJson

    // ── memory.md ───────────────────────────────────────────────────

    suspend fun getMemoryMd(): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, MEMORY_FILE) ?: return null
        return downloadFile(token, fileId)
    }

    suspend fun updateMemoryMd(content: String): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val existingId = findFile(token, folderId, MEMORY_FILE)
        return if (existingId != null) patchFile(token, existingId, content, "text/markdown")
               else createFile(token, folderId, MEMORY_FILE, content, "text/markdown")
    }

    // ── todos.json ──────────────────────────────────────────────────

    suspend fun downloadTodosJson(): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, TODOS_FILE) ?: return null
        return downloadFile(token, fileId)
    }

    suspend fun uploadTodosJson(jsonContent: String): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val existingId = findFile(token, folderId, TODOS_FILE)
        return if (existingId != null) patchFile(token, existingId, jsonContent, "application/json")
               else createFile(token, folderId, TODOS_FILE, jsonContent, "application/json")
    }

    // ── settings.json ───────────────────────────────────────────────

    suspend fun getApiKeyFromSettings(): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, SETTINGS_FILE) ?: return null
        val content = downloadFile(token, fileId) ?: return null
        return runCatching {
            json.decodeFromString<SettingsJson>(content).apiKey.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Saves the Anthropic key, leaving any other key in settings.json untouched.
     *
     * Delegates to [publishSettingsKeys] rather than writing SettingsJson(apiKey) directly —
     * that form serialises openaiApiKey as blank, so saving the Anthropic key on the phone
     * would silently wipe the OpenAI key the web app needs for transcription.
     */
    suspend fun saveApiKeyToSettings(apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        return publishSettingsKeys(anthropicKey = apiKey, openaiKey = "")
    }

    /**
     * Ensures Drive's settings.json carries the API keys the web app needs, without ever
     * clearing one that is already there.
     *
     * The web app reads both keys from this file: Anthropic for meeting analysis and chat,
     * OpenAI for Whisper transcription. The phone kept the OpenAI key only in DataStore and
     * never published it, so web transcription could not work at all — and when settings.json
     * itself went missing, the web app lost the Anthropic key too and failed with nothing more
     * useful than "No API key configured".
     *
     * Merges rather than overwrites: a key set from the web app is preserved when the phone
     * has none, and vice versa. A blank local key never blanks the stored one.
     *
     * @return true if nothing needed doing or the write succeeded.
     */
    suspend fun publishSettingsKeys(anthropicKey: String, openaiKey: String): Boolean {
        if (anthropicKey.isBlank() && openaiKey.isBlank()) return true
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val existingId = findFile(token, folderId, SETTINGS_FILE)
        val existing = existingId
            ?.let { downloadFile(token, it) }
            ?.let { runCatching { json.decodeFromString<SettingsJson>(it) }.getOrNull() }
            ?: SettingsJson()

        val merged = SettingsJson(
            apiKey = anthropicKey.ifBlank { existing.apiKey },
            openaiApiKey = openaiKey.ifBlank { existing.openaiApiKey }
        )
        if (merged == existing) return true

        val content = json.encodeToString(merged)
        return if (existingId != null) patchFile(token, existingId, content, "application/json")
               else createFile(token, folderId, SETTINGS_FILE, content, "application/json")
    }

    // ── buckets.json ────────────────────────────────────────────────

    /**
     * Publishes the bucket list, including which buckets are vault.
     *
     * The web app previously had no way to know this and fell back to a hardcoded list in its
     * own types.ts. A bucket Carl marked vault on the phone was therefore NOT treated as vault
     * on the web — it rendered like any other. Publishing the real config is what lets the web
     * app filter correctly, and lets it do so on the server rather than hiding rows in the
     * browser after already sending them.
     *
     * Written on every sync push: it is tiny, and a stale copy is precisely the failure that
     * would leak a bucket Carl had just marked private.
     */
    suspend fun uploadBucketsJson(jsonContent: String): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val existingId = findFile(token, folderId, BUCKETS_FILE)
        return if (existingId != null) patchFile(token, existingId, jsonContent, "application/json")
               else createFile(token, folderId, BUCKETS_FILE, jsonContent, "application/json")
    }

    /**
     * Reads back the published bucket list, or null if it is missing or unreachable.
     *
     * Needed because buckets.json was write-only: a fresh install rebuilt buckets from the
     * names on todos and notes, which carry no vault flag, so every vault bucket came back as
     * an ordinary one. Null and "no file" are both returned as null, and the caller must treat
     * that as "no information" rather than "nothing is vault".
     */
    suspend fun downloadBucketsJson(): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, BUCKETS_FILE) ?: return null
        return downloadFile(token, fileId)
    }

    // ── journal_templates.json ──────────────────────────────────────

    /**
     * Publishes Carl's journal templates and their shared option lists.
     *
     * These are his, not the app's — he builds them, and rebuilding a five-scale training
     * template with all its anchors on a replacement phone is exactly the sort of thing that
     * makes someone stop using a feature. Entries carry their own snapshot of the questions, so
     * this file is about the templates themselves, not about reading old entries.
     */
    suspend fun uploadJournalTemplatesJson(jsonContent: String): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val existingId = findFile(token, folderId, JOURNAL_TEMPLATES_FILE)
        return if (existingId != null) patchFile(token, existingId, jsonContent, "application/json")
               else createFile(token, folderId, JOURNAL_TEMPLATES_FILE, jsonContent, "application/json")
    }

    /** Null means missing or unreachable — never "he has no templates". */
    suspend fun downloadJournalTemplatesJson(): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, JOURNAL_TEMPLATES_FILE) ?: return null
        return downloadFile(token, fileId)
    }

    // ── preferences.json ────────────────────────────────────────────

    /**
     * Publishes the settings that travel between devices.
     *
     * Kept separate from settings.json rather than folded into it: settings.json holds API keys
     * and is merged field-by-field so the web app and the phone cannot blank each other's keys.
     * Preferences are a whole-document snapshot with different rules, and mixing the two would
     * mean one file with two conflict policies.
     */
    suspend fun uploadPreferencesJson(jsonContent: String): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val existingId = findFile(token, folderId, PREFERENCES_FILE)
        return if (existingId != null) patchFile(token, existingId, jsonContent, "application/json")
               else createFile(token, folderId, PREFERENCES_FILE, jsonContent, "application/json")
    }

    /** Null means missing or unreachable — the caller must not treat it as "no settings". */
    suspend fun downloadPreferencesJson(): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, PREFERENCES_FILE) ?: return null
        return downloadFile(token, fileId)
    }

    // ── note files ──────────────────────────────────────────────────

    /**
     * One file in the SecondBrain folder, as the sync needs to see it.
     *
     * Carries the Drive file id and its modification time so a pull can decide whether a
     * download is worth making at all. Without those the sync had to fetch every file — three
     * HTTP round-trips each, twice over — just to read a timestamp out of the body.
     */
    data class RemoteFile(val entityId: Long, val fileId: String, val modifiedAtMs: Long)

    private fun parseRfc3339(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private suspend fun listEntityFiles(prefix: String): List<RemoteFile> {
        val token = fetchToken() ?: return emptyList()
        val folderId = findFolder(token, FOLDER_NAME) ?: return emptyList()
        val q = "name contains '$prefix' and '$folderId' in parents and trashed=false"
        return listFiles(token, q).orEmpty().mapNotNull { file ->
            val id = file.name.removePrefix(prefix).removeSuffix(".md").toLongOrNull()
                ?: return@mapNotNull null
            RemoteFile(id, file.id, parseRfc3339(file.modifiedTime))
        }
    }

    suspend fun listNoteFiles(): List<RemoteFile> = listEntityFiles("note_")

    suspend fun listJournalFiles(): List<RemoteFile> = listEntityFiles("journal_")

    /** Reads a note by Drive file id, skipping the folder and name lookups. */
    suspend fun downloadNoteFileById(fileId: String): NoteFile? {
        val token = fetchToken() ?: return null
        val raw = downloadFile(token, fileId) ?: return null
        val (title, content) = parseNoteContent(raw)
        return NoteFile(title = title, content = content, updatedAt = parseUpdatedAt(raw))
    }

    /** Reads a journal entry by Drive file id, skipping the folder and name lookups. */
    suspend fun downloadJournalEntryById(fileId: String): JournalFile? {
        val token = fetchToken() ?: return null
        val raw = downloadFile(token, fileId) ?: return null
        return parseJournalRaw(raw)
    }

    suspend fun listNoteIds(): List<Long> {
        val token = fetchToken() ?: return emptyList()
        val folderId = findFolder(token, FOLDER_NAME) ?: return emptyList()
        val q = "name contains 'note_' and '$folderId' in parents and trashed=false"
        // A failed lookup returns null and yields an empty list here, same as before. That is
        // safe for this caller: the sync only ever pulls notes whose ids are missing locally,
        // so an empty result skips the pull rather than deleting anything.
        return listFiles(token, q).orEmpty().mapNotNull { file ->
            file.name.removePrefix("note_").removeSuffix(".md").toLongOrNull()
        }
    }

    suspend fun downloadNoteFile(noteId: Long): NoteFile? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, "note_$noteId.md") ?: return null
        val raw = downloadFile(token, fileId) ?: return null
        val (title, content) = parseNoteContent(raw)
        return NoteFile(title = title, content = content, updatedAt = parseUpdatedAt(raw))
    }

    data class NoteFile(val title: String, val content: String, val updatedAt: Long)

    /**
     * @param updatedAt when this note was last edited. Written into the file so a pull can tell
     *   which copy is newer — without it the merge could only ever insert, so a note edited on
     *   the web app never reached the phone.
     */
    suspend fun uploadNoteFile(
        noteId: Long,
        title: String,
        content: String,
        bucketName: String = "Personal",
        updatedAt: Long = 0L
    ): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val fileName = "note_$noteId.md"
        val stamp = if (updatedAt > 0) "<!-- updatedAt: $updatedAt -->\n" else ""
        // The bucket comment is written whether or not there is a title. It used to be part of
        // the titled branch only, so an untitled note carried no bucket at all — and a reader
        // with no bucket to go on defaults it to something public, which quietly un-vaults an
        // untitled note in a vault bucket. Only the heading is conditional now.
        val heading = if (title.isBlank()) "" else "# $title\n"
        val noteContent = "$heading<!-- bucket: $bucketName -->\n$stamp\n$content"
        val existingId = findFile(token, folderId, fileName)
        return if (existingId != null) patchFile(token, existingId, noteContent, "text/markdown")
               else createFile(token, folderId, fileName, noteContent, "text/markdown")
    }

    // ── journal entries ─────────────────────────────────────────────

    /**
     * Journal entries live as `journal_<id>.md` beside the notes, with their metadata in an
     * HTML comment the way note buckets already are — so the file stays readable markdown
     * rather than becoming a second bespoke format.
     *
     * Private entries are uploaded like any other: Drive is Carl's own storage behind his own
     * account, and the privacy flag is what governs display. It travels with the file so the
     * web app can honour it.
     */
    suspend fun uploadJournalEntry(
        entryId: Long,
        content: String,
        prompt: String,
        isPrivate: Boolean,
        createdAt: Long,
        attachments: String = "",
        updatedAt: Long = 0L,
        bucketName: String = ""
    ): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val fileName = "journal_$entryId.md"
        val body = buildString {
            appendLine("<!-- private: $isPrivate -->")
            appendLine("<!-- createdAt: $createdAt -->")
            if (updatedAt > 0) appendLine("<!-- updatedAt: $updatedAt -->")
            if (prompt.isNotBlank()) appendLine("<!-- prompt: ${prompt.replace("-->", "--&gt;")} -->")
            if (attachments.isNotBlank()) appendLine("<!-- attachments: $attachments -->")
            // Travels as a name, not an id: ids are per-device, and a bucket id from this phone
            // means nothing on the next one. Same convention notes and todos already use.
            if (bucketName.isNotBlank()) appendLine("<!-- bucket: $bucketName -->")
            appendLine()
            append(content)
        }
        val existingId = findFile(token, folderId, fileName)
        return if (existingId != null) patchFile(token, existingId, body, "text/markdown")
               else createFile(token, folderId, fileName, body, "text/markdown")
    }

    suspend fun deleteJournalEntry(entryId: Long): Boolean {
        val token = fetchToken() ?: return false
        val folderId = findFolder(token, FOLDER_NAME) ?: return true
        val fileId = findFile(token, folderId, "journal_$entryId.md") ?: return true
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val code = httpClient.newCall(request).execute().use { it.code }
                code in 200..299 || code == 404
            }
        }.getOrDefault(false)
    }

    /**
     * Reads one journal entry back from Drive, parsing the metadata comments written by
     * [uploadJournalEntry] and by the web app's serialiser. Keep the three in step.
     *
     * @return content, prompt, isPrivate, createdAt — or null if unreadable.
     */
    suspend fun downloadJournalEntry(entryId: Long): JournalFile? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, "journal_$entryId.md") ?: return null
        val raw = downloadFile(token, fileId) ?: return null
        return parseJournalRaw(raw)
    }

    /** The parser both journal download paths share, so they cannot drift apart. */
    private fun parseJournalRaw(raw: String): JournalFile {
        val isPrivate = Regex("""<!--\s*private:\s*(true|false)\s*-->""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.equals("true", ignoreCase = true) ?: false
        val createdAt = Regex("""<!--\s*createdAt:\s*(\d+)\s*-->""")
            .find(raw)?.groupValues?.get(1)?.toLongOrNull()
        val prompt = Regex("""<!--\s*prompt:\s*([\s\S]*?)-->""")
            .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val attachments = Regex("""<!--\s*attachments:\s*([^\n]*?)-->""")
            .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val bucketName = Regex("""<!--\s*bucket:\s*([^\n]*?)-->""")
            .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val content = raw.replace(Regex("""<!--[\s\S]*?-->"""), "").trimStart()

        return JournalFile(
            content = content,
            prompt = prompt,
            isPrivate = isPrivate,
            // A missing timestamp would otherwise become "now" and sort a re-pulled entry to
            // the top of the journal every time, which reads as the entry being rewritten.
            createdAt = createdAt ?: 0L,
            attachments = attachments,
            updatedAt = parseUpdatedAt(raw),
            bucketName = bucketName
        )
    }

    data class JournalFile(
        val content: String,
        val prompt: String,
        val isPrivate: Boolean,
        val createdAt: Long,
        val attachments: String = "",
        val updatedAt: Long = 0L,
        val bucketName: String = ""
    )

    /** Ids present on Drive, so the sync can spot entries whose file has gone missing. */
    suspend fun listJournalIds(): List<Long> {
        val token = fetchToken() ?: return emptyList()
        val folderId = findFolder(token, FOLDER_NAME) ?: return emptyList()
        val q = "name contains 'journal_' and '$folderId' in parents and trashed=false"
        return listFiles(token, q).orEmpty().mapNotNull { file ->
            file.name.removePrefix("journal_").removeSuffix(".md").toLongOrNull()
        }
    }

    // ── photo + file attachments ────────────────────────────────────

    suspend fun uploadFile(noteId: Long, bytes: ByteArray, mimeType: String, displayName: String): String? {
        val token = fetchToken() ?: return null
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return null
        val mediaFolderId = getOrCreateFolder(token, folderId, MEDIA_FOLDER) ?: return null
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        val fileName = "file_${noteId}_${System.currentTimeMillis()}_$safeName"
        val boundary = "boundary${System.currentTimeMillis()}"
        val metadata = """{"name":"$fileName","parents":["$mediaFolderId"]}"""
        val metaPart = "--$boundary\r\nContent-Type: application/json\r\n\r\n$metadata\r\n"
        val mediaPart = "--$boundary\r\nContent-Type: $mimeType\r\n\r\n"
        val closing = "\r\n--$boundary--"
        val body = (metaPart + mediaPart).toByteArray() + bytes + closing.toByteArray()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val resp = mediaUploadClient.newCall(request).execute().use { it.body?.string() }
                    ?: return@withContext null
                json.decodeFromString<DriveFileInfo>(resp).id.ifEmpty { null }
            }
        }.getOrNull()
    }

    suspend fun uploadPhoto(noteId: Long, bytes: ByteArray, mimeType: String): String? {
        val token = fetchToken() ?: return null
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return null
        val mediaFolderId = getOrCreateFolder(token, folderId, MEDIA_FOLDER) ?: return null
        val fileName = "media_${noteId}_${System.currentTimeMillis()}.jpg"
        val boundary = "boundary${System.currentTimeMillis()}"
        val metadata = """{"name":"$fileName","parents":["$mediaFolderId"]}"""
        val metaPart = "--$boundary\r\nContent-Type: application/json\r\n\r\n$metadata\r\n"
        val mediaPart = "--$boundary\r\nContent-Type: $mimeType\r\n\r\n"
        val closing = "\r\n--$boundary--"
        val body = (metaPart + mediaPart).toByteArray() + bytes + closing.toByteArray()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val resp = mediaUploadClient.newCall(request).execute().use { it.body?.string() }
                    ?: return@withContext null
                json.decodeFromString<DriveFileInfo>(resp).id.ifEmpty { null }
            }
        }.getOrNull()
    }

    suspend fun downloadPhotoBytes(fileId: String): ByteArray? {
        val token = fetchToken() ?: return null
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { it.body?.bytes() } }
        }.getOrNull()
    }

    suspend fun deletePhoto(fileId: String): Boolean {
        val token = fetchToken() ?: return false
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { it.isSuccessful } }
        }.getOrElse { false }
    }

    /**
     * Permanently removes a meeting's Drive folder and everything in it.
     *
     * Deleting a meeting previously left the whole folder behind — audio, transcript, summary
     * — so a meeting deleted on the phone stayed visible on the web app forever, because the
     * web reads Drive folders directly and had no idea it had been deleted. Called at purge,
     * not at delete, so the 90-day Recently Deleted window still restores everything.
     *
     * Returns true when the folder is gone or was never there.
     */
    suspend fun deleteMeetingFolder(folderId: String): Boolean {
        if (folderId.isBlank()) return true
        val token = fetchToken() ?: return false
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$folderId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val code = httpClient.newCall(request).execute().use { it.code }
                // 404 means someone already removed it — the desired end state either way.
                code in 200..299 || code == 404
            }
        }.getOrDefault(false)
    }

    suspend fun deleteNoteFile(noteId: Long): Boolean {
        val token = fetchToken() ?: return false
        val folderId = findFolder(token, FOLDER_NAME) ?: return true
        val fileId = findFile(token, folderId, "note_$noteId.md") ?: return true
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { it.isSuccessful } }
        }.getOrElse { false }
    }

    // ── sharing ─────────────────────────────────────────────────────────────────

    /**
     * Makes a Drive file publicly readable (anyone with the link) and returns its webViewLink,
     * or null on failure.
     */
    suspend fun shareFile(fileId: String): String? {
        val token = fetchToken() ?: return null
        // Create "anyone reader" permission
        val permBody = """{"type":"anyone","role":"reader"}"""
        val permRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId/permissions")
            .addHeader("Authorization", "Bearer $token")
            .post(permBody.toRequestBody("application/json".toMediaType()))
            .build()
        val permOk = runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(permRequest).execute().use { it.isSuccessful } }
        }.getOrElse { false }
        if (!permOk) return null

        // Retrieve the webViewLink
        val linkRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?fields=webViewLink")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val body = httpClient.newCall(linkRequest).execute().use { it.body?.string() }
                    ?: return@withContext null
                json.decodeFromString<DriveWebViewLink>(body).webViewLink.ifEmpty { null }
            }
        }.getOrNull()
    }

    /**
     * Finds the Drive file ID for note_[noteId].md inside the SecondBrain folder,
     * then makes it publicly readable and returns its webViewLink.
     */
    suspend fun shareNoteFile(noteId: Long): String? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, "note_$noteId.md") ?: return null
        return shareFile(fileId)
    }

    /**
     * Finds summary.md inside the given meeting Drive folder,
     * makes it publicly readable and returns its webViewLink.
     */
    suspend fun shareMeetingSummary(driveFolderId: String): String? {
        if (driveFolderId.isBlank()) return null
        val token = fetchToken() ?: return null
        val summaryFileId = findFile(token, driveFolderId, "summary.md") ?: return null
        return shareFile(summaryFileId)
    }

    // ── meeting files ────────────────────────────────────────────────────────────────────────────

    suspend fun createMeetingFolder(folderName: String): String? {
        val token = fetchToken() ?: return null
        val rootFolderId = getOrCreateFolder(token, FOLDER_NAME) ?: return null
        val meetingsFolderId = getOrCreateFolder(token, rootFolderId, MEETINGS_FOLDER) ?: return null
        return createFolderIn(token, meetingsFolderId, folderName)
    }

    suspend fun uploadMeetingAudio(folderId: String, audioBytes: ByteArray): String? {
        val token = fetchToken() ?: return null
        val boundary = "boundary${System.currentTimeMillis()}"
        val metadata = """{"name":"recording.m4a","parents":["$folderId"]}"""
        val metaPart = "--$boundary\r\nContent-Type: application/json\r\n\r\n$metadata\r\n"
        val mediaPart = "--$boundary\r\nContent-Type: audio/mp4\r\n\r\n"
        val closing = "\r\n--$boundary--"
        val body = (metaPart + mediaPart).toByteArray() + audioBytes + closing.toByteArray()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val resp = mediaUploadClient.newCall(request).execute().use { it.body?.string() }
                    ?: return@withContext null
                json.decodeFromString<DriveFileInfo>(resp).id.ifEmpty { null }
            }
        }.getOrNull()
    }

    /**
     * Writes a text file into a meeting folder, replacing any existing file of that name.
     *
     * This used to always create. Drive permits two files with the same name in one folder, so
     * a re-processed meeting — and now every upload retry — left a second transcript.md beside
     * the first. The web app reads whichever the API returns first, which is not necessarily
     * the newest, so a meeting could show a stale transcript with no sign anything was wrong.
     */
    suspend fun uploadMeetingTextFile(
        folderId: String,
        fileName: String,
        content: String,
        mimeType: String = "text/markdown"
    ): Boolean {
        val token = fetchToken() ?: return false
        val existingId = findFile(token, folderId, fileName)
        return if (existingId != null) patchFile(token, existingId, content, mimeType)
               else createFile(token, folderId, fileName, content, mimeType)
    }

    suspend fun getAccessToken(): String? = fetchToken()

    /**
     * Pulls a meeting's audio back down from Drive.
     *
     * Needed because the midnight worker clears the local copy 30 days after recording, once
     * the audio is safely on Drive. Without this, "Transcribe from audio" simply stopped
     * working on anything older than a month even though the recording still existed — the app
     * had the file and no way to reach it.
     */
    suspend fun downloadMeetingAudio(fileId: String): ByteArray? {
        if (fileId.isBlank()) return null
        val token = fetchToken() ?: return null
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                mediaUploadClient.newCall(request).execute().use { it.body?.bytes() }
            }
        }.getOrNull()
    }

    fun buildAudioDownloadUrl(fileId: String) =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

    // ── internals ───────────────────────────────────────────────────

    /**
     * Splits a note file into title and body.
     *
     * The metadata comments are dropped from the body. They were not before: `dropWhile
     * { it.isBlank() }` stops at the first non-blank line, and `<!-- bucket: … -->` is not
     * blank — so every note pulled back from Drive had its bucket comment glued to the front of
     * its text. Adding the updatedAt stamp would have made that two stray lines instead of one.
     */
    private fun parseNoteContent(raw: String): Pair<String, String> {
        val lines = raw.lines()
        val title = if (raw.startsWith("# ")) lines.first().removePrefix("# ").trim() else ""
        val rest = if (raw.startsWith("# ")) lines.drop(1) else lines
        val body = rest
            .dropWhile { it.isBlank() || it.trim().startsWith("<!--") }
            .joinToString("\n")
        return Pair(title, body)
    }

    /** Reads the `updatedAt` stamp from any of our markdown files, or 0 when absent. */
    private fun parseUpdatedAt(raw: String): Long =
        Regex("""<!--\s*updatedAt:\s*(\d+)\s*-->""")
            .find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Resolves the folder, creating it only when Drive definitely does not have one.
     *
     * Two things here stop duplicate folders, which is what fragmented Carl's Drive into five
     * SecondBrain folders:
     *
     *  1. A failed lookup returns null and creates nothing. Creating on failure is what orphaned
     *     the existing data — the folder was there, the request simply did not come back.
     *  2. [folderMutex] serialises the check-and-create. It is check-then-act, so two callers
     *     (the 15-minute sync worker, a save, the memory learner) could both look, both find
     *     nothing, and both create. Three of Carl's five folders appeared within 45 minutes of
     *     each other, which is exactly that race.
     *
     * The mutex is on the companion object, not the instance: DriveRepository is constructed
     * ad hoc at half a dozen call sites, so a per-instance lock would guard nothing.
     */
    private suspend fun getOrCreateFolder(token: String, name: String): String? =
        folderMutex.withLock {
            val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                    " and 'root' in parents and trashed=false"
            val found = listFiles(token, q, orderBy = "createdTime") ?: return@withLock null
            found.firstOrNull()?.id ?: createFolder(token, name)
        }

    private suspend fun getOrCreateFolder(token: String, parentId: String, name: String): String? =
        folderMutex.withLock {
            val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                    " and '$parentId' in parents and trashed=false"
            val found = listFiles(token, q, orderBy = "createdTime") ?: return@withLock null
            found.firstOrNull()?.id ?: createFolderIn(token, parentId, name)
        }

    /**
     * Read-path folder lookup. Never creates, so a failed lookup returning null simply aborts
     * the read — correct, and the reason the read paths never contributed to the duplicates.
     *
     * Ordered by creation time so that while duplicate folders still exist every caller agrees
     * on the same one: the oldest, which holds the longest history.
     */
    private suspend fun findFolder(token: String, name: String): String? {
        val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                " and 'root' in parents and trashed=false"
        return listFiles(token, q, orderBy = "createdTime")?.firstOrNull()?.id
    }

    private suspend fun findFolderIn(token: String, parentId: String, name: String): String? {
        val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                " and '$parentId' in parents and trashed=false"
        return listFiles(token, q, orderBy = "createdTime")?.firstOrNull()?.id
    }

    private suspend fun findFile(token: String, folderId: String, name: String): String? {
        val q = "name='$name' and '$folderId' in parents and trashed=false"
        return listFiles(token, q)?.firstOrNull()?.id
    }

    /**
     * Lists Drive files matching [q].
     *
     * Returns **null when the lookup itself failed** (network, auth, malformed response) and an
     * empty list only when Drive genuinely holds nothing matching. That distinction is not
     * academic: this previously collapsed every failure into "nothing found", so a dropped
     * request made [getOrCreateFolder] believe the SecondBrain folder did not exist and create a
     * second one. Every later write went to the new folder and the existing memory, notes and
     * todos were orphaned. Carl's Drive accumulated five SecondBrain folders that way.
     *
     * @param orderBy optional Drive orderBy, e.g. "createdTime" to make "first result" stable.
     */
    private suspend fun listFiles(
        token: String,
        q: String,
        orderBy: String? = null
    ): List<DriveFileInfo>? {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val order = orderBy?.let { "&orderBy=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val url =
            "https://www.googleapis.com/drive/v3/files?q=$encoded&fields=files(id,name,modifiedTime)$order"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    // An error status still carries a body, which would parse to zero files and
                    // read as "not found". Treat any non-2xx as a failed lookup.
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    json.decodeFromString<FilesListResponse>(body).files
                }
            }
        }.getOrNull()
    }

    private suspend fun createFolder(token: String, name: String): String? =
        createFolderIn(token, "root", name)

    private suspend fun createFolderIn(token: String, parentId: String, name: String): String? {
        val body = """{"name":"$name","mimeType":"application/vnd.google-apps.folder","parents":["$parentId"]}"""
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val resp = httpClient.newCall(request).execute().use { it.body?.string() }
                    ?: return@withContext null
                json.decodeFromString<DriveFileInfo>(resp).id.ifEmpty { null }
            }
        }.getOrNull()
    }

    private suspend fun downloadFile(token: String, fileId: String): String? {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { it.body?.string() } }
        }.getOrNull()
    }

    private suspend fun createFile(
        token: String, folderId: String, name: String, content: String, contentType: String
    ): Boolean {
        val boundary = "boundary${System.currentTimeMillis()}"
        val metadata = """{"name":"$name","parents":["$folderId"]}"""
        val multipart = "--$boundary\r\n" +
                "Content-Type: application/json\r\n\r\n$metadata\r\n" +
                "--$boundary\r\n" +
                "Content-Type: $contentType\r\n\r\n$content\r\n" +
                "--$boundary--"
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $token")
            .post(multipart.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { it.isSuccessful } }
        }.getOrElse { false }
    }

    private suspend fun patchFile(token: String, fileId: String, content: String, contentType: String): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $token")
            .patch(content.toRequestBody(contentType.toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().use { it.isSuccessful } }
        }.getOrElse { false }
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        authManager.authorize(
            onSuccess = { if (cont.isActive) cont.resume(it) },
            onResolutionRequired = { if (cont.isActive) cont.resume(null) },
            onError = { if (cont.isActive) cont.resume(null) }
        )
    }

    companion object {
        private const val FOLDER_NAME = "SecondBrain"
        /** Serialises folder check-and-create across every DriveRepository instance. */
        private val folderMutex = Mutex()

        private const val MEMORY_FILE = "memory.md"
        private const val TODOS_FILE = "todos.json"
        private const val BUCKETS_FILE = "buckets.json"
        private const val PREFERENCES_FILE = "preferences.json"
        private const val JOURNAL_TEMPLATES_FILE = "journal_templates.json"
        private const val SETTINGS_FILE = "settings.json"
        const val MEDIA_FOLDER = "media"
        private const val MEETINGS_FOLDER = "meetings"

        /**
         * Written to Drive only when memory.md does not yet exist. Lives in
         * [UserContext] so it cannot drift from the persona used in system prompts.
         */
        val INITIAL_MEMORY = UserContext.INITIAL_MEMORY
    }
}

@Serializable private data class FilesListResponse(val files: List<DriveFileInfo> = emptyList())
@Serializable private data class DriveFileInfo(
    val id: String = "",
    val name: String = "",
    /** RFC 3339, e.g. 2026-08-21T04:15:00.000Z. Absent on the create/patch responses. */
    val modifiedTime: String = ""
)
@Serializable private data class SettingsJson(
    val apiKey: String = "",
    val openaiApiKey: String = ""
)
@Serializable private data class DriveWebViewLink(val webViewLink: String = "")
