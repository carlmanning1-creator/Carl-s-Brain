package com.carlmanning.carlsbrain.ui.screens.meetings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.data.remote.ActionItem
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.appJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class MeetingDetailUiState(
    val id: Long = 0,
    val title: String = "",
    val editableTitle: String = "",
    val summary: String = "",
    val transcript: String = "",
    val pendingActionItems: List<ActionItem> = emptyList(),
    val status: String = "IDLE",
    val recordedAt: Long = 0L,
    val durationMs: Long = 0L,
    val localAudioPath: String = "",
    val driveFolderId: String = "",
    val isLoading: Boolean = true,
    val isSharing: Boolean = false
)

class MeetingDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)

    private val _uiState = MutableStateFlow(MeetingDetailUiState())
    val uiState: StateFlow<MeetingDetailUiState> = _uiState.asStateFlow()

    fun loadMeeting(meetingId: Long) {
        viewModelScope.launch {
            val meeting = db.meetingDao().getMeetingById(meetingId) ?: run {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val items = runCatching {
                appJson.decodeFromString<List<ActionItem>>(meeting.pendingActionItems)
            }.getOrElse { emptyList() }

            _uiState.update {
                it.copy(
                    id = meeting.id,
                    title = meeting.title,
                    editableTitle = meeting.title,
                    summary = meeting.summary,
                    transcript = meeting.transcript,
                    pendingActionItems = items,
                    status = meeting.status,
                    recordedAt = meeting.recordedAt,
                    durationMs = meeting.durationMs,
                    localAudioPath = meeting.localAudioPath,
                    driveFolderId = meeting.driveFolderId,
                    isLoading = false
                )
            }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(editableTitle = title) }

    fun saveTitle() {
        viewModelScope.launch {
            val state = _uiState.value
            val meeting = db.meetingDao().getMeetingById(state.id) ?: return@launch
            db.meetingDao().updateMeeting(
                meeting.copy(title = state.editableTitle.trim().ifBlank { meeting.title }, updatedAt = System.currentTimeMillis())
            )
            _uiState.update { it.copy(title = state.editableTitle) }
        }
    }

    fun approveActionItem(index: Int) {
        viewModelScope.launch {
            val item = _uiState.value.pendingActionItems.getOrNull(index) ?: return@launch
            val buckets = db.bucketDao().getAllBuckets().first()
            val bucket = buckets.find { it.name.equals(item.bucket, ignoreCase = true) }
                ?: buckets.find { !it.isVault && it.name == "Other" }
                ?: buckets.firstOrNull { !it.isVault }
                ?: return@launch
            db.todoDao().insertTodo(
                TodoEntity(
                    title = item.title,
                    bucketId = bucket.id,
                    priority = Priority.NORMAL.rank,
                    sourceMeetingId = _uiState.value.id.takeIf { it != 0L }
                )
            )
            persistRemovedItem(index)
        }
    }

    fun rejectActionItem(index: Int) {
        viewModelScope.launch { persistRemovedItem(index) }
    }

    private suspend fun persistRemovedItem(index: Int) {
        val remaining = _uiState.value.pendingActionItems.toMutableList().also {
            if (index in it.indices) it.removeAt(index)
        }
        _uiState.update { it.copy(pendingActionItems = remaining) }
        val meeting = db.meetingDao().getMeetingById(_uiState.value.id) ?: return
        db.meetingDao().updateMeeting(
            meeting.copy(
                pendingActionItems = appJson.encodeToString(remaining),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun saveTranscriptOnly(transcript: String) {
        viewModelScope.launch {
            val meeting = db.meetingDao().getMeetingById(_uiState.value.id) ?: return@launch
            db.meetingDao().updateMeeting(
                meeting.copy(transcript = transcript, updatedAt = System.currentTimeMillis())
            )
            _uiState.update { it.copy(transcript = transcript) }
        }
    }

    fun shareMeetingToDrive() {
        val state = _uiState.value
        if (state.driveFolderId.isBlank() || state.isSharing) return
        _uiState.update { it.copy(isSharing = true) }
        viewModelScope.launch {
            val ctx: Context = getApplication()
            val webViewLink = drive.shareMeetingSummary(state.driveFolderId)
            if (webViewLink != null) {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Drive link", webViewLink))
                Toast.makeText(ctx, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Failed to share — summary not found in Drive", Toast.LENGTH_LONG).show()
            }
            _uiState.update { it.copy(isSharing = false) }
        }
    }

    fun deleteMeeting(onComplete: () -> Unit) {
        viewModelScope.launch {
            db.meetingDao().softDeleteMeeting(_uiState.value.id)
            onComplete()
        }
    }
}
