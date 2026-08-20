package com.carlmanning.carlsbrain.ui.screens.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalOptionListEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalTemplateEntity
import com.carlmanning.carlsbrain.domain.journal.FieldType
import com.carlmanning.carlsbrain.domain.journal.JournalTemplateSeeder
import com.carlmanning.carlsbrain.domain.journal.JournalReminderScheduler
import com.carlmanning.carlsbrain.domain.journal.TemplateField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The template currently open in the editor, held apart from the saved row until saved. */
data class TemplateDraft(
    val id: Long = 0L,
    val name: String = "",
    val isPrivateByDefault: Boolean = false,
    val fields: List<TemplateField> = emptyList(),
    val builtInKey: String = "",
    val bucketId: Long? = null,
    val reminderRule: String = ""
)

class TemplateManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val dao = db.journalTemplateDao()

    val templates: StateFlow<List<JournalTemplateEntity>> = dao.getTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val optionLists: StateFlow<List<JournalOptionListEntity>> = dao.getOptionLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editing = MutableStateFlow<TemplateDraft?>(null)
    val editing: StateFlow<TemplateDraft?> = _editing.asStateFlow()

    fun newTemplate() {
        _editing.value = TemplateDraft()
    }

    fun editTemplate(template: JournalTemplateEntity) {
        _editing.value = TemplateDraft(
            id = template.id,
            name = template.name,
            isPrivateByDefault = template.isPrivateByDefault,
            fields = JournalTemplateSeeder.decodeFields(template.fieldsJson),
            builtInKey = template.builtInKey,
            bucketId = template.bucketId,
            reminderRule = template.reminderRule
        )
    }

    fun cancelEdit() { _editing.value = null }

    fun setName(name: String) = _editing.update { it?.copy(name = name) }

    fun setPrivateByDefault(value: Boolean) =
        _editing.update { it?.copy(isPrivateByDefault = value) }

    fun setTemplateBucket(bucketId: Long?) = _editing.update { it?.copy(bucketId = bucketId) }

    fun setReminderRule(rule: String) = _editing.update { it?.copy(reminderRule = rule) }

    val buckets: StateFlow<List<com.carlmanning.carlsbrain.data.local.entity.BucketEntity>> =
        db.bucketDao().getAllBuckets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Adds a field with a freshly minted id.
     *
     * The id is a UUID rather than anything derived from the label, so renaming the field later
     * keeps every answer already recorded against it. That is the whole reason answers are
     * keyed by id in the first place.
     */
    fun addField(type: FieldType) {
        _editing.update { draft ->
            draft ?: return@update null
            val field = TemplateField(
                id = "f_${UUID.randomUUID().toString().take(8)}",
                label = "",
                type = type,
                min = 1,
                max = 10
            )
            draft.copy(fields = draft.fields + field)
        }
    }

    fun updateField(index: Int, transform: (TemplateField) -> TemplateField) {
        _editing.update { draft ->
            draft ?: return@update null
            val fields = draft.fields.toMutableList()
            if (index !in fields.indices) return@update draft
            fields[index] = transform(fields[index])
            draft.copy(fields = fields)
        }
    }

    fun removeField(index: Int) {
        _editing.update { draft ->
            draft ?: return@update null
            val fields = draft.fields.toMutableList()
            if (index !in fields.indices) return@update draft
            fields.removeAt(index)
            draft.copy(fields = fields)
        }
    }

    fun moveField(index: Int, delta: Int) {
        _editing.update { draft ->
            draft ?: return@update null
            val fields = draft.fields.toMutableList()
            val target = index + delta
            if (index !in fields.indices || target !in fields.indices) return@update draft
            val moved = fields.removeAt(index)
            fields.add(target, moved)
            draft.copy(fields = fields)
        }
    }

    fun saveTemplate() {
        val draft = _editing.value ?: return
        if (draft.name.isBlank()) return
        viewModelScope.launch {
            val fieldsJson = JournalTemplateSeeder.encodeFields(
                // A field with no label is one Carl added and never filled in; keeping it would
                // put a nameless question on every entry from this template.
                draft.fields.filter { it.label.isNotBlank() }
            )
            if (draft.id == 0L) {
                dao.insertTemplate(
                    JournalTemplateEntity(
                        name = draft.name.trim(),
                        fieldsJson = fieldsJson,
                        isPrivateByDefault = draft.isPrivateByDefault,
                        bucketId = draft.bucketId,
                        reminderRule = draft.reminderRule,
                        sortOrder = (templates.value.maxOfOrNull { it.sortOrder } ?: 0) + 10
                    )
                )
            } else {
                val existing = dao.getTemplateById(draft.id) ?: return@launch
                dao.updateTemplate(
                    existing.copy(
                        name = draft.name.trim(),
                        fieldsJson = fieldsJson,
                        isPrivateByDefault = draft.isPrivateByDefault,
                        bucketId = draft.bucketId,
                        reminderRule = draft.reminderRule,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            _editing.value = null
            // Rules changed, so the alarms have to be rebuilt — AlarmManager knows nothing
            // about the database changing underneath it.
            JournalReminderScheduler.rescheduleAll(getApplication(), db)
        }
    }

    /**
     * Soft-deletes a template.
     *
     * Entries written from it are untouched: each carries its own snapshot of the fields, so a
     * year of training entries still renders correctly after the template is gone.
     */
    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            dao.softDeleteTemplate(id)
            // Without this the weekly alarm outlives the template and keeps nagging Carl to
            // fill in something that no longer exists. rescheduleAll cancels deleted ones.
            JournalReminderScheduler.rescheduleAll(getApplication(), db)
        }
    }

    // ── Option lists ──────────────────────────────────────────────────────────

    /**
     * Rewrites a shared option list.
     *
     * Every field pointing at it updates at once — which is exactly why Main and Secondary
     * Activities share one list rather than holding a copy each. Entries already saved keep the
     * option text they recorded, so removing "Rope" here never rewrites history.
     */
    fun saveOptionList(id: Long, name: String, options: List<String>) {
        viewModelScope.launch {
            val cleaned = options.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (id == 0L) {
                dao.insertOptionList(
                    JournalOptionListEntity(
                        name = name.trim(),
                        optionsJson = JournalTemplateSeeder.encodeOptions(cleaned)
                    )
                )
            } else {
                val existing = dao.getOptionListById(id) ?: return@launch
                dao.updateOptionList(
                    existing.copy(
                        name = name.trim(),
                        optionsJson = JournalTemplateSeeder.encodeOptions(cleaned),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun optionsOf(list: JournalOptionListEntity): List<String> =
        JournalTemplateSeeder.decodeOptions(list.optionsJson)

    fun fieldsOf(template: JournalTemplateEntity): List<TemplateField> =
        JournalTemplateSeeder.decodeFields(template.fieldsJson)
}
