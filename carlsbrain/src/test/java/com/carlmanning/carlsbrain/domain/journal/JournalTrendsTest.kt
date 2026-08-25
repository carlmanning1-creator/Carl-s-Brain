package com.carlmanning.carlsbrain.domain.journal

import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building chartable series out of stored journal answers.
 *
 * The failure modes here are all silent — a chart that is subtly wrong looks exactly like a
 * chart that is right, and Carl would act on it. Two in particular:
 *
 *  - **A reversed scale read upside-down.** `higherIsBetter` exists precisely so a falling line
 *    on a reversed scale is not reported as bad news. Nothing else in the app has ever exercised
 *    it, so these tests are the first thing that does.
 *  - **A renamed field re-labelling its own history.** Entries snapshot their field definitions
 *    for exactly this reason; a series must key off the id, never the label.
 */
class JournalTrendsTest {

    private val day = 24L * 60 * 60 * 1000

    private fun scale(
        id: String,
        label: String,
        higherIsBetter: Boolean = true
    ) = TemplateField(
        id = id,
        label = label,
        type = FieldType.SCALE,
        min = 1,
        max = 10,
        minAnchor = "low",
        maxAnchor = "high",
        higherIsBetter = higherIsBetter
    )

    private fun entry(
        id: Long,
        atMs: Long,
        templateId: Long = 1L,
        templateName: String = "Training",
        fields: List<TemplateField>,
        values: Map<String, Int>
    ): JournalEntryEntity {
        val answers = EntryAnswers(
            templateId = templateId,
            templateName = templateName,
            fields = fields,
            answers = values.map { (fieldId, n) -> FieldAnswer(fieldId = fieldId, number = n) }
        )
        return JournalEntryEntity(
            id = id,
            content = answers.renderToText(),
            createdAt = atMs,
            answersJson = journalJson.encodeToString(EntryAnswers.serializer(), answers)
        )
    }

