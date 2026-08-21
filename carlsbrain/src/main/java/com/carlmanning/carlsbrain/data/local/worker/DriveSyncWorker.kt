package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalOptionListEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalTemplateEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import com.carlmanning.carlsbrain.data.preferences.PreferencesSnapshot
import com.carlmanning.carlsbrain.domain.journal.JournalReminderScheduler
import com.carlmanning.carlsbrain.domain.journal.JournalTemplateSeeder
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.defaultBucket
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DriveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val drive = DriveRepository(applicationContext)

        val pushOk = withTimeoutOrNull(60_000L) {
            // The push runs whatever the pull did.
            //
            // This used to return early on a failed pull, with the exception discarded. One
            // malformed remote file — anything that made pullFromDrive throw repeatedly — was
            // therefore enough to stop everything Carl wrote on the phone from ever reaching
            // Drive, silently and permanently, while the worker went on reporting retry.
            //
            // The push is the half that protects local data; the pull is the optional one. And
            // the reason is logged now rather than swallowed, so the next failure is diagnosable.
            runCatching { pullFromDrive(db, drive) }.onFailure {
                android.util.Log.w("DriveSyncWorker", "Pull failed, pushing anyway", it)
            }
            runCatching { pushToDrive(db, drive) }.getOrElse { false }
        }

        return when {
            pushOk == null -> Result.retry() // timeout
            pushOk -> Result.success()
            else -> Result.retry()
        }
    }

    // ── Pull ─────────────────────────────────────────────────────────

    private suspend fun pullFromDrive(db: AppDatabase, drive: DriveRepository) {
        // Runs first so a restored digest time is in place before anything else reads it.
        restorePreferencesOnFirstSync(drive)
        // Before todos, so a bucket restored from Drive already carries its vault flag by the
        // time resolveBucketId would otherwise recreate it as an ordinary one.
        mergeBucketsFromDrive(db, drive)
        mergeTodosFromDrive(db, drive)
        mergeNotesFromDrive(db, drive)
        mergeJournalFromDrive(db, drive)
        mergeJournalTemplatesFromDrive(db, drive)
    }

    /**
     * Restores journal templates and their shared option lists from Drive.
     *
     * Insert-only, matched on name: a template Carl has locally is left alone, because he edits
     * these on the phone and a stale remote copy overwriting a just-edited one would be worse
     * than a missing template. The point is a replacement device arriving with his templates
     * intact, not two phones negotiating.
     *
     * Option lists come first — a restored template's fields point at them by id, and those ids
     * are remapped to whatever the local list turns out to be.
     */
    private suspend fun mergeJournalTemplatesFromDrive(db: AppDatabase, drive: DriveRepository) {
        val raw = drive.downloadJournalTemplatesJson() ?: return
        val payload = runCatching {
            json.decodeFromString<JournalTemplatesSyncDto>(raw)
        }.getOrElse { return }
        val dao = db.journalTemplateDao()

        // Remote option-list id → local id, so a restored field points at the right list even
        // though autoincrement ids differ between devices.
        val idMap = mutableMapOf<Long, Long>()
        val localLists = dao.getOptionListsOnce()
        for (remote in payload.optionLists) {
            val existing = localLists.find {
                (remote.builtInKey.isNotBlank() && it.builtInKey == remote.builtInKey) ||
                    it.name.equals(remote.name, ignoreCase = true)
            }
            idMap[remote.id] = existing?.id ?: dao.insertOptionList(
                JournalOptionListEntity(
                    name = remote.name,
                    optionsJson = JournalTemplateSeeder.encodeOptions(remote.options),
                    builtInKey = remote.builtInKey
                )
            )
        }

        val localBuckets = db.bucketDao().getAllBuckets().first()
        val localTemplates = dao.getAllTemplatesIncludingDeleted()
        for (remote in payload.templates) {
            // Includes deleted rows: a template Carl threw away must not come back on sync.
            val exists = localTemplates.any {
                (remote.builtInKey.isNotBlank() && it.builtInKey == remote.builtInKey) ||
                    it.name.equals(remote.name, ignoreCase = true)
            }
            if (exists) continue
            val fields = JournalTemplateSeeder.decodeFields(remote.fieldsJson).map { field ->
                if (field.optionListId == 0L) field
                else field.copy(optionListId = idMap[field.optionListId] ?: 0L)
            }
            dao.insertTemplate(
                JournalTemplateEntity(
                    name = remote.name,
                    fieldsJson = JournalTemplateSeeder.encodeFields(fields),
                    isPrivateByDefault = remote.isPrivateByDefault,
                    sortOrder = remote.sortOrder,
                    builtInKey = remote.builtInKey,
                    bucketId = localBuckets
                        .find { it.name.equals(remote.bucketName, ignoreCase = true) }?.id,
                    reminderRule = remote.reminderRule
                )
            )
        }
    }

    /**
     * Pulls journal entries written on the web app.
     *
     * Only entries whose id is absent locally are inserted — including soft-deleted ids, so an
     * entry Carl deleted on the phone is never resurrected by a Drive file that has not been
     * cleaned up yet. Existing entries are not updated from Drive: the phone is where they are
     * written and edited, and last-write-wins on free text risks losing a longer local entry to
     * a stale remote copy.
     */
    private suspend fun mergeJournalFromDrive(db: AppDatabase, drive: DriveRepository) {
        val driveFiles = drive.listJournalFiles()
        if (driveFiles.isEmpty()) return
        val localIds = db.journalDao().getAllIds().toSet()

        driveFiles.filter { it.entityId !in localIds }.forEach { remote ->
            val id = remote.entityId
            // Hard-purged but tombstoned — never resurrect. getAllIds cannot see a purged row,
            // so without this an orphaned Drive file re-created an entry Carl deleted.
            if (db.tombstoneDao().isTombstoned(id, TombstoneEntity.TYPE_JOURNAL)) return@forEach
            val file = drive.downloadJournalEntryById(remote.fileId) ?: return@forEach
            if (file.content.isBlank()) return@forEach
            db.journalDao().insertEntry(
                JournalEntryEntity(
                    id = id,
                    content = file.content,
                    prompt = file.prompt,
                    isPrivate = file.isPrivate,
                    attachments = file.attachments,
                    bucketId = journalBucketId(db, file.bucketName),
                    answersJson = file.answersJson,
                    mood = file.mood,
                    createdAt = if (file.createdAt > 0) file.createdAt else System.currentTimeMillis(),
                    updatedAt = if (file.updatedAt > 0) file.updatedAt else System.currentTimeMillis(),
                    isSynced = true
                )
            )
        }

        pullJournalEdits(db, drive, driveFiles)
    }

    /**
     * Resolves a journal entry's bucket by name.
     *
     * Blank means "no bucket", not "default bucket": a journal entry with no bucket is the
     * normal case, and quietly filing every pulled entry into Family would be wrong. Matched by
     * name only — never created — because the bucket list is merged from buckets.json earlier
     * in this same sync, complete with its vault flags. Inventing a bucket here would produce a
     * public one with the same name as a vault bucket that had simply not arrived yet.
     */
    private suspend fun journalBucketId(db: AppDatabase, bucketName: String): Long? {
        if (bucketName.isBlank()) return null
        return db.bucketDao().getAllBuckets().first()
            .find { it.name.equals(bucketName, ignoreCase = true) }?.id
    }

    /** Journal entries edited elsewhere, under the same rules as [pullNoteEdits]. */
    private suspend fun pullJournalEdits(
        db: AppDatabase,
        drive: DriveRepository,
        driveFiles: List<DriveRepository.RemoteFile>
    ) {
        val localById = db.journalDao().getAllEntriesIncludingDeleted().associateBy { it.id }
        for (remote in driveFiles) {
            val local = localById[remote.entityId] ?: continue
            if (local.deletedAt != null || local.isDraft) continue
            if (!local.isSynced) continue
            // Skip the download entirely unless Drive's own copy was touched after ours. This
            // is what stops the sync fetching every entry twice per run — three HTTP calls
            // each — and timing out once the journal grows past a few dozen entries.
            if (remote.modifiedAtMs > 0 && remote.modifiedAtMs <= local.updatedAt) continue
            val file = drive.downloadJournalEntryById(remote.fileId) ?: continue
            if (file.updatedAt <= 0L || file.updatedAt <= local.updatedAt) continue
            if (file.content.isBlank() || file.content == local.content) continue
            db.journalDao().updateEntry(
                local.copy(
                    content = file.content,
                    prompt = file.prompt,
                    isPrivate = file.isPrivate,
                    attachments = file.attachments,
                    // A blank bucket comment means the writer knows nothing about journal
                    // buckets (the web app does not), so it must not clear the local one.
                    bucketId = journalBucketId(db, file.bucketName) ?: local.bucketId,
                    // The rendered text has just been replaced by an editor that may know
                    // nothing about templates. Keeping the old structured answers would leave
                    // the entry reading one way and charting another, which is exactly what
                    // the field snapshot exists to prevent — so they go with the text.
                    answersJson = file.answersJson,
                    mood = file.mood.ifBlank { local.mood },
                    updatedAt = file.updatedAt,
                    isSynced = true
                )
            )
        }
    }

    /**
     * Takes this device's settings from Drive, once, on its first sync after a fresh install.
     *
     * The rule is pull-once-then-push: a new phone inherits Carl's whole setup instead of
     * starting blank, and every later change on it is published. It is device migration, not
     * live two-way sync — a change made on a second phone will not reappear on the first,
     * because doing that correctly needs a changed-at stamp per setting.
     *
     * Three outcomes, and the difference between them matters:
     *  - **File read and parsed** — apply it, and the flag is set inside the same edit.
     *  - **No file yet** (Carl's existing phone, first run after this build) — nothing to
     *    restore, so mark as pulled and let the push below create the file from this device.
     *  - **Unreachable or unparseable** — leave the flag alone and try again next sync. Marking
     *    it pulled here would let a network blip cause a blank-default push over a good file.
     */
    private suspend fun restorePreferencesOnFirstSync(drive: DriveRepository) {
        val prefs = CarlsBrainApp.userPreferences
        if (prefs.preferencesPulledFromDrive.first()) return

        val raw = drive.downloadPreferencesJson()
        if (raw == null) {
            // Distinguishing "no file" from "could not reach Drive" is what makes this safe.
            // downloadPreferencesJson returns null for both, so confirm Drive is actually
            // reachable before concluding there is nothing to restore.
            if (drive.getAccessToken() != null) prefs.markPreferencesPulled()
            return
        }
        val snapshot = runCatching { json.decodeFromString<PreferencesSnapshot>(raw) }.getOrElse {
            // Unparseable is not the same as unreachable: retrying forever would mean this
            // device never pushes either, so its settings would silently never be published.
            // Treat junk as "nothing to restore" and let this device replace the file.
            prefs.markPreferencesPulled()
            return
        }
        prefs.applySyncedPreferences(snapshot)

        // AlarmManager holds the digest and slot times independently of DataStore, so restored
        // times would otherwise not take effect until the next reboot.
        rescheduleAlarms(prefs)
    }

    /** Re-arms every alarm from the current preference values. Mirrors BootReceiver. */
    private suspend fun rescheduleAlarms(prefs: com.carlmanning.carlsbrain.data.preferences.UserPreferences) {
        runCatching {
            val ctx = applicationContext
            if (prefs.digestEnabled.first()) {
                DigestAlarmScheduler.schedule(
                    ctx, prefs.morningDigestHour.first(), prefs.morningDigestMinute.first()
                )
            }
            SmartNotificationAlarmScheduler.scheduleSlot(
                ctx, SmartNotificationWorker.Slot.MORNING,
                prefs.notifMorningEnabled.first(),
                prefs.notifMorningHour.first(), prefs.notifMorningMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                ctx, SmartNotificationWorker.Slot.MIDDAY,
                prefs.notifMiddayEnabled.first(),
                prefs.notifMiddayHour.first(), prefs.notifMiddayMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                ctx, SmartNotificationWorker.Slot.AFTERNOON,
                prefs.notifAfternoonEnabled.first(),
                prefs.notifAfternoonHour.first(), prefs.notifAfternoonMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                ctx, SmartNotificationWorker.Slot.EVENING,
                prefs.notifEveningEnabled.first(),
                prefs.notifEveningHour.first(), prefs.notifEveningMinute.first()
            )
            // Templates arriving from journal_templates.json carry their own DOW:HH:MM rules,
            // and AlarmManager knows nothing about the database changing underneath it. Without
            // this a new phone restores the training template with its Sunday reminder set and
            // never fires it until the next cold start.
            JournalReminderScheduler.rescheduleAll(ctx, AppDatabase.getInstance(ctx))
        }
    }

    /**
     * Restores the bucket list — and, crucially, which buckets are vault — from Drive.
     *
     * buckets.json used to be write-only. On a new phone the buckets were rebuilt from the
     * names attached to todos and notes, which carry no vault flag, so every vault bucket came
     * back as an ordinary one and its contents were visible in normal views. That is the
     * failure this closes.
     *
     * The merge is deliberately asymmetric: a bucket may be turned INTO a vault bucket by a
     * pull, never out of one. Buckets have no per-field timestamps, so a symmetric
     * last-write-wins would let a stale remote copy un-hide something Carl had just marked
     * private. The cost of the asymmetry is that un-vaulting has to be repeated on each device;
     * the cost of the alternative is exposing private content, which is not a trade worth
     * making. A missing or unreadable file changes nothing at all.
     */
    private suspend fun mergeBucketsFromDrive(db: AppDatabase, drive: DriveRepository) {
        val raw = drive.downloadBucketsJson() ?: return
        val remote = runCatching { json.decodeFromString<List<BucketSyncDto>>(raw) }
            .getOrElse { return }
        if (remote.isEmpty()) return

        val local = db.bucketDao().getAllBuckets().first()
        remote.forEach { dto ->
            if (dto.name.isBlank()) return@forEach
            val match = local.find { it.name.equals(dto.name, ignoreCase = true) }
            when {
                match == null ->
                    db.bucketDao().insertBucket(
                        BucketEntity(name = dto.name, isVault = dto.isVault, sortOrder = 99)
                    )
                dto.isVault && !match.isVault ->
                    db.bucketDao().updateBucket(match.copy(isVault = true))
            }
        }
    }

    private suspend fun mergeTodosFromDrive(db: AppDatabase, drive: DriveRepository) {
        val jsonStr = drive.downloadTodosJson() ?: return
        val driveTodos = runCatching { json.decodeFromString<List<TodoSyncDto>>(jsonStr) }
            .getOrElse { return }

        // Include soft-deleted rows so we never resurrect a todo the user deleted
        val roomTodosById = db.todoDao().getAllTodosIncludingDeleted().associateBy { it.id }
        val allBuckets = db.bucketDao().getAllBuckets().first().toMutableList()

        driveTodos.forEach { dto ->
            // Drive item is marked deleted — never insert or update locally
            if (dto.deletedAt != null) return@forEach
            val bucketId = resolveBucketId(db, allBuckets, dto.bucket)
            val existing = roomTodosById[dto.id]
            when {
                // Locally deleted — never resurrect regardless of Drive timestamp
                existing != null && existing.deletedAt != null -> { /* skip */ }
                // Hard-purged but tombstone exists — never resurrect
                existing == null && db.tombstoneDao().isTombstoned(dto.id, TombstoneEntity.TYPE_TODO) -> { /* skip */ }
                existing == null -> {
                    db.todoDao().insertTodo(
                        TodoEntity(
                            id = dto.id,
                            title = dto.title,
                            bucketId = bucketId,
                            priority = Priority.entries.find { it.name == dto.priority.uppercase() }?.rank ?: Priority.NORMAL.rank,
                            isDone = dto.isDone,
                            dueDate = dto.dueDate,
                            createdAt = dto.createdAt,
                            updatedAt = dto.updatedAt,
                            recurrence = dto.recurrence.ifBlank { Recurrence.None.toStorageString() },
                            leadDays = dto.leadDays,
                            reminderAt = dto.reminderAt,
                            isPinned = dto.isPinned,
                            estimateMinutes = dto.estimateMinutes,
                            attachments = dto.attachments.orEmpty(),
                            isArchived = dto.isArchived ?: false,
                            archivedAt = dto.archivedAt,
                            isSynced = true
                        )
                    )
                    if (dto.subtasks != null) applySubtasks(db, dto)
                }
                dto.updatedAt > existing.updatedAt -> {
                    // From v2 a null is a deliberate clear; before that it only ever meant the
                    // writer did not know the field, so the local value has to stand.
                    val nullsAreAuthoritative = dto.schema >= SCHEMA_V2
                    db.todoDao().updateTodo(
                        existing.copy(
                            title = dto.title,
                            bucketId = bucketId,
                            priority = Priority.entries.find { it.name == dto.priority.uppercase() }?.rank ?: Priority.NORMAL.rank,
                            isDone = dto.isDone,
                            dueDate = dto.dueDate,
                            updatedAt = dto.updatedAt,
                            // Blank means the remote copy predates these fields — keep what is
                            // already here rather than wiping a recurrence the phone knows about.
                            recurrence = dto.recurrence.ifBlank { existing.recurrence },
                            leadDays = dto.leadDays,
                            reminderAt = if (nullsAreAuthoritative) dto.reminderAt
                                         else dto.reminderAt ?: existing.reminderAt,
                            isPinned = dto.isPinned,
                            estimateMinutes = if (nullsAreAuthoritative) dto.estimateMinutes
                                              else dto.estimateMinutes ?: existing.estimateMinutes,
                            // Null is "not stated" for these regardless of schema, so a writer
                            // that does not know the field cannot wipe it.
                            attachments = dto.attachments ?: existing.attachments,
                            isArchived = dto.isArchived ?: existing.isArchived,
                            archivedAt = if (dto.isArchived == null) existing.archivedAt
                                         else dto.archivedAt,
                            isSynced = true
                        )
                    )
                    // Only when the row actually states them. Absent is silence, not an
                    // instruction to delete the subtasks the phone holds.
                    if (dto.subtasks != null) applySubtasks(db, dto)
                    // A cleared reminder has to stop the alarm too, or it fires anyway.
                    if (nullsAreAuthoritative && dto.reminderAt == null) {
                        ReminderScheduler.cancel(applicationContext, dto.id)
                    }
                }
                // else: local is current or newer — keep it
            }
        }
    }

    /** Replaces a to-do's subtasks with the copy that arrived from Drive. */
    private suspend fun applySubtasks(db: AppDatabase, dto: TodoSyncDto) {
        val subtasks = dto.subtasks ?: return
        if (subtasks.isEmpty()) {
            db.subtaskDao().deleteSubtasksForTodo(dto.id)
            return
        }
        db.subtaskDao().replaceSubtasks(
            dto.id,
            subtasks.map {
                com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity(
                    todoId = dto.id,
                    title = it.title,
                    isDone = it.isDone,
                    sortOrder = it.sortOrder
                )
            }
        )
    }

    private suspend fun mergeNotesFromDrive(db: AppDatabase, drive: DriveRepository) {
        val driveNoteFiles = drive.listNoteFiles()
        val driveNoteIds = driveNoteFiles.map { it.entityId }

        // Re-queue notes the app thinks are on Drive but are not.
        //
        // isSynced is set once, on a successful upload, and never revisited — so if the Drive
        // copy disappears the phone goes on believing it is safe and never re-uploads. That is
        // exactly what happened when Carl's duplicate SecondBrain folders were consolidated:
        // every note file went with the trashed folders, the notes stayed on the phone marked
        // synced, and the web app showed an empty Notes list with nothing explaining why.
        //
        // Guarded on driveNoteIds being non-empty: listNoteIds returns an empty list both when
        // Drive genuinely has no notes AND when the lookup failed, and treating a failed lookup
        // as "Drive has nothing" would re-upload the entire library on every network blip.
        // The first-ever sync is covered anyway — those notes are unsynced already.
        if (driveNoteIds.isNotEmpty()) {
            val missing = db.noteDao().getSyncedNoteIds().filterNot { it in driveNoteIds }
            if (missing.isNotEmpty()) db.noteDao().markNotesUnsynced(missing)
        }
        // Include soft-deleted note IDs so we never resurrect a note the user deleted
        val roomNoteIds = db.noteDao().getAllNoteIds().toSet()
        val allBuckets = db.bucketDao().getAllBuckets().first()
        val defaultBucketId = allBuckets.defaultBucket()?.id
            ?: allBuckets.firstOrNull()?.id ?: return

        driveNoteIds.filter { it !in roomNoteIds }.forEach { noteId ->
            // Hard-purged but tombstone exists — never resurrect
            if (db.tombstoneDao().isTombstoned(noteId, TombstoneEntity.TYPE_NOTE)) return@forEach
            val fileId = driveNoteFiles.firstOrNull { it.entityId == noteId }?.fileId
            val file = (if (fileId != null) drive.downloadNoteFileById(fileId)
                        else drive.downloadNoteFile(noteId)) ?: return@forEach
            val bucketId = noteBucketId(allBuckets, file.bucketName, defaultBucketId)
                ?: return@forEach
            db.noteDao().insertNote(
                NoteEntity(
                    id = noteId,
                    title = file.title,
                    content = file.content,
                    bucketId = bucketId,
                    attachments = file.attachments,
                    updatedAt = if (file.updatedAt > 0) file.updatedAt else System.currentTimeMillis(),
                    isSynced = true
                )
            )
        }

        pullNoteEdits(db, drive, driveNoteFiles)
    }

    /**
     * Resolves a pulled note's bucket from the name written in its file.
     *
     * Every note used to be inserted into the default bucket outright, which meant a new phone
     * restored the whole library into Family — vault-bucketed notes included, visible in
     * ordinary views. That is the same failure `mergeBucketsFromDrive` was written to close for
     * buckets themselves, left open for the notes inside them.
     *
     * Three cases, and the middle one is the important one:
     *  - **Blank name** — the file carries no bucket comment, so there is nothing to honour and
     *    the default is as good an answer as exists.
     *  - **Named but unmatched** — returns null, and the caller skips the note *this sync*. The
     *    bucket list is merged earlier in the same run, so an unmatched name usually means
     *    buckets.json has not arrived yet; filing it into Family anyway is how a vault note
     *    becomes a visible one, and creating the bucket locally would produce a public bucket
     *    shadowing a vault one. Skipping is recoverable, and the next sync picks it up.
     *  - **Matched** — use it, vault flag and all.
     */
    private fun noteBucketId(
        allBuckets: List<BucketEntity>,
        bucketName: String,
        defaultBucketId: Long
    ): Long? {
        if (bucketName.isBlank()) return defaultBucketId
        return allBuckets.find { it.name.equals(bucketName, ignoreCase = true) }?.id
    }

    /**
     * Brings across edits made to notes that already exist locally.
     *
     * The merge used to be insert-only, so a note edited on the web app never reached the
     * phone — the laptop's version simply sat on Drive being ignored, and the next push from
     * the phone overwrote it. That is silent data loss on a single device, before any
     * dual-device question arises.
     *
     * Two guards make last-write-wins safe here:
     *
     * `isSynced == false` means the phone holds an edit that has not been published yet. Its
     * updatedAt may well be older than the remote copy — that is exactly what "written offline
     * yesterday, remote edited today" looks like — but overwriting it would destroy work that
     * has never existed anywhere else. The push later in this same sync publishes it instead.
     *
     * A remote stamp of 0 means the file predates the stamp being written at all. Treating that
     * as "very old" would be wrong in the other direction, so those files are skipped entirely
     * until something rewrites them with a stamp.
     */
    private suspend fun pullNoteEdits(
        db: AppDatabase,
        drive: DriveRepository,
        driveNoteFiles: List<DriveRepository.RemoteFile>
    ) {
        val localById = db.noteDao().getAllNotesIncludingDeleted().associateBy { it.id }
        // Read once, not once per changed note.
        val allBuckets = db.bucketDao().getAllBuckets().first()
        for (remote in driveNoteFiles) {
            val local = localById[remote.entityId] ?: continue
            if (local.deletedAt != null) continue      // deleted here; the push publishes that
            if (!local.isSynced) continue              // unpublished local edit wins
            // Drive's own modification time settles it without a download: a file untouched
            // since our copy was written cannot contain a newer edit. Every note used to be
            // fetched here — three HTTP calls each — including the ones just fetched by the
            // insert pass above, which is what pushed a modest library past the 60s timeout.
            if (remote.modifiedAtMs > 0 && remote.modifiedAtMs <= local.updatedAt) continue
            val file = drive.downloadNoteFileById(remote.fileId) ?: continue
            if (file.updatedAt <= 0L) continue         // unstamped, cannot be compared
            if (file.updatedAt <= local.updatedAt) continue
            if (file.title == local.title && file.content == local.content) continue
            // Blank means the writer knows nothing about buckets, so the local one stands;
            // an unmatched name is left alone for the same reason it is skipped on insert.
            val bucketId = if (file.bucketName.isBlank()) local.bucketId
                           else allBuckets.find {
                               it.name.equals(file.bucketName, ignoreCase = true)
                           }?.id ?: local.bucketId
            db.noteDao().updateNote(
                local.copy(
                    title = file.title,
                    content = file.content,
                    bucketId = bucketId,
                    // Blank is "the writer does not do attachments" — the web app writes no
                    // such comment — so it must not strip photos added on the phone.
                    attachments = file.attachments.ifBlank { local.attachments },
                    updatedAt = file.updatedAt,
                    isSynced = true
                )
            )
        }
    }

    private suspend fun resolveBucketId(
        db: AppDatabase,
        allBuckets: MutableList<BucketEntity>,
        bucketName: String
    ): Long {
        val existing = allBuckets.find { it.name.equals(bucketName, ignoreCase = true) }
        if (existing != null) return existing.id
        val newBucket = BucketEntity(name = bucketName, sortOrder = 99)
        val newId = db.bucketDao().insertBucket(newBucket)
        allBuckets.add(newBucket.copy(id = newId))
        return newId
    }

    // ── Push ─────────────────────────────────────────────────────────

    private suspend fun pushToDrive(db: AppDatabase, drive: DriveRepository): Boolean {
        // Include soft-deleted todos (deletedAt IS NOT NULL) so Drive's JSON reflects deletions.
        // This closes the resurrection window: if a todo is hard-purged before the next sync,
        // Drive has already seen the deletedAt marker and the pull will skip re-insertion.
        val todos = db.todoDao().getAllTodosIncludingDeleted()
        // Collected once and used for both the todo bucket names and buckets.json below.
        val allBuckets = db.bucketDao().getAllBuckets().first()
        val buckets = allBuckets.associateBy { it.id }
        val dtos = todos.map { todo ->
            TodoSyncDto(
                id = todo.id,
                title = todo.title,
                bucket = buckets[todo.bucketId]?.name ?: "Other",
                priority = Priority.fromRank(todo.priority).name,
                isDone = todo.isDone,
                dueDate = todo.dueDate,
                createdAt = todo.createdAt,
                updatedAt = todo.updatedAt,
                deletedAt = todo.deletedAt,
                recurrence = todo.recurrence,
                leadDays = todo.leadDays,
                reminderAt = todo.reminderAt,
                isPinned = todo.isPinned,
                estimateMinutes = todo.estimateMinutes,
                // Stamping v2 is what makes a null on this row mean "cleared" rather than
                // "unknown" to whoever reads it back.
                schema = SCHEMA_V2,
                subtasks = db.subtaskDao().getSubtasksOnce(todo.id).map {
                    SubtaskSyncDto(title = it.title, isDone = it.isDone, sortOrder = it.sortOrder)
                },
                attachments = todo.attachments,
                isArchived = todo.isArchived,
                archivedAt = todo.archivedAt
            )
        }
        val todosOk = drive.uploadTodosJson(json.encodeToString(dtos))

        // Publish the bucket list so the web app knows which buckets are vault. Without this
        // it fell back to a hardcoded list and rendered a bucket Carl had marked private as
        // an ordinary one. Best-effort: a failure here must not fail the whole sync, and the
        // web app treats a missing buckets.json as "trust nothing", not "nothing is vault".
        val bucketDtos = allBuckets.map { b -> BucketSyncDto(name = b.name, isVault = b.isVault) }
        runCatching { drive.uploadBucketsJson(json.encodeToString(bucketDtos)) }

        // Publish journal templates so a replacement phone arrives with them already built.
        // Deleted ones are excluded, so a sync never resurrects a template Carl removed.
        runCatching {
            val dao = db.journalTemplateDao()
            val bucketNamesById = db.bucketDao().getAllBuckets().first().associate { it.id to it.name }
            val payload = JournalTemplatesSyncDto(
                templates = dao.getAllTemplatesIncludingDeleted()
                    .filter { it.deletedAt == null }
                    .map {
                        TemplateSyncDto(
                            name = it.name,
                            fieldsJson = it.fieldsJson,
                            isPrivateByDefault = it.isPrivateByDefault,
                            sortOrder = it.sortOrder,
                            builtInKey = it.builtInKey,
                            bucketName = bucketNamesById[it.bucketId].orEmpty(),
                            reminderRule = it.reminderRule
                        )
                    },
                optionLists = dao.getOptionListsOnce().map {
                    OptionListSyncDto(
                        id = it.id,
                        name = it.name,
                        options = JournalTemplateSeeder.decodeOptions(it.optionsJson),
                        builtInKey = it.builtInKey
                    )
                }
            )
            drive.uploadJournalTemplatesJson(json.encodeToString(payload))
        }

        // Publish the travelling settings so a replacement phone inherits them. Gated on this
        // device having already settled its own settings: pushing before the pull has happened
        // would write a fresh install's blank defaults over Carl's real setup.
        runCatching {
            val prefsStore = CarlsBrainApp.userPreferences
            if (prefsStore.preferencesPulledFromDrive.first()) {
                drive.uploadPreferencesJson(
                    json.encodeToString(prefsStore.snapshotForSync())
                )
            }
        }

        // Keep the web app's API keys in step with the phone's. The OpenAI key in particular
        // lived only in DataStore, so web transcription had no key at all and failed every
        // time. Merging, so a key set from the web is never blanked by a phone that lacks one.
        runCatching {
            val prefs = CarlsBrainApp.userPreferences
            drive.publishSettingsKeys(
                anthropicKey = prefs.anthropicApiKey.first(),
                openaiKey = prefs.openaiApiKey.first()
            )
        }

        // Journal entries. Same self-healing check as notes: an entry the app believes is on
        // Drive but is not gets re-queued, so a file lost outside the app cannot leave the two
        // silently disagreeing.
        val driveJournalIds = drive.listJournalIds()
        if (driveJournalIds.isNotEmpty()) {
            val missing = db.journalDao().getSyncedIds().filterNot { it in driveJournalIds }
            if (missing.isNotEmpty()) db.journalDao().markUnsynced(missing)
        }
        db.journalDao().getUnsyncedEntries().forEach { entry ->
            val ok = drive.uploadJournalEntry(
                entryId = entry.id,
                content = entry.content,
                prompt = entry.prompt,
                isPrivate = entry.isPrivate,
                createdAt = entry.createdAt,
                attachments = entry.attachments,
                updatedAt = entry.updatedAt,
                bucketName = entry.bucketId?.let { db.bucketDao().getBucketById(it)?.name }.orEmpty(),
                answersJson = entry.answersJson,
                mood = entry.mood
            )
            if (ok) db.journalDao().markSynced(entry.id)
        }
        db.journalDao().getDeletedEntries().first().forEach { entry ->
            drive.deleteJournalEntry(entry.id)
        }

        // One-shot republish for the bucket comment: notes written before it was unconditional
        // carry none, and a reader with nothing to go on has to guess.
        if (!CarlsBrainApp.userPreferences.noteBucketsRepublished.first()) {
            val ids = db.noteDao().getAllNoteIds()
            if (ids.isNotEmpty()) db.noteDao().markNotesUnsynced(ids)
            CarlsBrainApp.userPreferences.markNoteBucketsRepublished()
        }

        // One-shot republish for wire format v2: files already on Drive carry no attachment
        // comment on notes and no structured answers on journal entries, and nothing would
        // otherwise rewrite them until Carl happened to edit each one. Deliberately its own
        // flag — the bucket one above is already set on any phone that has run 2.11.1.
        if (!CarlsBrainApp.userPreferences.wireV2Republished.first()) {
            val noteIds = db.noteDao().getAllNoteIds()
            if (noteIds.isNotEmpty()) db.noteDao().markNotesUnsynced(noteIds)
            val journalIds = db.journalDao().getSyncedIds()
            if (journalIds.isNotEmpty()) db.journalDao().markUnsynced(journalIds)
            CarlsBrainApp.userPreferences.markWireV2Republished()
        }

        db.noteDao().getUnsyncedNotes().forEach { note ->
            val bucketName = db.bucketDao().getBucketById(note.bucketId)?.name ?: "Personal"
            // The stamp is what lets another device tell which copy is newer.
            if (drive.uploadNoteFile(
                    note.id, note.title, note.content, bucketName, note.updatedAt,
                    attachments = note.attachments
                )
            ) {
                db.noteDao().markSynced(note.id)
            }
        }

        // Remove Drive files for notes that have been soft-deleted locally
        db.noteDao().getDeletedNotes().first().forEach { note ->
            drive.deleteNoteFile(note.id)
        }

        return todosOk
    }

    /** Journal templates and their shared option lists, as published to Drive. */
    @Serializable
    data class JournalTemplatesSyncDto(
        val templates: List<TemplateSyncDto> = emptyList(),
        val optionLists: List<OptionListSyncDto> = emptyList()
    )

    @Serializable
    data class TemplateSyncDto(
        val name: String = "",
        val fieldsJson: String = "",
        val isPrivateByDefault: Boolean = false,
        val sortOrder: Int = 0,
        val builtInKey: String = "",
        /** Bucket NAME, not id — ids differ between devices. Resolved on the way back in. */
        val bucketName: String = "",
        val reminderRule: String = ""
    )

    @Serializable
    data class OptionListSyncDto(
        val id: Long = 0L,
        val name: String = "",
        val options: List<String> = emptyList(),
        val builtInKey: String = ""
    )

    /** Bucket config published for the web app. Names must match those used in TodoSyncDto. */
    @Serializable
    data class BucketSyncDto(
        val name: String,
        val isVault: Boolean
    )

    /**
     * The todo wire format shared with the web app.
     *
     * recurrence, leadDays, reminderAt and estimateMinutes were previously absent here while
     * the web app's editor already wrote recurrence and leadDays. The result was one-way loss:
     * a recurring todo created on the laptop arrived on the phone as a plain one, and a
     * recurring todo created on the phone showed as one-off on the laptop. Nothing was
     * corrupted — both sides preserve fields they do not understand when merging an existing
     * row — but the round trip quietly dropped them.
     *
     * All new fields are optional with defaults, so an older todos.json still parses.
     */
    @Serializable
    data class TodoSyncDto(
        val id: Long,
        val title: String,
        val bucket: String,
        val priority: String,
        val isDone: Boolean,
        val dueDate: Long? = null,
        val createdAt: Long,
        val updatedAt: Long,
        val deletedAt: Long? = null,
        val recurrence: String = "",
        val leadDays: Int = 0,
        val reminderAt: Long? = null,
        val isPinned: Boolean = false,
        val estimateMinutes: Int? = null,
        /**
         * Wire format version of this row.
         *
         * 1 (or absent) is anything written before subtasks existed. It matters because of what
         * a `null` means: in v1 a null [reminderAt] could only mean "the writer did not know
         * about this field", so it was read as "keep what is here" — which made a reminder
         * cleared on the web app come back and fire anyway. From v2 a null is authoritative and
         * clears the value, because a v2 writer always sends every field it knows.
         */
        val schema: Int = SCHEMA_V1,
        /**
         * Ordered. **Null means "not stated"; an empty list means "genuinely none".**
         *
         * The distinction is not pedantry. A writer that knows nothing about subtasks — the web
         * editor merging onto a row that predates them — omits the field, and reading that as
         * an empty list would delete every subtask on the phone the moment a to-do was edited
         * on the laptop. Same reasoning for the three fields below.
         */
        val subtasks: List<SubtaskSyncDto>? = null,
        /** Comma-separated Drive ids, same encoding notes and journal entries use. */
        val attachments: String? = null,
        val isArchived: Boolean? = null,
        val archivedAt: Long? = null
    )

    /**
     * One subtask, by value.
     *
     * No id: subtask ids are per-device autoincrement values that mean nothing on another phone,
     * and the merge replaces the whole list rather than reconciling rows.
     */
    @Serializable
    data class SubtaskSyncDto(
        val title: String,
        val isDone: Boolean = false,
        val sortOrder: Int = 0
    )

    companion object {
        const val SCHEMA_V1 = 1

        /** Adds subtasks, attachments, archive state, and authoritative nulls. */
        const val SCHEMA_V2 = 2
    }
}
