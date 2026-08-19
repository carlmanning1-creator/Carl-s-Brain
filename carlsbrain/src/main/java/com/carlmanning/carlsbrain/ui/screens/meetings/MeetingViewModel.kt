package com.carlmanning.carlsbrain.ui.screens.meetings

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.worker.FirefliesSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.MeetingUploadWorker
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.data.local.worker.AmbientState
import com.carlmanning.carlsbrain.data.local.worker.MeetingRecordingService
import com.carlmanning.carlsbrain.data.local.worker.MeetingServiceState
import com.carlmanning.carlsbrain.data.remote.ActionItem
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.WhisperClient
import com.carlmanning.carlsbrain.data.remote.appJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val actionRegex = Regex("""\[?\s*ACTION:\s*([^|\]\n]+?)\s*\|\s*([^\]\n]+?)\s*\]?""", RegexOption.IGNORE_CASE)
private val titleRegex = Regex("""^TITLE:\s*(.+)$""", RegexOption.MULTILINE)

data class MeetingUiState(
    val isRecording: Boolean = false,
    val recordingMeetingId: Long = -1L,
    val recordingDurationMs: Long = 0L,
    val liveTranscript: String = "",
    val isProcessing: Boolean = false,
    val isTranscribing: Boolean = false,
    val newlyProcessedMeetingId: Long? = null,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)
    private val claude = CarlsBrainApp.claudeClient
    private val whisper = WhisperClient(CarlsBrainApp.userPreferences)
    private val fireflies = com.carlmanning.carlsbrain.data.remote.FirefliesRepository()

    private val _vaultOpen = MutableStateFlow(false)
    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    val meetings: StateFlow<List<MeetingEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.meetingDao().getAllMeetings()
            else db.meetingDao().getNonVaultMeetings()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Buckets, used to resolve meeting.bucketId → colour on the meeting cards. */
    val buckets: StateFlow<List<com.carlmanning.carlsbrain.data.local.entity.BucketEntity>> =
        _vaultOpen
            .flatMapLatest { open ->
                if (open) db.bucketDao().getAllBuckets()
                else db.bucketDao().getNonVaultBuckets()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    /**
     * The rolling ambient buffer's state, so the Meetings screen can offer "record from the
     * buffer" and show how much audio would be recovered.
     */
    val ambientState: StateFlow<AmbientState> = AmbientBufferService.state

    /** Turns the buffered audio into a meeting, or stops one already running. */
    fun toggleBufferRecording(context: Context) {
        AmbientBufferService.send(context, AmbientBufferService.ACTION_TOGGLE)
    }

    init {
        viewModelScope.launch { recoverPendingRecordings() }
        viewModelScope.launch { recoverStuckTranscribingMeetings() }
        viewModelScope.launch {
            MeetingRecordingService.state.collect { serviceState ->
                when (serviceState) {
                    is MeetingServiceState.Recording -> {
                        _uiState.update {
                            it.copy(
                                isRecording = true,
                                recordingMeetingId = serviceState.meetingId,
                                recordingDurationMs = serviceState.durationMs,
                                liveTranscript = serviceState.transcript
                            )
                        }
                    }
                    is MeetingServiceState.Stopped -> {
                        _uiState.update { it.copy(isRecording = false, isProcessing = true) }
                        handleRecordingStopped(serviceState)
                    }
                    is MeetingServiceState.Idle -> {
                        // nothing
                    }
                }
            }
        }
    }

    /**
     * Picks up recordings that finished while no ViewModel was alive to hear about it.
     *
     * A recording started from the Quick Settings tile with the app closed leaves a meeting at
     * status RECORDING with its audio saved and nothing to carry it into transcription — this
     * screen opening is the next chance to do that. Only meetings that are genuinely finished
     * are touched: anything currently recording is skipped both by the service-state check and
     * by requiring a saved audio file, which is only written once the encoder closes.
     */
    private suspend fun recoverPendingRecordings() {
        if (AmbientBufferService.state.value !is AmbientState.Off) return
        if (MeetingRecordingService.state.value !is MeetingServiceState.Idle) return
        val pending = db.meetingDao().getAllMeetings().first()
            .filter { it.status == "RECORDING" && it.localAudioPath.isNotBlank() }
        for (meeting in pending) {
            if (!File(meeting.localAudioPath).let { it.exists() && it.length() > 0 }) continue
            handleRecordingStopped(
                MeetingServiceState.Stopped(
                    meetingId = meeting.id,
                    durationMs = meeting.durationMs,
                    localAudioPath = meeting.localAudioPath,
                    transcript = meeting.transcript
                )
            )
        }
    }

    private suspend fun recoverStuckTranscribingMeetings() {
        val stuck = db.meetingDao().getAllMeetings().first()
            .filter { it.status == "TRANSCRIBING" }
        for (meeting in stuck) {
            val audioFile = java.io.File(meeting.localAudioPath)
            val whisperKey = CarlsBrainApp.userPreferences.openaiApiKey.first()
            if (meeting.localAudioPath.isNotBlank() && audioFile.exists() && audioFile.length() > 0 && whisperKey.isNotBlank()) {
                _uiState.update { it.copy(isProcessing = true, isTranscribing = true) }
                val result = whisper.transcribe(audioFile)
                _uiState.update { it.copy(isTranscribing = false) }
                val transcript = result.getOrNull()
                if (!transcript.isNullOrBlank()) {
                    val withTranscript = meeting.copy(transcript = transcript, status = "PROCESSING", updatedAt = System.currentTimeMillis())
                    db.meetingDao().updateMeeting(withTranscript)
                    analyzeTranscript(withTranscript)
                    continue
                }
            }
            // Can't recover — demote so it's not stuck forever
            db.meetingDao().updateMeeting(meeting.copy(status = "AUDIO_ONLY", updatedAt = System.currentTimeMillis()))
            // Upload anyway. A meeting with no transcript is exactly the one Carl most wants
            // to reach on the work device, because the audio is the only copy of it.
            enqueueDriveUpload(meeting.id)
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun startRecording(context: Context) {
        if (_uiState.value.isRecording) return
        viewModelScope.launch {
            val meetingId = db.meetingDao().insertMeeting(
                MeetingEntity(status = "RECORDING")
            )
            val cutoff = if (CarlsBrainApp.userPreferences.meetingAutoCutoffEnabled.first())
                MeetingRecordingService.MAX_DURATION_MS else 0L
            val intent = Intent(context, MeetingRecordingService::class.java).apply {
                action = MeetingRecordingService.ACTION_START
                putExtra(MeetingRecordingService.EXTRA_MEETING_ID, meetingId)
                putExtra(MeetingRecordingService.EXTRA_AUTO_CUTOFF_MS, cutoff)
            }
            context.startForegroundService(intent)
        }
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, MeetingRecordingService::class.java).apply {
            action = MeetingRecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun consumeNewMeeting() {
        _uiState.update { it.copy(newlyProcessedMeetingId = null) }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun syncFromFireflies() {
        val request = OneTimeWorkRequestBuilder<FirefliesSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(FirefliesSyncWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        _uiState.update { it.copy(errorMessage = "Syncing from Fireflies…") }
    }

    fun retryAnalysis(meetingId: Long) {
        viewModelScope.launch {
            val meeting = db.meetingDao().getMeetingById(meetingId) ?: return@launch
            val updated = meeting.copy(status = "PROCESSING", updatedAt = System.currentTimeMillis())
            db.meetingDao().updateMeeting(updated)
            _uiState.update { it.copy(isProcessing = true) }

            // If transcript is blank but audio exists and Whisper key is set, try Whisper first
            if (meeting.transcript.isBlank() || updated.transcript.isBlank()) {
                val audioFile = java.io.File(meeting.localAudioPath)
                val whisperKey = CarlsBrainApp.userPreferences.openaiApiKey.first()
                if (meeting.localAudioPath.isNotBlank() && audioFile.exists() && audioFile.length() > 0 && whisperKey.isNotBlank()) {
                    _uiState.update { it.copy(isTranscribing = true) }
                    val whisperResult = whisper.transcribe(audioFile)
                    _uiState.update { it.copy(isTranscribing = false) }
                    whisperResult.getOrNull()?.let { wt ->
                        if (wt.isNotBlank()) {
                            val withWhisper = db.meetingDao().getMeetingById(meeting.id)?.copy(
                                transcript = wt, status = "PROCESSING", updatedAt = System.currentTimeMillis()
                            ) ?: return@launch
                            db.meetingDao().updateMeeting(withWhisper)
                            analyzeTranscript(withWhisper)
                            return@launch
                        }
                    }
                }
                // Whisper not available or returned blank — if audio exists, park as AUDIO_ONLY
                // rather than silently producing an empty-summary DONE meeting.
                val audioFile2 = java.io.File(meeting.localAudioPath)
                if (meeting.localAudioPath.isNotBlank() && audioFile2.exists() && audioFile2.length() > 0) {
                    db.meetingDao().updateMeeting(meeting.copy(status = "AUDIO_ONLY", updatedAt = System.currentTimeMillis()))
                    enqueueDriveUpload(meeting.id)
                    _uiState.update { it.copy(isProcessing = false) }
                    return@launch
                }
            }
            analyzeTranscript(updated)
        }
    }

    fun deleteMeeting(meeting: MeetingEntity) {
        viewModelScope.launch {
            db.meetingDao().softDeleteMeeting(meeting.id)
            // Publishes a meta.json marked deleted so the web app hides it now, rather than
            // still listing it until the files are purged 90 days later.
            enqueueDriveUpload(meeting.id)
        }
    }

    /** Pulls a soft-deleted meeting back out of the bin — backs the swipe-to-delete undo. */
    fun restoreMeeting(id: Long) {
        viewModelScope.launch {
            db.meetingDao().restoreMeetingFromBin(id)
        }
    }

    private suspend fun handleRecordingStopped(stopped: MeetingServiceState.Stopped) {
        val meeting = db.meetingDao().getMeetingById(stopped.meetingId) ?: return
        // Idempotency guard: if a second ViewModel instance collected the same Stopped event,
        // the meeting will already have moved past RECORDING status — skip re-processing.
        if (meeting.status != "RECORDING") return
        val updated = meeting.copy(
            durationMs = stopped.durationMs,
            localAudioPath = stopped.localAudioPath,
            transcript = stopped.transcript,
            status = "PROCESSING",
            updatedAt = System.currentTimeMillis()
        )
        db.meetingDao().updateMeeting(updated)
        MeetingRecordingService.resetState()

        val audioFile = java.io.File(stopped.localAudioPath)
        val hasAudio = stopped.localAudioPath.isNotBlank() && audioFile.exists() && audioFile.length() > 0

        // Try Fireflies upload first (higher quality, speaker-labelled transcription)
        val firefliesKey = CarlsBrainApp.userPreferences.firefliesApiKey.first()
        if (hasAudio && firefliesKey.isNotBlank()) {
            uploadToFirefliesViaDrive(updated, firefliesKey, audioFile)
            return
        }

        // Fallback: Whisper + Claude analysis
        val whisperKey = CarlsBrainApp.userPreferences.openaiApiKey.first()

        val finalTranscript: String
        if (hasAudio && whisperKey.isNotBlank()) {
            db.meetingDao().updateMeeting(updated.copy(status = "TRANSCRIBING", updatedAt = System.currentTimeMillis()))
            _uiState.update { it.copy(isProcessing = true, isTranscribing = true) }
            val whisperResult = whisper.transcribe(audioFile)
            _uiState.update { it.copy(isTranscribing = false) }
            finalTranscript = whisperResult.getOrElse {
                // Whisper failed — fall back to live transcript
                stopped.transcript
            }
        } else {
            finalTranscript = stopped.transcript
        }

        // If still no transcript and we have audio, park as AUDIO_ONLY
        if (finalTranscript.isBlank()) {
            if (hasAudio) {
                db.meetingDao().updateMeeting(updated.copy(status = "AUDIO_ONLY", updatedAt = System.currentTimeMillis()))
                enqueueDriveUpload(updated.id)
                _uiState.update { it.copy(isProcessing = false, newlyProcessedMeetingId = updated.id) }
                fireMeetingReadyNotification(updated.id, "Meeting recorded — tap to add transcript")
                return
            }
        }

        val withTranscript = updated.copy(
            transcript = finalTranscript,
            status = "PROCESSING",
            updatedAt = System.currentTimeMillis()
        )
        db.meetingDao().updateMeeting(withTranscript)
        analyzeTranscript(withTranscript)
    }

    fun submitManualTranscript(meetingId: Long, transcript: String) {
        if (transcript.isBlank()) return
        viewModelScope.launch {
            val meeting = db.meetingDao().getMeetingById(meetingId) ?: return@launch
            val updated = meeting.copy(
                transcript = transcript,
                status = "PROCESSING",
                updatedAt = System.currentTimeMillis()
            )
            db.meetingDao().updateMeeting(updated)
            _uiState.update { it.copy(isProcessing = true) }
            analyzeTranscript(updated)
        }
    }

    private suspend fun analyzeTranscript(meeting: MeetingEntity) {
        val dateStr = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(meeting.recordedAt))
        val bucketNames = db.bucketDao().getNonVaultBuckets().first()
            .joinToString(", ") { it.name }
            .ifEmpty { "SES, Family, Work, Personal, Other" }
        val prompt = if (meeting.transcript.isBlank()) {
            // No transcript — just generate a title. Use TITLE: prefix so parsing is consistent.
            "Generate a short descriptive title (max 6 words) for a meeting recorded on $dateStr with no transcript.\nRespond in exactly this format:\nTITLE: [title here]"
        } else {
            """
Analyse this meeting transcript. Produce exactly this format — do not deviate:

TITLE: [brief descriptive title, max 8 words]

## Summary
[3-5 sentence overview of what was discussed]

## Key Decisions
- [decision, or omit section if none]

## Action Items
[ACTION: task description | bucket]
[repeat for each action item, or omit section if none]
Example: [ACTION: Call John about insurance renewal | Work]
IMPORTANT: Every action item MUST use exactly this format with square brackets, ACTION: prefix, and pipe separator.

Valid buckets: $bucketNames. Infer from context.

Transcript:
${meeting.transcript}
            """.trimIndent()
        }

        claude.chat(
            messages = listOf(ApiMessage("user", prompt)),
            systemPrompt = "You analyse meeting transcripts concisely and accurately.",
            maxTokens = 2048
        ).onSuccess { response ->
            val title = titleRegex.find(response)?.groupValues?.get(1)?.trim()
                ?: "Meeting $dateStr"
            val actionItems = actionRegex.findAll(response).map { m ->
                ActionItem(m.groupValues[1].trim(), m.groupValues[2].trim())
            }.toList()
            val summary = response
                .replace(titleRegex, "")
                .replace(actionRegex, "")
                .trim()

            val done = meeting.copy(
                title = title,
                summary = summary,
                pendingActionItems = appJson.encodeToString(actionItems),
                status = "DONE",
                updatedAt = System.currentTimeMillis()
            )
            db.meetingDao().updateMeeting(done)
            _uiState.update { it.copy(isProcessing = false, newlyProcessedMeetingId = done.id) }
            fireMeetingReadyNotification(done.id, done.title)

            // Auto-sort into a bucket (best-effort, never blocks or fails processing)
            viewModelScope.launch { autoSortBucket(done) }

            // Upload to Drive via WorkManager, NOT viewModelScope: navigating away from
            // Meetings cancelled the scope mid-upload and the meeting silently never reached
            // Drive — invisible on the web app, with nothing recording that it had failed.
            enqueueDriveUpload(done.id)
        }.onFailure { e ->
            db.meetingDao().updateMeeting(meeting.copy(status = "ERROR", updatedAt = System.currentTimeMillis()))
            // Analysis failed, but the recording still exists and is still worth having.
            enqueueDriveUpload(meeting.id)
            _uiState.update { it.copy(isProcessing = false, errorMessage = e.message ?: "Failed to analyse meeting") }
        }
    }

    /**
     * Best-effort Claude bucket auto-sort for a processed meeting.
     * Mirrors the capture flow's auto-tag mechanism (JSON-only bucket classification).
     * Only non-vault buckets are candidates, so a meeting is never auto-filed into the vault.
     * Any failure leaves bucketId untouched (null) — the UI falls back gracefully.
     */
    private suspend fun autoSortBucket(meeting: MeetingEntity) {
        runCatching {
            val bucketList = db.bucketDao().getNonVaultBuckets().first()
            if (bucketList.isEmpty()) return@runCatching

            val context = meeting.summary.trim().ifBlank { meeting.transcript.take(500).trim() }
            if (context.isBlank() && meeting.title.isBlank()) return@runCatching

            val bucketNames = bucketList.joinToString("|") { it.name }
            val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>"}
Which bucket does this meeting belong to?
Title: "${meeting.title}"
Summary: "$context""""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You classify meetings into buckets. Return only valid JSON, nothing else."
            ).onSuccess { response ->
                runCatching {
                    val tag = appJson.decodeFromString<MeetingBucketTag>(response.trim())
                    val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                    if (bucket != null) {
                        db.meetingDao().setBucket(meeting.id, bucket.id)
                    }
                }
            }
        }
    }

    @Serializable
    private data class MeetingBucketTag(val bucket: String = "")

    private fun fireMeetingReadyNotification(meetingId: Long, title: String) {
        val ctx: Context = getApplication()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val tapIntent = Intent(ctx, com.carlmanning.carlsbrain.MainActivity::class.java).apply {
            putExtra(com.carlmanning.carlsbrain.MainActivity.EXTRA_OPEN_MEETING_ID, meetingId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, meetingId.toInt(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CarlsBrainApp.MEETINGS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Meeting ready")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            .notify(meetingId.toInt(), notification)
    }

    private suspend fun uploadToFirefliesViaDrive(
        meeting: MeetingEntity,
        firefliesKey: String,
        audioFile: File
    ) {
        val date = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date(meeting.recordedAt))
        val folderId = drive.createMeetingFolder(date) ?: run {
            // Drive unavailable — fall back to Claude analysis
            _uiState.update { it.copy(isProcessing = true) }
            analyzeTranscript(meeting)
            return
        }
        val audioId = drive.uploadMeetingAudio(folderId, audioFile.readBytes()) ?: run {
            _uiState.update { it.copy(isProcessing = true) }
            analyzeTranscript(meeting.copy(driveFolderId = folderId))
            return
        }
        val token = drive.getAccessToken() ?: run {
            _uiState.update { it.copy(isProcessing = true) }
            analyzeTranscript(meeting.copy(driveFolderId = folderId, driveAudioFileId = audioId))
            return
        }
        val audioUrl = drive.buildAudioDownloadUrl(audioId)
        // "CB{id}" prefix lets the sync worker match this transcript back to this meeting
        val firefliesTitle = "CB${meeting.id} $date"

        val success = fireflies.uploadAudio(
            apiKey = firefliesKey,
            audioUrl = audioUrl,
            title = firefliesTitle,
            bearerToken = token
        ).getOrDefault(false)

        if (success) {
            db.meetingDao().updateMeeting(
                meeting.copy(
                    title = "Awaiting Fireflies transcription…",
                    status = "FIREFLIES_PROCESSING",
                    driveFolderId = folderId,
                    driveAudioFileId = audioId,
                    updatedAt = System.currentTimeMillis()
                )
            )
            _uiState.update { it.copy(isProcessing = false) }
        } else {
            // Fireflies rejected — fall back to Claude analysis
            _uiState.update { it.copy(isProcessing = true) }
            analyzeTranscript(meeting.copy(driveFolderId = folderId, driveAudioFileId = audioId))
        }
    }

    /**
     * Queues the Drive upload for [meetingId].
     *
     * Unique work per meeting with REPLACE, so re-processing a meeting supersedes any queued
     * attempt instead of racing it. Requires a network and backs off on failure, so a meeting
     * recorded offline uploads when connectivity returns rather than being lost.
     */
    private fun enqueueDriveUpload(meetingId: Long) {
        val request = OneTimeWorkRequestBuilder<MeetingUploadWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putLong(MeetingUploadWorker.KEY_MEETING_ID, meetingId)
                    .build()
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            MeetingUploadWorker.workName(meetingId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

}