    @Test
    fun `a scale answered twice becomes a series in time order`() {
        val f = listOf(scale("energy", "Energy"))
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(2, 2_000, fields = f, values = mapOf("energy" to 8)),
                entry(1, 1_000, fields = f, values = mapOf("energy" to 4))
            )
        )
        assertEquals(1, series.size)
        assertEquals(listOf(4, 8), series[0].points.map { it.value })
        assertEquals(6.0, series[0].average, 0.001)
    }

    @Test
    fun `a single answer is not a trend and is left out`() {
        val f = listOf(scale("energy", "Energy"))
        val series = JournalTrends.seriesFrom(
            listOf(entry(1, 1_000, fields = f, values = mapOf("energy" to 7)))
        )
        assertTrue(series.isEmpty())
    }

    @Test
    fun `a field shared across templates is one series, not two`() {
        // The whole reason field ids are stable and deliberately shared: this is what makes
        // "training scores against sleep" a single chart.
        val training = listOf(scale("energy", "Energy"))
        val kink = listOf(scale("energy", "Energy before"))
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, 1_000, templateId = 1, templateName = "Training", fields = training, values = mapOf("energy" to 5)),
                entry(2, 2_000, templateId = 2, templateName = "Kink", fields = kink, values = mapOf("energy" to 9))
            )
        )
        assertEquals(1, series.size)
        assertEquals(listOf(5, 9), series[0].points.map { it.value })
    }

    @Test
    fun `filtering by template narrows to that template's entries`() {
        val training = listOf(scale("energy", "Energy"))
        val kink = listOf(scale("energy", "Energy before"))
        val entries = listOf(
            entry(1, 1_000, templateId = 1, fields = training, values = mapOf("energy" to 5)),
            entry(2, 2_000, templateId = 1, fields = training, values = mapOf("energy" to 7)),
            entry(3, 3_000, templateId = 2, templateName = "Kink", fields = kink, values = mapOf("energy" to 9))
        )
        val onlyTraining = JournalTrends.seriesFrom(entries, templateId = 1L)
        assertEquals(listOf(5, 7), onlyTraining[0].points.map { it.value })
    }

    @Test
    fun `renaming a field keeps its history and takes the newest label`() {
        // Points come from each entry's own snapshot, so nothing is re-interpreted. The label
        // shown is the current wording, because that is the language Carl uses now.
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, 1_000, fields = listOf(scale("energy", "Energy")), values = mapOf("energy" to 4)),
                entry(2, 2_000, fields = listOf(scale("energy", "Readiness")), values = mapOf("energy" to 6))
            )
        )
        assertEquals(1, series.size)
        assertEquals("Readiness", series[0].label)
        assertEquals(listOf(4, 6), series[0].points.map { it.value })
    }

    @Test
    fun `on a reversed scale, going down is an improvement`() {
        // The one that would be silently wrong without higherIsBetter: on a "how sore are you"
        // scale a falling line is good news, and must not be reported as a decline.
        val f = listOf(scale("soreness", "Soreness", higherIsBetter = false))
        val now = 100L * day
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, now - 60 * day, fields = f, values = mapOf("soreness" to 8)),
                entry(2, now - 55 * day, fields = f, values = mapOf("soreness" to 8)),
                entry(3, now - 50 * day, fields = f, values = mapOf("soreness" to 8)),
                entry(4, now - 5 * day, fields = f, values = mapOf("soreness" to 3)),
                entry(5, now - 4 * day, fields = f, values = mapOf("soreness" to 3)),
                entry(6, now - 3 * day, fields = f, values = mapOf("soreness" to 3))
            )
        )
        val change = series[0].changeOverLast(30, now)!!
        assertTrue("falling soreness must report as improvement, got $change", change > 0)

        // Best on a reversed scale is the LOWEST value.
        assertEquals(3, series[0].best)
        assertEquals(8, series[0].worst)
    }

    @Test
    fun `too few points either side of the window means no trend is claimed`() {
        val f = listOf(scale("energy", "Energy"))
        val now = 100L * day
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, now - 60 * day, fields = f, values = mapOf("energy" to 4)),
                entry(2, now - 2 * day, fields = f, values = mapOf("energy" to 9))
            )
        )
        assertNull(series[0].changeOverLast(30, now))
    }

    @Test
    fun `an out-of-range value is dropped rather than distorting the axis`() {
        val f = listOf(scale("energy", "Energy"))
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, 1_000, fields = f, values = mapOf("energy" to 5)),
                entry(2, 2_000, fields = f, values = mapOf("energy" to 7)),
                entry(3, 3_000, fields = f, values = mapOf("energy" to 99))
            )
        )
        assertEquals(listOf(5, 7), series[0].points.map { it.value })
    }

    @Test
    fun `malformed answers cost a point, never a crash`() {
        val f = listOf(scale("energy", "Energy"))
        val broken = JournalEntryEntity(id = 9, createdAt = 1_500, answersJson = "not json")
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, 1_000, fields = f, values = mapOf("energy" to 5)),
                broken,
                entry(2, 2_000, fields = f, values = mapOf("energy" to 7))
            )
        )
        assertEquals(listOf(5, 7), series[0].points.map { it.value })
    }

    @Test
    fun `non-scale fields are not charted`() {
        val fields = listOf(
            scale("energy", "Energy"),
            TemplateField(id = "main", label = "Main lift", type = FieldType.CHOICE)
        )
        val series = JournalTrends.seriesFrom(
            listOf(
                entry(1, 1_000, fields = fields, values = mapOf("energy" to 5)),
                entry(2, 2_000, fields = fields, values = mapOf("energy" to 7))
            )
        )
        assertEquals(listOf("energy"), series.map { it.fieldId })
    }

    @Test
    fun `templatesWithData lists only templates that produced numbers`() {
        val scaleFields = listOf(scale("energy", "Energy"))
        val choiceOnly = listOf(TemplateField(id = "main", label = "Main", type = FieldType.CHOICE))
        val found = JournalTrends.templatesWithData(
            listOf(
                entry(1, 1_000, templateId = 1, templateName = "Training", fields = scaleFields, values = mapOf("energy" to 5)),
                entry(2, 2_000, templateId = 2, templateName = "Choices", fields = choiceOnly, values = emptyMap())
            )
        )
        assertEquals(listOf(1L to "Training"), found)
    }
}
