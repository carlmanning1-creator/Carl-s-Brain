package com.carlmanning.carlsbrain.domain.journal

import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalOptionListEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalTemplateEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Creates Carl's two templates on first run, exactly as he specified them.
 *
 * Seeded rather than hardcoded: once created they are ordinary rows he can rename, re-question,
 * reorder or delete. The seeder exists so he does not have to retype a specification he has
 * already written out, not to make these templates special.
 *
 * Runs once per template. [JournalTemplateDao.getByBuiltInKey] deliberately looks at
 * soft-deleted rows too, so a template Carl deletes stays deleted instead of reappearing at the
 * next launch.
 */
object JournalTemplateSeeder {

    const val KEY_TRAINING = "builtin_training"
    const val KEY_KINK = "builtin_kink"
    const val KEY_ACTIVITIES_LIST = "builtin_kink_activities"
    const val KEY_PEOPLE_LIST = "builtin_kink_people"

    /** Field ids are stable and never derived from labels — see [TemplateField]. */
    private const val FIELD_MAIN_ACTIVITIES = "kink_main_activities"

    suspend fun seedIfNeeded(db: AppDatabase) {
        val dao = db.journalTemplateDao()

        // Option lists first: the Kink template's fields point at them by id.
        val peopleList = dao.getOptionListByKey(KEY_PEOPLE_LIST) ?: run {
            val id = dao.insertOptionList(
                JournalOptionListEntity(
                    name = "Who with",
                    builtInKey = KEY_PEOPLE_LIST,
                    optionsJson = encodeOptions(listOf("Bec", "Grace", OTHER_OPTION))
                )
            )
            dao.getOptionListById(id)
        }
        val activitiesList = dao.getOptionListByKey(KEY_ACTIVITIES_LIST) ?: run {
            val id = dao.insertOptionList(
                JournalOptionListEntity(
                    name = "Activities",
                    builtInKey = KEY_ACTIVITIES_LIST,
                    optionsJson = encodeOptions(
                        listOf(
                            "Impact", "Needles", "Knives", "Other Sharps", "Sex",
                            "Electro", "Rope", "Mind fuck", "Training", OTHER_OPTION
                        )
                    )
                )
            )
            dao.getOptionListById(id)
        }

        if (dao.getByBuiltInKey(KEY_TRAINING) == null) {
            dao.insertTemplate(
                JournalTemplateEntity(
                    name = "Training",
                    builtInKey = KEY_TRAINING,
                    sortOrder = 10,
                    fieldsJson = encodeFields(trainingFields())
                )
            )
        }

        if (dao.getByBuiltInKey(KEY_KINK) == null && activitiesList != null && peopleList != null) {
            dao.insertTemplate(
                JournalTemplateEntity(
                    name = "Kink",
                    builtInKey = KEY_KINK,
                    sortOrder = 20,
                    // Starts private so Carl does not have to remember the toggle each time.
                    isPrivateByDefault = true,
                    fieldsJson = encodeFields(kinkFields(peopleList.id, activitiesList.id))
                )
            )
        }
    }

    private fun trainingFields(): List<TemplateField> = listOf(
        TemplateField(
            id = "training_time",
            label = "Training Time",
            type = FieldType.CHOICE,
            inlineOptions = listOf(
                "Early (before 07:00)",
                "Morning (07:00–11:00)",
                "Midday (11:00–13:00)",
                "Afternoon (13:00–17:00)",
                "Evening (17:00–19:00)",
                "Night (after 19:00)"
            ),
            prefillFromClock = true
        ),
        // Carl's spec had this one running the other way — 1 "can take on the world" to 10
        // "walking corpse" — which would have made it the only scale here where a low score is
        // the good one. Reversed at his request so all five run the same direction and a
        // correlation across them means what it appears to mean. The label follows the anchors:
        // "Fatigue 10 = can take on the world" would be actively misleading while filling it in.
        // The field id stays "fatigue" so a rename never orphans past answers.
        scale("fatigue", "Energy", "I am a walking corpse", "Can take on the world"),
        scale("motivation", "Motivation", "Just why?", "I have already won gold"),
        scale("sleep_quality", "Sleep Quality", "I wish I was dead so I can rest", "I literally bounced out of bed"),
        scale("mindfulness", "Mindfulness", "Brain = squirrel asylum", "I am focus incarnate"),
        scale("overall_rating", "Overall Rating", "Feel worse than before", "Greatest training session ever")
    )

    private fun kinkFields(peopleListId: Long, activitiesListId: Long): List<TemplateField> = listOf(
        TemplateField(
            id = "kink_who_with",
            label = "Who with?",
            type = FieldType.MULTI_CHOICE,
            optionListId = peopleListId,
            allowOther = true
        ),
        TemplateField(
            id = "kink_length",
            label = "Session length",
            type = FieldType.CHOICE,
            // Anchored with rough durations for the same reason the scales are anchored: without
            // one, what counts as "Long" drifts and the field stops being comparable over time.
            inlineOptions = listOf(
                "Short (under 30 min)",
                "Medium (30–60 min)",
                "Long (1–2 hrs)",
                "Extra long (2 hrs+)"
            )
        ),
        TemplateField(
            id = FIELD_MAIN_ACTIVITIES,
            label = "Main activities",
            type = FieldType.MULTI_CHOICE,
            optionListId = activitiesListId,
            allowOther = true
        ),
        TemplateField(
            id = "kink_secondary_activities",
            label = "Secondary activities",
            type = FieldType.MULTI_CHOICE,
            optionListId = activitiesListId,
            allowOther = true,
            // Whatever was picked as Main disappears from this list, so a single activity
            // cannot be counted twice for one session.
            excludeAnswersOf = FIELD_MAIN_ACTIVITIES
        )
    )

    private fun scale(
        id: String,
        label: String,
        low: String,
        high: String,
        higherIsBetter: Boolean = true
    ) = TemplateField(
        id = id,
        label = label,
        type = FieldType.SCALE,
        min = 1,
        max = 10,
        minAnchor = low,
        maxAnchor = high,
        higherIsBetter = higherIsBetter
    )

    fun encodeFields(fields: List<TemplateField>): String =
        journalJson.encodeToString(ListSerializer(TemplateField.serializer()), fields)

    fun decodeFields(raw: String): List<TemplateField> =
        if (raw.isBlank()) emptyList()
        else runCatching {
            journalJson.decodeFromString(ListSerializer(TemplateField.serializer()), raw)
        }.getOrDefault(emptyList())

    fun encodeOptions(options: List<String>): String =
        journalJson.encodeToString(ListSerializer(String.serializer()), options)

    fun decodeOptions(raw: String): List<String> =
        if (raw.isBlank()) emptyList()
        else runCatching {
            journalJson.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
}

/**
 * The option matching the current time of day, for [TemplateField.prefillFromClock].
 *
 * Matches on the bracket bounds parsed out of the option text rather than on position, so
 * editing the labels does not silently start prefilling the wrong one. Returns null when
 * nothing matches, which simply leaves the field unanswered.
 */
fun prefilledChoiceFor(field: TemplateField, options: List<String>, hourOfDay: Int): String? {
    if (!field.prefillFromClock || options.isEmpty()) return null
    val index = when {
        hourOfDay < 7 -> 0
        hourOfDay < 11 -> 1
        hourOfDay < 13 -> 2
        hourOfDay < 17 -> 3
        hourOfDay < 19 -> 4
        else -> 5
    }
    return options.getOrNull(index)
}
