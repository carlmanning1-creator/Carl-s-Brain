package com.carlmanning.carlsbrain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.ChatThreadEntity
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CaptureRequest(
    val type: String = "TODO",
    val startVoice: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    val urgentTodoCount: StateFlow<Int> = db.todoDao().getActiveTodos()
        .map { todos -> todos.count { it.priority in listOf(0, 1) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isVaultVisible = MutableStateFlow(false)
    val isVaultVisible: StateFlow<Boolean> = _isVaultVisible.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _pendingCapture = MutableStateFlow<CaptureRequest?>(null)
    val pendingCapture: StateFlow<CaptureRequest?> = _pendingCapture.asStateFlow()

    private val _pendingOpenNoteId = MutableStateFlow<Long?>(null)
    val pendingOpenNoteId: StateFlow<Long?> = _pendingOpenNoteId.asStateFlow()

    private val _pendingOpenTodoId = MutableStateFlow<Long?>(null)
    val pendingOpenTodoId: StateFlow<Long?> = _pendingOpenTodoId.asStateFlow()

    /** True while an unlock request is waiting on the host (PIN dialog / biometric prompt). */
    private val _vaultUnlockRequested = MutableStateFlow(false)
    val vaultUnlockRequested: StateFlow<Boolean> = _vaultUnlockRequested.asStateFlow()

    /**
     * Closing the vault is instant and unauthenticated.
     * Opening it never happens here — it raises [vaultUnlockRequested] so the host
     * (MainActivity) can authenticate first. Every entry point routes through this,
     * so no call site can open the vault directly.
     */
    fun toggleVaultVisibility() {
        if (_isVaultVisible.value) {
            _isVaultVisible.value = false
        } else {
            _vaultUnlockRequested.value = true
        }
    }

    /** Called by the host only after successful authentication. */
    fun onVaultUnlockGranted() {
        _vaultUnlockRequested.value = false
        _isVaultVisible.value = true
    }

    /** Authentication cancelled or failed — vault stays closed. */
    fun cancelVaultUnlock() {
        _vaultUnlockRequested.value = false
    }

    /** Force the vault shut (e.g. when the app re-locks). */
    fun lockVault() {
        _vaultUnlockRequested.value = false
        _isVaultVisible.value = false
    }

    fun requestCapture(type: String = "TODO", startVoice: Boolean = false) {
        _pendingCapture.value = CaptureRequest(type, startVoice)
    }

    fun consumePendingCapture() {
        _pendingCapture.value = null
    }

    fun requestOpenNote(noteId: Long) {
        _pendingOpenNoteId.value = noteId
    }

    fun consumePendingOpenNoteId() {
        _pendingOpenNoteId.value = null
    }

    fun requestOpenTodo(todoId: Long) {
        _pendingOpenTodoId.value = todoId
    }

    fun consumePendingOpenTodoId() {
        _pendingOpenTodoId.value = null
    }

    private val _pendingStartMeeting = MutableStateFlow(false)
    val pendingStartMeeting: StateFlow<Boolean> = _pendingStartMeeting.asStateFlow()

    fun requestStartMeeting() { _pendingStartMeeting.value = true }
    fun consumePendingStartMeeting() { _pendingStartMeeting.value = false }

    private val _pendingOpenMeetingId = MutableStateFlow<Long?>(null)
    val pendingOpenMeetingId: StateFlow<Long?> = _pendingOpenMeetingId.asStateFlow()

    fun requestOpenMeeting(meetingId: Long) { _pendingOpenMeetingId.value = meetingId }
    fun consumePendingOpenMeetingId() { _pendingOpenMeetingId.value = null }

    /** Non-null when a notification has requested the Chat screen open with a pre-loaded prompt. */
    private val _pendingChatPrompt = MutableStateFlow<String?>(null)
    val pendingChatPrompt: StateFlow<String?> = _pendingChatPrompt.asStateFlow()

    fun requestChatPrompt(prompt: String) { _pendingChatPrompt.value = prompt }
    fun consumePendingChatPrompt() { _pendingChatPrompt.value = null }

    /**
     * Creates a fresh chat thread for a pending prompt and hands back its id.
     *
     * A pending prompt has to land in an actual conversation: [pendingChatPrompt] is consumed by
     * ChatScreen, which is keyed on a threadId, so routing to the thread list instead left the
     * prompt sitting unsent.
     */
    fun startChatThreadForPrompt(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = db.chatDao().insertThread(ChatThreadEntity(title = "Weekly review"))
            onCreated(id)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(getApplication())
                .enqueueUniqueWork("drive_sync_now", ExistingWorkPolicy.REPLACE, request)
            WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(request.id)
                .first { it == null || it.state.isFinished }
            _isSyncing.value = false
        }
    }
}
