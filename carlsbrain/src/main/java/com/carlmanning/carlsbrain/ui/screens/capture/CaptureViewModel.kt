package com.carlmanning.carlsbrain.ui.screens.capture

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.data.voice.TapToTalkRecorder
import com.carlmanning.carlsbrain.domain.capture.SaveCapture
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

enum class CaptureType { NOTE, TODO }

data class CaptureUiState(
    val captureType: CaptureType = CaptureType.TODO,
    val title: String = "",
    val text: String = "",
    val selectedBucketId: Long? = null,
    val selectedPriority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val reminderAt: Long? = null,
    val recurrence: com.carlmanning.carlsbrain.domain.model.Recurrence = com.carlmanning.carlsbrain.domain.model.Recurrence.None,
    val pendingPhotoUris: List<Uri> = emptyList(),
    val isSaving: Boolean = false,
    val isListening: Boolean = false,
    /** A tap-to-talk recording is with OpenAI. */
    val isTranscribing: Boolean = false,
    val interimText: String = "",
    val suggestedBucket: BucketEntity? = null,
    val savedToBucket: String? = null,
    val errorMessage: String? = null,
    /** Non-blocking advisory (e.g. speech partially recovered). Not an error. */
    val hintMessage: String? = null
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val claude = CarlsBrainApp.claudeClient
    private val drive = DriveRepository(app)
    private val prefs = CarlsBrainApp.userPreferences
    private val tagJson = appJson

    private val _vaultOpen = MutableStateFlow(false)
    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    @OptIn(ExperimentalCoroutinesApi::class)
    val buckets: StateFlow<List<BucketEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.bucketDao().getAllBuckets()
            else db.bucketDao().getNonVaultBuckets()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    val savedToBucket = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // SpeechRecognizer must be created/destroyed on the main thread
    private var speechRecognizer: SpeechRecognizer? = null
    private var suggestionJob: Job? = null

    /** Tap-to-talk via OpenAI. The recogniser below is its fallback. */
    private val tapToTalk = TapToTalkRecorder(app, "quick capture")
    val voiceLevel: StateFlow<Float> = tapToTalk.level
    private var tapToTalkJob: Job? = null

    fun onTypeSelected(type: CaptureType) = _uiState.update { it.copy(captureType = type) }
    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onTextChange(text: String) {
        _uiState.update { it.copy(text = text, suggestedBucket = null) }
        enqueueBucketSuggestion(text)
    }
    fun onBucketSelected(bucketId: Long) {
        _uiState.update { it.copy(selectedBucketId = bucketId, suggestedBucket = null) }
        suggestionJob?.cancel()
    }

    fun acceptSuggestedBucket() {
        val suggested = _uiState.value.suggestedBucket ?: return
        _uiState.update { it.copy(selectedBucketId = suggested.id, suggestedBucket = null) }
    }

    /**
     * The most recent bucket suggestion, paired with the exact text it was made for.
     *
     * Only reused when the text still matches: a suggestion for what Carl had typed thirty
     * seconds ago is not a classification of what he is saving now.
     */
    private var lastSuggestion: Pair<String, BucketEntity>? = null

    private fun enqueueBucketSuggestion(text: String) {
        if (text.length < 30 || _uiState.value.selectedBucketId != null) return
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            delay(1500)
            val bucketList = buckets.value
            if (bucketList.isEmpty()) return@launch
            val bucketNames = bucketList.joinToString("|") { it.name }
            val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>"}
Suggest the best bucket for: "$text""""
            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You suggest buckets for notes and tasks. Return only valid JSON."
            ).onSuccess { response ->
                runCatching {
                    val tag = tagJson.decodeFromString<NoteTag>(response.trim())
                    val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                    if (bucket != null && _uiState.value.selectedBucketId == null) {
                        _uiState.update { it.copy(suggestedBucket = bucket) }
                        // Kept with the text it was derived from, so save() can reuse it rather
                        // than paying for a second classification of the same words. Capture is
                        // the app's most frequent action and it was making two Claude calls
                        // every time: one to suggest a bucket, then another on save that threw
                        // the first away.
                        lastSuggestion = text to bucket
                    }
                }
            }
        }
    }
    fun onPrioritySelected(priority: Priority) = _uiState.update { it.copy(selectedPriority = priority) }
    fun onDueDateChange(dateMs: Long?) = _uiState.update { it.copy(dueDate = dateMs) }
    fun onReminderChange(reminderAt: Long?) = _uiState.update { it.copy(reminderAt = reminderAt) }
    fun onRecurrenceChange(recurrence: Recurrence) = _uiState.update { it.copy(recurrence = recurrence) }

    fun addPendingPhoto(uri: Uri) {
        _uiState.update { it.copy(pendingPhotoUris = it.pendingPhotoUris + uri) }
    }

    fun removePendingPhoto(uri: Uri) {
        _uiState.update { it.copy(pendingPhotoUris = it.pendingPhotoUris - uri) }
    }

    // ---------- Voice capture ----------

    /**
     * The partial transcript we have already appended to [CaptureUiState.text] for the
     * current voice session. Kept so a late onResults can replace it instead of
     * duplicating it. Reset at the start of every session.
     */
    private var committedInterim: String? = null

    private fun appendSpeech(existing: String, addition: String): String =
        if (existing.isBlank()) addition else "$existing $addition"

    /**
     * Flushes the live partial transcript into the main text. Android's SpeechRecognizer
     * routinely reports ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT in a noisy cab, and before
     * this the on-screen partial was simply discarded — the user watched their words vanish.
     */
    private fun commitInterimText(hint: String?) {
        _uiState.update { state ->
            val interim = state.interimText.trim()
            if (interim.isBlank()) {
                state.copy(isListening = false, interimText = "", hintMessage = hint)
            } else {
                // Replace, don't stack, any interim already committed this session.
                val base = committedInterim
                    ?.let { state.text.removeSuffix(it).trimEnd() }
                    ?: state.text
                committedInterim = interim
                state.copy(
                    text = appendSpeech(base, interim),
                    isListening = false,
                    interimText = "",
                    hintMessage = hint
                )
            }
        }
    }

    /**
     * Records and transcribes with OpenAI when it can; otherwise the on-device recogniser.
     * Decided before recording, because a recording cannot be handed to the recogniser.
     */
    fun startListening() {
        if (tapToTalkJob?.isActive == true) return
        tapToTalkJob = viewModelScope.launch(Dispatchers.Main) {
            val ctx: Context = getApplication()
            if (TapToTalkRecorder.fallbackReason(ctx) != null) {
                startRecogniser()
                return@launch
            }
            destroySpeechRecognizer()
            _uiState.update { it.copy(isListening = true, interimText = "", hintMessage = null) }
            try {
                when (val rec = tapToTalk.record()) {
                    is TapToTalkRecorder.Recording.Speech -> {
                        _uiState.update { it.copy(isListening = false, isTranscribing = true) }
                        tapToTalk.transcribe(rec.pcm).fold(
                            onSuccess = { raw ->
                                val text = raw.trim()
                                if (text.isBlank()) {
                                    _uiState.update { it.copy(hintMessage = "Didn’t catch that — tap the mic to try again") }
                                } else {
                                    val newText = appendSpeech(_uiState.value.text, text)
                                    _uiState.update { it.copy(text = newText) }
                                    queueClaudeCleanup(newText)
                                }
                            },
                            onFailure = {
                                _uiState.update { it.copy(hintMessage = "Couldn’t transcribe that — check your connection and try again") }
                            }
                        )
                    }
                    TapToTalkRecorder.Recording.NoSpeech ->
                        _uiState.update { it.copy(hintMessage = "Didn’t hear anything — tap the mic to try again") }
                    is TapToTalkRecorder.Recording.Busy ->
                        _uiState.update { it.copy(hintMessage = rec.message) }
                    is TapToTalkRecorder.Recording.Failed ->
                        _uiState.update { it.copy(hintMessage = rec.message) }
                }
            } finally {
                _uiState.update { it.copy(isListening = false, isTranscribing = false) }
            }
        }
    }

    /** The on-device recogniser — used when OpenAI tap-to-talk is unavailable. */
    private fun startRecogniser() {
        viewModelScope.launch(Dispatchers.Main) {
            val ctx: Context = getApplication()
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return@launch
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return@launch
            destroySpeechRecognizer()
            committedInterim = null
            _uiState.update { it.copy(hintMessage = null) }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _uiState.update { it.copy(isListening = true, interimText = "") }
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _uiState.update { it.copy(isListening = false) }
                    }
                    override fun onError(error: Int) {
                        // Never drop what the user already said — keep the partial transcript
                        // and tell them it may be incomplete.
                        val hadInterim = _uiState.value.interimText.isNotBlank()
                        commitInterimText(
                            if (hadInterim) "Didn’t catch all of that — check the text"
                            else "Didn’t catch that — tap the mic to try again"
                        )
                    }
                    override fun onResults(results: Bundle?) {
                        val recognised = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (recognised.isNullOrBlank()) {
                            // Blank final result: fall back to whatever we heard live.
                            commitInterimText(null)
                            return
                        }
                        // Supersede any partial already committed for this session.
                        val base = committedInterim
                            ?.let { _uiState.value.text.removeSuffix(it).trimEnd() }
                            ?: _uiState.value.text
                        committedInterim = null
                        val newText = appendSpeech(base, recognised)
                        _uiState.update {
                            it.copy(
                                text = newText,
                                isListening = false,
                                interimText = ""
                            )
                        }
                        queueClaudeCleanup(newText)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: return
                        _uiState.update { it.copy(interimText = partial) }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 12000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                }
                startListening(intent)
            }
        }
    }

    fun stopListening() {
        // Tap-to-talk: end the recording now and transcribe what was said.
        tapToTalk.finish()
        viewModelScope.launch(Dispatchers.Main) {
            speechRecognizer?.stopListening()
            // Commit immediately so the words are safe even if no final result ever
            // arrives; a later onResults supersedes this partial rather than repeating it.
            commitInterimText(null)
        }
    }

    /**
     * Abandons any voice capture — the screen is going away. Main thread. The tap-to-talk
     * recording is dropped (its microphone is released in TapToTalkRecorder's finally); the
     * recogniser's partial text is kept, then the recogniser is destroyed.
     */
    fun cancelListening() {
        tapToTalkJob?.cancel()
        if (speechRecognizer != null) {
            commitInterimText(null)
            destroySpeechRecognizer()
        }
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun queueClaudeCleanup(rawText: String) {
        viewModelScope.launch {
            val apiKey = prefs.anthropicApiKey.first()
            if (apiKey.isBlank()) return@launch
            val prompt = """Clean up the following voice transcription: fix punctuation, capitalisation, and obvious transcription errors. Return ONLY the cleaned text, nothing else.

"$rawText""""
            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You clean up voice transcriptions. Return only the cleaned text."
            ).onSuccess { cleaned ->
                val trimmed = cleaned.trim().removeSurrounding("\"")
                if (trimmed.isNotBlank() && trimmed != rawText) {
                    // Only apply if the text hasn't changed since we fired the request
                    _uiState.update { current ->
                        if (current.text == rawText) current.copy(text = trimmed) else current
                    }
                }
            }
        }
    }

    fun consumeError() = _uiState.update { it.copy(errorMessage = null) }

    fun consumeHint() = _uiState.update { it.copy(hintMessage = null) }

    override fun onCleared() {
        // onCleared() already runs on the main thread, and viewModelScope is
        // cancelled before it is called — so destroy directly, never via a coroutine.
        destroySpeechRecognizer()
        // viewModelScope is already cancelled, which releases the tap-to-talk microphone in
        // TapToTalkRecorder's finally; cancel explicitly too, in case that ever changes.
        tapToTalkJob?.cancel()
        super.onCleared()
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        val text = state.text.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(isSaving = false, errorMessage = "Nothing to save yet.") }
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isSaving = true, errorMessage = null) }
                // The insert and all post-save work live in SaveCapture, shared with the widget's
                // QuickAddActivity. The slow half runs on appScope there, because onComplete()
                // pops the back stack and that cancels viewModelScope.
                val result = SaveCapture.save(
                    getApplication<Application>(),
                    SaveCapture.Request(
                        isNote = state.captureType == CaptureType.NOTE,
                        text = text,
                        title = state.title,
                        selectedBucketId = state.selectedBucketId,
                        priority = state.selectedPriority,
                        dueDate = state.dueDate,
                        reminderAt = state.reminderAt,
                        recurrence = state.recurrence,
                        photoUris = state.pendingPhotoUris,
                        // Reuse the suggestion only when it was made for exactly this text.
                        reusableSuggestion = lastSuggestion?.takeIf { it.first == text }?.second
                    ),
                    buckets.value
                )
                when (result) {
                    is SaveCapture.Result.Saved -> {
                        _uiState.update { CaptureUiState() }
                        savedToBucket.tryEmit(result.bucketName)
                        onComplete()
                    }
                    SaveCapture.Result.NoBucket -> _uiState.update {
                        it.copy(errorMessage = "No bucket available to save into. Open the vault or create a bucket first.")
                    }
                    SaveCapture.Result.Blank -> _uiState.update {
                        it.copy(errorMessage = "Nothing to save yet.")
                    }
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    @Serializable
    private data class NoteTag(val bucket: String = "")
}
