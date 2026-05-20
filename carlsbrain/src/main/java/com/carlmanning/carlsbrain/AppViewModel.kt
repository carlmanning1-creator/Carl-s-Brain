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
import androidx.work.ExistingPeriodicWorkPolicy
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
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
        .map { todos -> todos.count { it.priority in listOf("URGENT", "HIGH") && !it.isDone } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            val prefs = CarlsBrainApp.userPreferences
            val hour = prefs.morningDigestHour.first()
            val minute = prefs.morningDigestMinute.first()
            DigestScheduler.schedule(app, hour, minute, ExistingPeriodicWorkPolicy.KEEP)
        }
    }

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

    fun toggleVaultVisibility() {
        _isVaultVisible.value = !_isVaultVisible.value
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
                .first { it?.state?.isFinished == true }
            _isSyncing.value = false
        }
    }
}
