package com.carlmanning.carlsbrain.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.carlmanning.carlsbrain.CarlsBrainApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.coroutines.resume

class DriveRepository(context: Context) {

    private val authManager = GoogleAuthManager(context)
    private val httpClient = CarlsBrainApp.httpClient
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

    suspend fun saveApiKeyToSettings(apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val content = json.encodeToString(SettingsJson(apiKey))
        val existingId = findFile(token, folderId, SETTINGS_FILE)
        return if (existingId != null) patchFile(token, existingId, content, "application/json")
               else createFile(token, folderId, SETTINGS_FILE, content, "application/json")
    }

    // ── note files ──────────────────────────────────────────────────

    suspend fun listNoteIds(): List<Long> {
        val token = fetchToken() ?: return emptyList()
        val folderId = findFolder(token, FOLDER_NAME) ?: return emptyList()
        val q = "name contains 'note_' and '$folderId' in parents and trashed=false"
        return listFiles(token, q).mapNotNull { file ->
            file.name.removePrefix("note_").removeSuffix(".md").toLongOrNull()
        }
    }

    suspend fun downloadNoteFile(noteId: Long): Pair<String, String>? {
        val token = fetchToken() ?: return null
        val folderId = findFolder(token, FOLDER_NAME) ?: return null
        val fileId = findFile(token, folderId, "note_$noteId.md") ?: return null
        val raw = downloadFile(token, fileId) ?: return null
        return parseNoteContent(raw)
    }

    suspend fun uploadNoteFile(noteId: Long, title: String, content: String): Boolean {
        val token = fetchToken() ?: return false
        val folderId = getOrCreateFolder(token, FOLDER_NAME) ?: return false
        val fileName = "note_$noteId.md"
        val noteContent = if (title.isBlank()) content else "# $title\n\n$content"
        val existingId = findFile(token, folderId, fileName)
        return if (existingId != null) patchFile(token, existingId, noteContent, "text/markdown")
               else createFile(token, folderId, fileName, noteContent, "text/markdown")
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
                val resp = httpClient.newCall(request).execute().body?.string() ?: return@withContext null
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
                val resp = httpClient.newCall(request).execute().body?.string() ?: return@withContext null
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
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().body?.bytes() }
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
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().isSuccessful }
        }.getOrElse { false }
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
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().isSuccessful }
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
            withContext(Dispatchers.IO) { httpClient.newCall(permRequest).execute().isSuccessful }
        }.getOrElse { false }
        if (!permOk) return null

        // Retrieve the webViewLink
        val linkRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?fields=webViewLink")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val body = httpClient.newCall(linkRequest).execute().body?.string() ?: return@withContext null
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
                val resp = httpClient.newCall(request).execute().body?.string() ?: return@withContext null
                json.decodeFromString<DriveFileInfo>(resp).id.ifEmpty { null }
            }
        }.getOrNull()
    }

    suspend fun uploadMeetingTextFile(folderId: String, fileName: String, content: String): Boolean {
        val token = fetchToken() ?: return false
        return createFile(token, folderId, fileName, content, "text/markdown")
    }

    // ── internals ───────────────────────────────────────────────────

    private fun parseNoteContent(raw: String): Pair<String, String> {
        return if (raw.startsWith("# ")) {
            val lines = raw.lines()
            val title = lines.first().removePrefix("# ").trim()
            val body = lines.drop(1).dropWhile { it.isBlank() }.joinToString("\n")
            Pair(title, body)
        } else {
            Pair("", raw)
        }
    }

    private suspend fun getOrCreateFolder(token: String, name: String): String? =
        findFolder(token, name) ?: createFolder(token, name)

    private suspend fun getOrCreateFolder(token: String, parentId: String, name: String): String? =
        findFolderIn(token, parentId, name) ?: createFolderIn(token, parentId, name)

    private suspend fun findFolder(token: String, name: String): String? {
        val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                " and 'root' in parents and trashed=false"
        return listFiles(token, q).firstOrNull()?.id
    }

    private suspend fun findFolderIn(token: String, parentId: String, name: String): String? {
        val q = "name='$name' and mimeType='application/vnd.google-apps.folder'" +
                " and '$parentId' in parents and trashed=false"
        return listFiles(token, q).firstOrNull()?.id
    }

    private suspend fun findFile(token: String, folderId: String, name: String): String? {
        val q = "name='$name' and '$folderId' in parents and trashed=false"
        return listFiles(token, q).firstOrNull()?.id
    }

    private suspend fun listFiles(token: String, q: String): List<DriveFileInfo> {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$encoded&fields=files(id,name)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        return runCatching {
            withContext(Dispatchers.IO) {
                val body = httpClient.newCall(request).execute().body?.string()
                    ?: return@withContext emptyList()
                json.decodeFromString<FilesListResponse>(body).files
            }
        }.getOrElse { emptyList() }
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
                val resp = httpClient.newCall(request).execute().body?.string() ?: return@withContext null
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
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().body?.string() }
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
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().isSuccessful }
        }.getOrElse { false }
    }

    private suspend fun patchFile(token: String, fileId: String, content: String, contentType: String): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $token")
            .patch(content.toRequestBody(contentType.toMediaType()))
            .build()
        return runCatching {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute().isSuccessful }
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
        private const val MEMORY_FILE = "memory.md"
        private const val TODOS_FILE = "todos.json"
        private const val SETTINGS_FILE = "settings.json"
        const val MEDIA_FOLDER = "media"
        private const val MEETINGS_FOLDER = "meetings"

        val INITIAL_MEMORY = """
            # Carl's Memory

            ## About Me
            - Name: Carl Manning
            - Role: Deputy, NSW SES (State Emergency Service) — Dubbo Unit
            - Location: Dubbo, NSW, Australia
            - Life buckets: SES, Family, Work, Personal, Kink, Other
            - Has ADHD — appreciates structured, actionable responses

            ## Notes
            *(Updated automatically as you chat with Carl's Brain)*
        """.trimIndent()
    }
}

@Serializable private data class FilesListResponse(val files: List<DriveFileInfo> = emptyList())
@Serializable private data class DriveFileInfo(val id: String = "", val name: String = "")
@Serializable private data class SettingsJson(val apiKey: String = "")
@Serializable private data class DriveWebViewLink(val webViewLink: String = "")
