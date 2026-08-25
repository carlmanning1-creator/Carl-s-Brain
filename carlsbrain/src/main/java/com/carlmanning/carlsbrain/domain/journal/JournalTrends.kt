package com.carlmanning.carlsbrain.domain.journal

import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity

/**
 * Turns the structured answers stored on journal entries into something chartable.
 *
 * This is what `answersJson` was built for. Since v2.8 every templated entry has carried its
 * numeric answers *and* a snapshot of the field definitions they were given against, and until
 * now nothing has read them.
 *
 * ## Why the entry's own snapshot is the authority
 *
 * A series is built from the fields recorded **on each entry**, never from the live template.
 * That is the whole point of snapshotting: Carl can rename "Energy" to "Readiness", or change
 * a scale's anchors, without a year of past entries silently re-labelling themselves. The
 * label shown is the most recent one, because that is the language he uses now, but every
 * point keeps the meaning it was recorded with.
 *
 * ## Why field id, not label
 *
 * Series are keyed by field id, which is stable and independent of the label. Two templates
 * deliberately share an id where the answers are meant to be comparable — that is what makes
 * "training scores against sleep" a single chart rather than two.
 *
 * Everything here is pure and offline. No Claude call, no network: the numbers are already on
 * the phone, and a chart that costs money per look would not get looked at.
 */
object JournalTrends {

    /** One recorded answer, in time order. */
    data class Point(val atMs: Long, val value: Int, val entryId: Long)

    /**
     * Every answer Carl has given to one scale field.
     *
     * @param min the low end of the scale, and [max] the high end. Taken from the most recent
     *   entry, so a chart's axis matches how the question is asked today.
     * @param higherIsBetter false for a reversed scale. Nothing here inverts anything — it is
     *   carried so that a chart can colour "good" correctly rather than assuming up is up.
     */
    data class Series(
        val fieldId: String,
        val label: String,
        val min: Int,
        val max: Int,
        val minAnchor: String,
        val maxAnchor: String,
        val higherIsBetter: Boolean,
        val points: List<Point>
    ) {
        val average: Double get() = if (points.isEmpty()) 0.0 else points.sumOf { it.value } / points.size.toDouble()
        val best: Int? get() = if (higherIsBetter) points.maxOfOrNull { it.value } else points.minOfOrNull { it.value }
        val worst: Int? get() = if (higherIsBetter) points.minOfOrNull { it.value } else points.maxOfOrNull { it.value }

        /**
         * Recent average minus the average before it, in scale units.
         *
         * Positive always means "better", reversed scales included — otherwise a falling line
         * on a reversed scale would read as bad news when it is the opposite. Null when there
         * is not enough on one side of the split to compare honestly.
         */
        fun changeOverLast(days: Int, nowMs: Long = System.currentTimeMillis()): Double? {
            val cutoff = nowMs - days * 24L * 60 * 60 * 1000
            val recent = points.filter { it.atMs >= cutoff }
            val earlier = points.filter { it.atMs < cutoff }
            if (recent.size < MIN_POINTS_FOR_TREND || earlier.size < MIN_POINTS_FOR_TREND) return null
            val delta = recent.map { it.value }.average() - earlier.map { it.value }.average()
            return if (higherIsBetter) delta else -delta
        }
    }

    /** Below this a "trend" is one good session and one bad one, which means nothing. */
    const val MIN_POINTS_FOR_TREND = 3

    /**
     * Builds every scale series present in [entries].
     *
     * @param entries the entries to read, in any order. **Callers must pass a vault-filtered,
     *   non-draft, non-deleted list** — this does no filtering of its own, because the filtering
     *   that matters belongs in SQL where a screen cannot forget it.
     * @param templateId when set, only entries from that template. Null charts everything,
     *   which is how a field shared between two templates becomes one series.
     */
    fun seriesFrom(
        entries: List<JournalEntryEntity>,
        templateId: Long? = null
    ): List<Series> {
        // Oldest first: points are read in order, and the last one seen defines the labels.
        val ordered = entries.sortedBy { it.createdAt }

        // Insertion-ordered so series appear in the order the template asks its questions,
        // which is the order Carl thinks about them in.
        val building = LinkedHashMap<String, MutableSeries>()

        for (entry in ordered) {
            if (entry.answersJson.isBlank()) continue
            val answers = runCatching {
                journalJson.decodeFromString(EntryAnswers.serializer(), entry.answersJson)
            }.getOrNull() ?: continue          // A chart is never worth losing an entry over.
            if (templateId != null && answers.templateId != templateId) continue

            for (field in answers.fields) {
                if (field.type != FieldType.SCALE) continue
                val value = answers.answerFor(field.id)?.number ?: continue
                // Out-of-range values would distort the axis. They should not exist, but an
                // edited template or a hand-written file could produce one.
                if (value < field.min || value > field.max) continue

                val series = building.getOrPut(field.id) { MutableSeries(field.id) }
                // Overwritten by each later entry, so the newest wording wins — the labels Carl
                // uses now, over points that keep the meaning they were recorded with.
                series.label = field.label
                series.min = field.min
                series.max = field.max
                series.minAnchor = field.minAnchor
                series.maxAnchor = field.maxAnchor
                series.higherIsBetter = field.higherIsBetter
                series.points += Point(entry.createdAt, value, entry.id)
            }
        }

        return building.values
            // A single point is a dot, not a trend. Two is the minimum worth drawing a line
            // between; below that the screen says so rather than showing an empty chart.
            .filter { it.points.size >= 2 }
            .map {
                Series(
                    fieldId = it.fieldId,
                    label = it.label,
                    min = it.min,
                    max = it.max,
                    minAnchor = it.minAnchor,
                    maxAnchor = it.maxAnchor,
                    higherIsBetter = it.higherIsBetter,
                    points = it.points
                )
            }
    }

    /** Templates that have produced at least one chartable answer, newest use first. */
    fun templatesWithData(entries: List<JournalEntryEntity>): List<Pair<Long, String>> {
        val seen = LinkedHashMap<Long, String>()
        entries.sortedByDescending { it.createdAt }.forEach { entry ->
            if (entry.answersJson.isBlank()) return@forEach
            val answers = runCatching {
                journalJson.decodeFromString(EntryAnswers.serializer(), entry.answersJson)
            }.getOrNull() ?: return@forEach
            if (answers.templateId == 0L || answers.templateName.isBlank()) return@forEach
            if (answers.fields.none { it.type == FieldType.SCALE }) return@forEach
            seen.putIfAbsent(answers.templateId, answers.templateName)
        }
        return seen.map { it.key to it.value }
    }

    private class MutableSeries(val fieldId: String) {
        var label: String = ""
        var min: Int = 1
        var max: Int = 10
        var minAnchor: String = ""
        var maxAnchor: String = ""
        var higherIsBetter: Boolean = true
        val points = mutableListOf<Point>()
    }
}
