package com.carlmanning.carlsbrain.ui.screens.meetings

import android.app.Application
import android.app.NotificationManager
import android.util.Log
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
import com.carlmanning.carlsbrain.data.local.worker.MeetingAudioStore
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

private const val TAG = "MeetingViewModel"

/** Which rung of the ladder wrote a transcript. Stored on the meeting, shown on its detail. */
object TranscriptSource {
    const val FIREFLIES = "FIREFLIES"
    const val WHISPER = "WHISPER"
    /** Android's on-device SpeechRecognizer, captured live during an ordinary recording. */
    const val LIVE = "LIVE"
    const val MANUAL = "MANUAL"
}
/**
 * Matches `[ACTION: task | bucket]` in a Claude reply.
 *
 * The closing bracket is REQUIRED, and that is the whole point. It used to be optional
 * (`\]?`) while the bucket group stayed non-greedy, so on `[ACTION: Review… | Work]` the engine
 * settled for the shortest bucket that let the pattern succeed — a single `W` — and stopped
 * there. Two things went wrong at once: every action item was filed under a bucket called "W",
 * which matches nothing and silently fell back to the default bucket; and stripping the match
 * out of the summary left `ork]` behind on screen. Requiring the bracket forces the bucket to
 * run to the end of the word.
 */
private val actionRegex = Regex("""\[\s*ACTION:\s*([^|\]\n]+?)\s*\|\s*([^\]\n]+?)\s*\]""", RegexOption.IGNORE_CASE)
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

    companion object {
        /**
         * Meetings some ViewModel has already taken responsibility for.
         *
         * Deliberately process-wide, not per-instance. The work it guards runs on
         * [CarlsBrainApp.appScope], and more than one MeetingViewModel exists at a time —
         * MeetingDetailScreen creates its own. With a per-instance set, opening a meeting to
         * look at it spawned a second ViewModel with an empty guard, whose recovery sweep saw
         * the meeting still at PROCESSING with no transcript and started a second analysis
         * racing the first. Two summaries were produced and the last write won, which looked
         * like the app inventing a summary and then correcting itself.
         *
         * A guard has to live at the same scope as the work it guards.
         */
        private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

        /** Released once a meeting reaches a terminal state, so a retry can claim it again. */
        internal fun releaseClaim(meetingId: Long) { inFlight.remove(meetingId) }
    }

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
        // Both sweeps run on appScope for the same reason as the collector below: they end in
        // the same upload-and-analyse chain, and must not die when this screen closes.
        CarlsBrainApp.appScope.launch { recoverPendingRecordings() }
        CarlsBrainApp.appScope.launch { recoverStuckTranscribingMeetings() }
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
                        // NOT viewModelScope. Everything downstream of here — a Drive upload,
                        // the Fireflies call, Whisper, then Claude — takes tens of seconds on a
                        // long recording, and leaving the Meetings screen cancels viewModelScope
                        // mid-flight. That stranded the meeting at PROCESSING with no transcript
                        // and nothing recording that it had failed. appScope lives as long as the
                        // process; if the process itself dies, recoverPendingRecordings picks the
                        // meeting up next time this screen opens.
                        CarlsBrainApp.appScope.launch { handleRecordingStopped(serviceState) }
                    }
                    is MeetingServiceState.Idle -> {
                        // nothing
                    }
                }
            }
        }
    }

    /**
     * The id of the meeting being recorded right now, or -1 if none.
     *
     * Recovery must skip exactly that one meeting and nothing else. The previous version asked
     * far broader questions — "is the ambient buffer Off?" and "is the recording service Idle?"
     * — and both were wrong. `stopMeeting` deliberately goes back to buffering once a recording
     * finishes, so the buffer is *never* Off at the moment recovery is needed, and the recording
     * service is left holding a `Stopped` state rather than `Idle`. The guards were therefore
     * false precisely when they had to be true, and the whole recovery path was dead: a meeting
     * recorded from the tile sat at RECORDING with its audio on disk and nothing ever picked it
     * up.
     */
    private fun currentlyRecordingId(): Long = when (val a = AmbientBufferService.state.value) {
        is AmbientState.Recording -> a.meetingId
        else -> when (val m = MeetingRecordingService.state.value) {
            is MeetingServiceState.Recording -> m.meetingId
            else -> -1L
        }
    }

    private fun claim(meetingId: Long): Boolean = inFlight.add(meetingId)

    /**
     * Picks up recordings that no live ViewModel ever heard about.
     *
     * Two cases, both real. A recording started from the Quick Settings tile with the app closed
     * leaves a meeting at RECORDING with its audio saved and nothing to carry it forward. And a
     * meeting whose transcription was interrupted mid-flight is left at PROCESSING, which no
     * other sweep looks at — [recoverStuckTranscribingMeetings] only covers TRANSCRIBING.
     *
     * Safe because it requires a saved audio file, which is only written once the encoder has
     * closed, and skips the one meeting actually being recorded.
     */
    private suspend fun recoverPendingRecordings() {
        val recordingNow = currentlyRecordingId()
        val pending = db.meetingDao().getAllMeetings().first().filter {
            it.id != recordingNow &&
                it.localAudioPath.isNotBlank() &&
                (it.status == "RECORDING" || (it.status == "PROCESSING" && it.transcript.isBlank()))
        }
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
            // The same process-wide claim the recording path uses. Without it, the two live
            // MeetingViewModel instances — the list screen's and the detail screen's — each ran
            // this sweep on init, so opening a meeting seconds after the list started two paid
            // Whisper uploads of the same file and two analyses racing to write the summary.
            // That is the "wild summary, then it corrected itself" symptom.
            if (!claim(meeting.id)) continue
            val audioFile = java.io.File(meeting.localAudioPath)
            val whisperKey = CarlsBrainApp.userPreferences.openaiApiKey.first()
            if (meeting.localAudioPath.isNotBlank() && audioFile.exists() && audioFile.length() > 0 && whisperKey.isNotBlank()) {
                _uiState.update { it.copy(isProcessing = true, isTranscribing = true) }
                val result = whisper.transcribe(audioFile)
                _uiState.update { it.copy(isTranscribing = false) }
                val transcript = result.getOrNull()
                if (!transcript.isNullOrBlank()) {
                    val withTranscript = meeting.copy(
                        transcript = transcript,
                        transcriptSource = TranscriptSource.WHISPER,
                        status = "PROCESSING",
                        updatedAt = System.currentTimeMillis()
                    )
                    db.meetingDao().updateMeeting(withTranscript)
                    // analyzeTranscript owns the claim from here and releases it when the
                    // meeting reaches a terminal state.
                    analyzeTranscript(withTranscript)
                    continue
                }
            }
            // Can't recover — demote so it's not stuck forever
            db.meetingDao().updateMeeting(meeting.copy(status = "AUDIO_ONLY", updatedAt = System.currentTimeMillis()))
            // AUDIO_ONLY is terminal for this pass, so the claim has to go back: otherwise a
            // later retry — a re-transcribe tap, or the next sweep — would find it held and
            // silently do nothing.
            releaseClaim(meeting.id)
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
                                transcript = wt,
                                transcriptSource = TranscriptSource.WHISPER,
                                status = "PROCESSING",
                                updatedAt = System.currentTimeMillis()
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

    /**
     * Runs the full transcription ladder again against a meeting's saved audio.
     *
     * The gap this fills: a meeting that ends up DONE with an empty transcript — which is what a
     * failed transcription used to produce — offered only "paste a transcript". The app was
     * holding the recording and had no way to be asked to try again. This is that way.
     *
     * On appScope, not viewModelScope, because it is the same long upload-and-analyse chain and
     * Carl will navigate away from it.
     */
    fun retranscribeFromAudio(meetingId: Long) {
        CarlsBrainApp.appScope.launch {
            val meeting = db.meetingDao().getMeetingById(meetingId) ?: return@launch
            val audioFile = resolveAudio(meeting)
            if (audioFile == null) {
                _uiState.update {
                    it.copy(errorMessage = "The audio for this meeting could not be found")
                }
                return@launch
            }
            // Cleared so the ladder cannot mistake a stale title for a finished meeting, and so
            // the recovery sweep would pick this up again if the process died mid-way.
            val reset = meeting.copy(
                transcript = "",
                summary = "",
                status = "PROCESSING",
                updatedAt = System.currentTimeMillis()
            )
            db.meetingDao().updateMeeting(reset)
            // Deliberately re-claimable: this is Carl explicitly asking for another attempt.
            releaseClaim(meetingId)
            claim(meetingId)
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }

            val firefliesKey = CarlsBrainApp.userPreferences.firefliesApiKey.first()
            if (firefliesKey.isNotBlank()) {
                uploadToFirefliesViaDrive(reset, firefliesKey, audioFile)
            } else {
                fallBackToWhisper(reset, audioFile, "No Fireflies key set")
            }
        }
    }

    /**
     * The meeting's audio as a local file, fetching it back from Drive if need be.
     *
     * MidnightCleanupWorker deletes the local copy 30 days after recording, once the upload is
     * confirmed — so without this, re-transcribing anything older than a month failed even
     * though Drive still held the recording. Restores the path as well, so the next attempt and
     * the in-app player both work without another download.
     */
    private suspend fun resolveAudio(meeting: MeetingEntity): File? {
        val local = File(meeting.localAudioPath)
        if (meeting.localAudioPath.isNotBlank() && local.exists() && local.length() > 0) return local
        if (meeting.driveAudioFileId.isBlank()) return null

        _uiState.update { it.copy(isProcessing = true) }
        val bytes = drive.downloadMeetingAudio(meeting.driveAudioFileId) ?: return null
        if (bytes.isEmpty()) return null
        val restored = MeetingAudioStore.fileFor(getApplication(), meeting.id)
        return runCatching {
            restored.writeBytes(bytes)
            db.meetingDao().updateMeeting(
                meeting.copy(
                    localAudioPath = restored.absolutePath,
                    updatedAt = System.currentTimeMillis()
                )
            )
            restored
        }.getOrNull()
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
        // PROCESSING is admitted too, but only when it carries no transcript, which means an
        // earlier attempt was interrupted before it produced one. A PROCESSING meeting that
        // does have a transcript is mid-analysis and must be left alone.
        if (meeting.status != "RECORDING" &&
            !(meeting.status == "PROCESSING" && meeting.transcript.isBlank())
        ) return
        // Both the recovery sweep and the state collector can arrive here for the same meeting,
        // and the status check above is a read-then-write two coroutines can interleave on.
        // Claiming here rather than at the call sites keeps it to one place.
        if (!claim(meeting.id)) return
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
            transcriptSource = if (hasAudio && whisperKey.isNotBlank()) TranscriptSource.WHISPER
                               else TranscriptSource.LIVE,
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
                transcriptSource = TranscriptSource.MANUAL,
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
            releaseClaim(done.id)
            _uiState.update { it.copy(isProcessing = false, newlyProcessedMeetingId = done.id) }
            fireMeetingReadyNotification(done.id, done.title)

            // Auto-sort into a bucket (best-effort, never blocks or fails processing)
            CarlsBrainApp.appScope.launch { autoSortBucket(done) }

            // Upload to Drive via WorkManager, NOT viewModelScope: navigating away from
            // Meetings cancelled the scope mid-upload and the meeting silently never reached
            // Drive — invisible on the web app, with nothing recording that it had failed.
            enqueueDriveUpload(done.id)
        }.onFailure { e ->
            db.meetingDao().updateMeeting(meeting.copy(status = "ERROR", updatedAt = System.currentTimeMillis()))
            releaseClaim(meeting.id)
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

    /**
     * Fireflies → Whisper → park. The documented ladder, which this genuinely was not.
     *
     * Every failure branch used to call [analyzeTranscript] directly with a blank transcript.
     * Claude can only invent a title from that, so a failed transcription produced a meeting
     * that looked *finished* — titled, status DONE, summary empty — with the reason discarded.
     * Whisper, the actual next rung, was never tried because the Fireflies branch returned
     * early in [handleRecordingStopped].
     *
     * Now a Fireflies failure falls through to Whisper, and if that fails too the meeting parks
     * as AUDIO_ONLY: the state that says "there is audio here and no transcript yet" and offers
     * a way back in. The reason reaches [MeetingUiState.errorMessage] either way.
     */
    private suspend fun uploadToFirefliesViaDrive(
        meeting: MeetingEntity,
        firefliesKey: String,
        audioFile: File
    ) {
        val date = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(meeting.recordedAt))

        val folderId = drive.createMeetingFolder(date)
            ?: return fallBackToWhisper(meeting, audioFile, "Drive folder could not be created")
        val audioId = drive.uploadMeetingAudio(folderId, audioFile.readBytes())
            ?: return fallBackToWhisper(
                meeting.copy(driveFolderId = folderId), audioFile,
                "Audio could not be uploaded to Drive"
            )
        val token = drive.getAccessToken()
            ?: return fallBackToWhisper(
                meeting.copy(driveFolderId = folderId, driveAudioFileId = audioId), audioFile,
                "No Google access token"
            )

        val audioUrl = drive.buildAudioDownloadUrl(audioId)
        // "CB{id}" prefix lets the sync worker match this transcript back to this meeting
        val firefliesTitle = "CB${meeting.id} $date"
        val uploaded = meeting.copy(driveFolderId = folderId, driveAudioFileId = audioId)

        fireflies.uploadAudio(
            apiKey = firefliesKey,
            audioUrl = audioUrl,
            title = firefliesTitle,
            bearerToken = token
        ).onSuccess {
            db.meetingDao().updateMeeting(
                uploaded.copy(
                    title = "Awaiting Fireflies transcription…",
                    status = "FIREFLIES_PROCESSING",
                    updatedAt = System.currentTimeMillis()
                )
            )
            // Fireflies now owns this meeting until its sync worker matches the transcript back.
            releaseClaim(meeting.id)
            _uiState.update { it.copy(isProcessing = false) }
        }.onFailure { e ->
            val reason = e.message ?: "Fireflies upload failed"
            Log.w(TAG, "Fireflies upload failed for meeting ${meeting.id}: $reason")
            fallBackToWhisper(uploaded, audioFile, reason)
        }
    }

    /**
     * Second rung: transcribe locally-held audio with Whisper. Parks as AUDIO_ONLY if that
     * fails too, so the meeting stays visibly unfinished rather than pretending otherwise.
     */
    private suspend fun fallBackToWhisper(
        meeting: MeetingEntity,
        audioFile: File,
        reason: String
    ) {
        Log.w(TAG, "Falling back to Whisper for meeting ${meeting.id}: $reason")
        val whisperKey = CarlsBrainApp.userPreferences.openaiApiKey.first()
        if (whisperKey.isNotBlank() && audioFile.exists() && audioFile.length() > 0) {
            db.meetingDao().updateMeeting(
                meeting.copy(status = "TRANSCRIBING", updatedAt = System.currentTimeMillis())
            )
            _uiState.update { it.copy(isProcessing = true, isTranscribing = true) }
            val transcript = whisper.transcribe(audioFile).getOrNull()
            _uiState.update { it.copy(isTranscribing = false) }
            if (!transcript.isNullOrBlank()) {
                val withTranscript = meeting.copy(
                    transcript = transcript,
                    transcriptSource = TranscriptSource.WHISPER,
                    status = "PROCESSING",
                    updatedAt = System.currentTimeMillis()
                )
                db.meetingDao().updateMeeting(withTranscript)
                _uiState.update {
                    it.copy(errorMessage = "Whisper used instead of Fireflies — $reason")
                }
                analyzeTranscript(withTranscript)
                return
            }
        }

        db.meetingDao().updateMeeting(
            meeting.copy(status = "AUDIO_ONLY", updatedAt = System.currentTimeMillis())
        )
        releaseClaim(meeting.id)
        enqueueDriveUpload(meeting.id)
        _uiState.update {
            it.copy(
                isProcessing = false,
                newlyProcessedMeetingId = meeting.id,
                errorMessage = "Could not transcribe: $reason"
            )
        }
        fireMeetingReadyNotification(meeting.id, "Meeting recorded — could not transcribe")
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
