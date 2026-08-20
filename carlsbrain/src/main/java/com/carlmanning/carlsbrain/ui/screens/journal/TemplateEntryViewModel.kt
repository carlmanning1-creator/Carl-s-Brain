package com.carlmanning.carlsbrain.ui.screens.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.domain.journal.EntryAnswers
import com.carlmanning.carlsbrain.domain.journal.FieldAnswer
import com.carlmanning.carlsbrain.domain.journal.FieldType
import com.carlmanning.carlsbrain.domain.journal.JournalTemplateSeeder
import com.carlmanning.carlsbrain.domain.journal.OTHER_OPTION
import com.carlmanning.carlsbrain.domain.journal.TemplateField
import com.carlmanning.carlsbrain.domain.journal.journalJson
import com.carlmanning.carlsbrain.domain.journal.prefilledChoiceFor
import com.carlmanning.carlsbrain.domain.journal.renderToText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class TemplateEntryUiState(
    val isLoading: Boolean = true,
    val entryId: Long? = null,
    val templateId: Long? = null,
    val templateName: String = "",
    val fields: List<TemplateField> = emptyList(),
    /** Resolved option lists per field id — shared lists already dereferenced. */
    val options: Map<String, List<String>> = emptyMap(),
    val answers: Map<String, FieldAnswer> = emptyMap(),
    val freeText: String = "",
    val isPrivate: Boolean = false,
    val isEditingSaved: Boolean = false,
    val saved: Boolean = false
) {
    /** Nothing entered at all — used to decide whether a draft is worth keeping. */
    val isEmpty: Boolean
        get() = freeText.isBlank() && answers.values.all { it.isBlank() }
}

/**
 * Fills in one templated journal entry.
 *
 * Handles three cases through the same state: a new entry from a template, an unfinished draft
 * being continued, and a saved entry being edited. They differ only in what is loaded and what
 * `isDraft` ends up as, so keeping them in one place avoids three near-identical screens
 * drifting apart.
 */
class TemplateEntryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val dao = db.journalDao()
    private val templateDao = db.journalTemplateDao()

    private val _uiState = MutableStateFlow(TemplateEntryUiState())
    val uiState: StateFlow<TemplateEntryUiState> = _uiState.asStateFlow()

    private var loaded = false

    /**
     * Set the moment this screen hands its content off, so it cannot do so twice.
     *
     * Backing out runs saveDraftIfNeeded and then navigates, which clears the ViewModel and
     * runs onCleared — which would call it again. With a new entry that is two inserts and two
     * rows in the journal for one draft.
     */
    private var handedOff = false

    fun load(templateId: Long?, entryId: Long?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            // Editing a saved entry, or continuing a draft, both start from an existing row.
            val existing = entryId?.let { dao.getEntryById(it) }
                ?: templateId?.let { dao.getDraftForTemplate(it) }

            val effectiveTemplateId = existing?.templateId ?: templateId
            val template = effectiveTemplateId?.let { templateDao.getTemplateById(it) }
            val fields = template?.let { JournalTemplateSeeder.decodeFields(it.fieldsJson) }
                ?: emptyList()

            // Shared option lists are dereferenced once here, so the UI never has to know that
            // two fields are looking at the same list.
            val optionsByField = mutableMapOf<String, List<String>>()
            for (field in fields) {
                val opts = if (field.optionListId != 0L) {
                    templateDao.getOptionListById(field.optionListId)
                        ?.let { JournalTemplateSeeder.decodeOptions(it.optionsJson) }
                        ?: emptyList()
                } else {
                    field.inlineOptions
                }
                optionsByField[field.id] = opts
            }

            val stored = existing?.answersJson
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching { journalJson.decodeFromString(EntryAnswers.serializer(), it) }
                        .getOrNull()
                }

            val answers = stored?.answers?.associateBy { it.fieldId }?.toMutableMap()
                ?: mutableMapOf()

            // Prefill the time-of-day bracket only on a genuinely new entry — reopening a draft
            // written last night must not quietly rewrite it to this morning.
            if (existing == null) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                for (field in fields) {
                    val prefill = prefilledChoiceFor(field, optionsByField[field.id].orEmpty(), hour)
                    if (prefill != null) {
                        answers[field.id] = FieldAnswer(field.id, choices = listOf(prefill))
                    }
                }
            }

            _uiState.value = TemplateEntryUiState(
                isLoading = false,
                entryId = existing?.id,
                templateId = effectiveTemplateId,
                templateName = template?.name ?: stored?.templateName.orEmpty(),
                // The entry's own snapshot wins over the live template, so editing a past entry
                // shows the questions it was actually answered against.
                fields = stored?.fields?.takeIf { it.isNotEmpty() } ?: fields,
                options = optionsByField,
                answers = answers,
                freeText = stored?.freeText ?: existing?.content.orEmpty(),
                isPrivate = existing?.isPrivate ?: (template?.isPrivateByDefault ?: false),
                isEditingSaved = existing != null && !existing.isDraft
            )
        }
    }

    // ── Editing ───────────────────────────────────────────────────────────────

    fun setScale(fieldId: String, value: Int) = update(fieldId) { it.copy(number = value) }

    /** Tapping the selected number again clears it — there is no other way to unset a scale. */
    fun toggleScale(fieldId: String, value: Int) = update(fieldId) {
        if (it.number == value) it.copy(number = null) else it.copy(number = value)
    }

    fun setSingleChoice(fieldId: String, option: String) = update(fieldId) {
        if (it.choices.firstOrNull() == option) it.copy(choices = emptyList(), otherText = "")
        else it.copy(choices = listOf(option), otherText = if (option == OTHER_OPTION) it.otherText else "")
    }

    fun toggleMultiChoice(fieldId: String, option: String) = update(fieldId) {
        val next = if (option in it.choices) it.choices - option else it.choices + option
        it.copy(
            choices = next,
            // Dropping Other should not leave its text behind to reappear later.
            otherText = if (OTHER_OPTION in next) it.otherText else ""
        )
    }

    fun setOtherText(fieldId: String, text: String) = update(fieldId) { it.copy(otherText = text) }

    fun setText(fieldId: String, text: String) = update(fieldId) { it.copy(text = text) }

    fun setFreeText(text: String) = _uiState.update { it.copy(freeText = text) }

    fun setPrivate(isPrivate: Boolean) = _uiState.update { it.copy(isPrivate = isPrivate) }

    private fun update(fieldId: String, transform: (FieldAnswer) -> FieldAnswer) {
        _uiState.update { state ->
            val current = state.answers[fieldId] ?: FieldAnswer(fieldId)
            state.copy(answers = state.answers + (fieldId to transform(current)))
        }
    }

    // ── Saving ────────────────────────────────────────────────────────────────

    fun save() {
        val state = _uiState.value
        if (state.isEmpty || handedOff) return
        handedOff = true
        _uiState.update { it.copy(saved = true) }
        persist(state, asDraft = false)
    }

    /**
     * Keeps an unfinished entry so it can be picked up later.
     *
     * Called when the screen goes away without a save. Silent by design: a dialog asking "save
     * draft?" every time Carl backs out is precisely the friction the app exists to remove.
     * An entry with nothing in it is discarded rather than kept as an empty draft.
     */
    fun saveDraftIfNeeded() {
        val state = _uiState.value
        if (state.isLoading || handedOff) return
        // Editing an entry that is already saved is different: there is no draft to make, and
        // silently throwing the edit away because Carl used back rather than Save would lose
        // work. It is the same row either way, so writing it back cannot duplicate anything.
        if (state.isEditingSaved) {
            if (!state.isEmpty) {
                handedOff = true
                persist(state, asDraft = false)
            }
            return
        }
        handedOff = true
        if (state.isEmpty) {
            // Nothing left in it — clear any draft row this screen started with.
            state.entryId?.let { id ->
                CarlsBrainApp.appScope.launch { dao.softDeleteEntry(id) }
            }
            return
        }
        persist(state, asDraft = true)
    }

    private fun persist(state: TemplateEntryUiState, asDraft: Boolean) {
        val answers = EntryAnswers(
            templateId = state.templateId ?: 0L,
            templateName = state.templateName,
            fields = state.fields,
            answers = state.fields.mapNotNull { state.answers[it.id] }.filterNot { it.isBlank() },
            freeText = state.freeText.trim()
        )
        // The rendered text is what search, sharing, the Drive markdown and Claude all read, so
        // every one of them keeps working without knowing templates exist.
        val content = answers.renderToText()
        val json = journalJson.encodeToString(EntryAnswers.serializer(), answers)

        // appScope: saving must not be cancelled by the navigation that triggered it.
        CarlsBrainApp.appScope.launch {
            val existing = state.entryId?.let { dao.getEntryById(it) }
            if (existing != null) {
                dao.updateEntry(
                    existing.copy(
                        content = content,
                        answersJson = json,
                        templateId = state.templateId,
                        isPrivate = state.isPrivate,
                        isDraft = asDraft,
                        updatedAt = System.currentTimeMillis(),
                        isSynced = false
                    )
                )
            } else {
                dao.insertEntry(
                    JournalEntryEntity(
                        content = content,
                        prompt = state.templateName,
                        answersJson = json,
                        templateId = state.templateId,
                        isPrivate = state.isPrivate,
                        isDraft = asDraft
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Covers leaving by any route — back, a tab change, or the process being trimmed.
        saveDraftIfNeeded()
    }
}

/** Convenience for the screen: does this field type render a list of options? */
fun TemplateField.usesOptions(): Boolean =
    type == FieldType.CHOICE || type == FieldType.MULTI_CHOICE
