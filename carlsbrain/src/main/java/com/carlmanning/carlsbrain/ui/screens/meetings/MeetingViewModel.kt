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
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.local.worker.MeetingRecordingService
import com.carlmanning.carlsbrain.data.local.worker.MeetingServiceState
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.appJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ActionItem(val title: String, val bucket: String)

private val actionRegex = Regex("""\[ACTION:\s*([^\]|]+)\|\s*([^\]]+)]""", RegexOption.IGNORE_CASE)
private val titleRegex = Regex("""^TITLE:\s*(.+)$""", RegexOption.MULTILINE)

data class MeetingUiState(
    val isRecording: Boolean = false,
    val recordingMeetingId: Long = -1L,
    val recordingDurationMs: Long = 0L,
    val liveTranscript: String = "",
    val isProcessing: Boolean = false,
    val newlyProcessedMeetingId: Long? = null,
    val errorMessage: String? = null
)

class MeetingViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)
    private val claude = CarlsBrainApp.claudeClient

    val meetings: StateFlow<List<MeetingEntity>> = db.meetingDao()
        .getAllMeetings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    init {
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

    fun startRecording(context: Context) {
        if (_uiState.value.isRecording) return
        viewModelScope.launch {
            val meetingId = db.meetingDao().insertMeeting(
                MeetingEntity(status = "RECORDING")
            )
            val intent = Intent(context, MeetingRecordingService::class.java).apply {
                action = MeetingRecordingService.ACTION_START
                putExtra(MeetingRecordingService.EXTRA_MEETING_ID, meetingId)
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

    fun retryAnalysis(meetingId: Long) {
        viewModelScope.launch {
            val meeting = db.meetingDao().getMeetingById(meetingId) ?: return@launch
            val updated = meeting.copy(status = "PROCESSING", updatedAt = System.currentTimeMillis())
            db.meetingDao().updateMeeting(updated)
            _uiState.update { it.copy(isProcessing = true) }
            analyzeTranscript(updated)
        }
    }

    fun deleteMeeting(meeting: MeetingEntity) {
        viewModelScope.launch {
            db.meetingDao().softDeleteMeeting(meeting.id)
        }
    }

    private suspend fun handleRecordingStopped(stopped: MeetingServiceState.Stopped) {
        val meeting = db.meetingDao().getMeetingById(stopped.meetingId) ?: return
        val updated = meeting.copy(
            durationMs = stopped.durationMs,
            localAudioPath = stopped.localAudioPath,
            transcript = stopped.transcript,
            status = "PROCESSING",
            updatedAt = System.currentTimeMillis()
        )
        db.meetingDao().updateMeeting(updated)
        MeetingRecordingService.resetState()
        analyzeTranscript(updated)
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

Valid buckets: $bucketNames. Infer from context.

Transcript:
${meeting.transcript}
            """.trimIndent()
        }

        claude.chat(
            messages = listOf(ApiMessage("user", prompt)),
            systemPrompt = "You analyse meeting transcripts concisely and accurately."
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

            // Upload to Drive (best-effort, no blocking)
            viewModelScope.launch { uploadToDrive(done) }
        }.onFailure { e ->
            db.meetingDao().updateMeeting(meeting.copy(status = "ERROR", updatedAt = System.currentTimeMillis()))
            _uiState.update { it.copy(isProcessing = false, errorMessage = e.message ?: "Failed to analyse meeting") }
        }
    }

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

    private suspend fun uploadToDrive(meeting: MeetingEntity) {
        val date = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date(meeting.recordedAt))
        val safeName = meeting.title.take(40).replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val folderName = "$date $safeName"
        val folderId = drive.createMeetingFolder(folderName) ?: return

        val audioFile = File(meeting.localAudioPath)
        val audioId = if (audioFile.exists() && audioFile.length() > 0) {
            drive.uploadMeetingAudio(folderId, audioFile.readBytes()) ?: ""
        } else ""

        drive.uploadMeetingTextFile(folderId, "transcript.md", "# Transcript\n\n${meeting.transcript}")
        drive.uploadMeetingTextFile(folderId, "summary.md", "# ${meeting.title}\n\n${meeting.summary}")

        val fresh = db.meetingDao().getMeetingById(meeting.id) ?: return
        db.meetingDao().updateMeeting(
            fresh.copy(driveFolderId = folderId, driveAudioFileId = audioId, updatedAt = System.currentTimeMillis())
        )
    }
}
