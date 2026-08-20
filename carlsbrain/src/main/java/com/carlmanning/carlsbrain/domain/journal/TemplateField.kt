package com.carlmanning.carlsbrain.domain.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a template field is answered, and therefore how it renders and what it is worth later. */
enum class FieldType {
    /** A number on a fixed range, tapped from a row of buttons. Interrogable. */
    SCALE,

    /** One option from a list. Interrogable — this is what "group by" needs. */
    CHOICE,

    /** Any number of options from a list. Interrogable as frequencies. */
    MULTI_CHOICE,

    /** One line of free text. Searchable, not interrogable. */
    TEXT,

    /** A paragraph. Searchable, not interrogable. */
    LONG_TEXT
}

/**
 * One question in a journal template.
 *
 * ## Why [id] is not the label
 * Answers are stored against [id], so renaming "Overall Rating" to "Session rating" keeps a
 * year of scores attached rather than orphaning them. It also lets two templates deliberately
 * share a field — give "how I'm feeling" the same id in both and the values are comparable
 * across them.
 *
 * ## Why the anchors live here
 * A scale is only worth recording if its ends mean something fixed. "Fatigue: 7" is noise
 * unless 1 and 10 are pinned down, and a year later nobody remembers what was meant. The
 * anchors are stored with the field, and copied onto each entry, so an old score still carries
 * its own definition.
 */
@Serializable
data class TemplateField(
    val id: String,
    val label: String,
    val type: FieldType,

    // ── SCALE ────────────────────────────────────────────────────────────────
    val min: Int = 1,
    val max: Int = 10,
    /** What [min] means, e.g. "Can take on the world". Shown beside the low end. */
    val minAnchor: String = "",
    /** What [max] means, e.g. "I am a walking corpse". Shown beside the high end. */
    val maxAnchor: String = "",
    /**
     * Whether a higher number is the better outcome.
     *
     * Not cosmetic. Carl's Fatigue scale runs 1 = "can take on the world" to 10 = "walking
     * corpse", the opposite way round to Motivation, Sleep, Mindfulness and Overall Rating.
     * Anything that later averages or correlates these — a chart, or Claude spotting a pattern
     * — would read Fatigue upside-down and confidently report the reverse of the truth. This
     * flag is what stops that.
     */
    val higherIsBetter: Boolean = true,

    // ── CHOICE / MULTI_CHOICE ────────────────────────────────────────────────
    /**
     * Id of a shared [JournalOptionList], or 0 to use [inlineOptions].
     *
     * Shared because Carl's Secondary Activities must offer the same list as Main Activities
     * and stay in step when he edits it. Two copies would drift the first time one was updated.
     */
    val optionListId: Long = 0L,
    val inlineOptions: List<String> = emptyList(),
    /**
     * Whether picking the literal option "Other" reveals a box to say what.
     *
     * Keeps the list short without six months of entries that only say "Other". A value typed
     * here is stored as the answer, so a recurring one becomes visible and can be promoted to
     * a real option.
     */
    val allowOther: Boolean = false,
    /**
     * Hide options already chosen for the field with this id.
     *
     * Secondary Activities uses it to drop whatever was picked as Main, so one activity cannot
     * be counted twice for a single session — which would quietly skew any later frequency
     * count.
     */
    val excludeAnswersOf: String = "",

    /** Preselect the option matching the current time bracket. Only meaningful for CHOICE. */
    val prefillFromClock: Boolean = false,

    val required: Boolean = false
)

/**
 * A named, reusable list of options.
 *
 * Its own entity rather than a list on each field, because Carl edits these — "an option in
 * journal or template settings to edit the options lists" — and Main and Secondary Activities
 * have to move together when he does.
 */
@Serializable
data class OptionListDto(
    val id: Long,
    val name: String,
    val options: List<String>
)

/** One answer. Only one of the value fields is meaningful, per [FieldType]. */
@Serializable
data class FieldAnswer(
    val fieldId: String,
    val number: Int? = null,
    val text: String = "",
    val choices: List<String> = emptyList(),
    /** What Carl typed after choosing "Other", if anything. */
    val otherText: String = ""
) {
    fun isBlank(): Boolean =
        number == null && text.isBlank() && choices.isEmpty() && otherText.isBlank()
}

/**
 * Everything an entry stores about its structured answers.
 *
 * [fields] is a **snapshot** of the template's definitions at the moment it was answered, not a
 * reference to the live template. Carl will edit his templates — rename a label, change a
 * scale's range, remove an option — and without the snapshot every past entry would silently
 * re-render against the new definition and stop meaning what it meant. This is the same
 * principle the journal already applies by storing the prompt with the entry.
 */
@Serializable
data class EntryAnswers(
    val templateId: Long = 0L,
    val templateName: String = "",
    val fields: List<TemplateField> = emptyList(),
    val answers: List<FieldAnswer> = emptyList(),
    /**
     * The free-text box every template carries underneath its fields.
     *
     * Held here rather than only inside the rendered content, because an entry must stay
     * editable after saving: reopening it needs the prose back on its own, not tangled up with
     * the rendered field lines.
     */
    val freeText: String = ""
) {
    fun answerFor(fieldId: String): FieldAnswer? = answers.find { it.fieldId == fieldId }
}

/** Lenient on purpose: an entry written by a newer build must still open on an older one. */
val journalJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Renders answers into readable text.
 *
 * The rendered text is stored as the entry's `content`, which is what makes everything already
 * built keep working: search matches it, sharing sends it, the Drive `.md` contains it, and
 * Claude reads it. The structured answers sit alongside for charts and questions like "how do
 * my training scores track against my sleep" — but nothing depends on them yet, so a failure
 * to parse them costs a chart, never the entry itself.
 */
fun EntryAnswers.renderToText(): String = buildString {
    for (field in fields) {
        val answer = answerFor(field.id) ?: continue
        if (answer.isBlank()) continue
        val value = when (field.type) {
            FieldType.SCALE -> {
                val n = answer.number ?: continue
                val anchor = when (n) {
                    field.min -> field.minAnchor
                    field.max -> field.maxAnchor
                    else -> ""
                }
                if (anchor.isBlank()) "$n/${field.max}" else "$n/${field.max} — $anchor"
            }
            FieldType.CHOICE, FieldType.MULTI_CHOICE -> {
                val picked = answer.choices.map { choice ->
                    if (choice == OTHER_OPTION && answer.otherText.isNotBlank()) {
                        "Other: ${answer.otherText}"
                    } else choice
                }
                picked.joinToString(", ")
            }
            FieldType.TEXT, FieldType.LONG_TEXT -> answer.text
        }
        if (value.isBlank()) continue
        appendLine("${field.label}: $value")
    }
    if (freeText.isNotBlank()) {
        if (isNotEmpty()) appendLine()
        append(freeText.trim())
    }
}.trim()

/** The literal option that triggers [TemplateField.allowOther]. */
const val OTHER_OPTION = "Other"
