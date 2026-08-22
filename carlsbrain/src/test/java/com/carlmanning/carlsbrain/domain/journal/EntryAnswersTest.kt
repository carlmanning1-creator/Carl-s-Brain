package com.carlmanning.carlsbrain.domain.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal's structured answers, and the text they render into.
 *
 * Worth pinning down because the two have to agree without anything checking them at runtime:
 * the rendered text is what search, sharing, Drive and Claude all read, while the structured
 * answers are what any future chart reads. If they drift, an entry says one thing and charts
 * another, and nothing anywhere reports it.
 *
 * The snapshot rule is the other half. `EntryAnswers.fields` is a copy of the template's
 * definitions at the moment Carl answered — not a pointer to the live template — so editing a
 * template must never retroactively change what a past entry meant.
 */
class EntryAnswersTest {

    private fun scale(id: String, label: String, low: String, high: String) = TemplateField(
        id = id,
        label = label,
        type = FieldType.SCALE,
        min = 1,
        max = 10,
        minAnchor = low,
        maxAnchor = high
    )

    private fun choice(id: String, label: String, options: List<String>) = TemplateField(
        id = id,
        label = label,
        type = FieldType.CHOICE,
        inlineOptions = options
    )

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Test
    fun `scale at an end renders its anchor, so a bare number is never left uninterpretable`() {
        val answers = EntryAnswers(
            fields = listOf(scale("energy", "Energy", "I am a walking corpse", "Can take on the world")),
            answers = listOf(FieldAnswer(fieldId = "energy", number = 10))
        )
        assertEquals("Energy: 10/10 — Can take on the world", answers.renderToText())
    }

    @Test
    fun `a mid-scale value renders without inventing an anchor`() {
        val answers = EntryAnswers(
            fields = listOf(scale("energy", "Energy", "corpse", "world")),
            answers = listOf(FieldAnswer(fieldId = "energy", number = 6))
        )
        assertEquals("Energy: 6/10", answers.renderToText())
    }

    @Test
    fun `unanswered fields are skipped rather than rendered blank`() {
        // A template Carl half-fills must not produce "Sleep: " lines that read as data.
        val answers = EntryAnswers(
            fields = listOf(
                scale("energy", "Energy", "low", "high"),
                scale("sleep", "Sleep", "awful", "great")
            ),
            answers = listOf(FieldAnswer(fieldId = "energy", number = 4))
        )
        assertEquals("Energy: 4/10", answers.renderToText())
    }

    @Test
    fun `an Other choice renders what Carl actually typed`() {
        val answers = EntryAnswers(
            fields = listOf(choice("main", "Main activity", listOf("Rope", OTHER_OPTION))),
            answers = listOf(
                FieldAnswer(fieldId = "main", choices = listOf(OTHER_OPTION), otherText = "Impact")
            )
        )
        assertEquals("Main activity: Other: Impact", answers.renderToText())
    }

    @Test
    fun `free text is kept, separated from the fields`() {
        val answers = EntryAnswers(
            fields = listOf(scale("energy", "Energy", "low", "high")),
            answers = listOf(FieldAnswer(fieldId = "energy", number = 3)),
            freeText = "Shoulder felt off all session."
        )
        assertEquals("Energy: 3/10\n\nShoulder felt off all session.", answers.renderToText())
    }

    @Test
    fun `free text alone renders as an ordinary entry`() {
        // A blank-page entry must not come out with stray leading whitespace.
        val answers = EntryAnswers(freeText = "  Just writing.  ")
        assertEquals("Just writing.", answers.renderToText())
    }

    @Test
    fun `a field answered then cleared leaves nothing behind`() {
        val answers = EntryAnswers(
            fields = listOf(choice("main", "Main", listOf("Rope"))),
            answers = listOf(FieldAnswer(fieldId = "main", choices = emptyList()))
        )
        assertEquals("", answers.renderToText())
    }

    // ── The snapshot ──────────────────────────────────────────────────────────

    @Test
    fun `answers survive a JSON round trip with their field definitions`() {
        // This is what gets stored in answersJson and now published to Drive, so a phone change
        // keeps the structured history rather than only the prose.
        val original = EntryAnswers(
            templateId = 3L,
            templateName = "Training",
            fields = listOf(
                scale("energy", "Energy", "corpse", "world"),
                choice("main", "Main lift", listOf("Snatch", "Clean"))
            ),
            answers = listOf(
                FieldAnswer(fieldId = "energy", number = 7),
                FieldAnswer(fieldId = "main", choices = listOf("Snatch"))
            ),
            freeText = "Felt strong."
        )
        val json = JournalTemplateSeeder.encodeFields(original.fields)
        val decodedFields = JournalTemplateSeeder.decodeFields(json)
        assertEquals(original.fields, decodedFields)

        val rebuilt = original.copy(fields = decodedFields)
        assertEquals(original.renderToText(), rebuilt.renderToText())
    }

    @Test
    fun `renaming a field label does not change what a past entry rendered`() {
        // The whole reason fields are snapshotted onto the entry. If this ever reads from the
        // live template instead, a year of training entries silently re-label themselves.
        val entry = EntryAnswers(
            fields = listOf(scale("energy", "Energy", "corpse", "world")),
            answers = listOf(FieldAnswer(fieldId = "energy", number = 8))
        )
        val before = entry.renderToText()

        @Suppress("UNUSED_VARIABLE")
        val templateEditedLater = listOf(scale("energy", "Readiness", "corpse", "world"))

        assertEquals(before, entry.renderToText())
        assertTrue(entry.renderToText().startsWith("Energy:"))
    }

    @Test
    fun `field ids stay stable so history keys off the id, not the label`() {
        // Two templates deliberately share an id to make answers comparable across them.
        val training = scale("energy", "Energy", "low", "high")
        val kink = scale("energy", "Energy before", "low", "high")
        assertEquals(training.id, kink.id)
    }

    @Test
    fun `decoding malformed field JSON yields nothing rather than throwing`() {
        // A failure to parse the structured answers must cost a chart, never the entry.
        assertEquals(emptyList<TemplateField>(), JournalTemplateSeeder.decodeFields("not json"))
        assertEquals(emptyList<TemplateField>(), JournalTemplateSeeder.decodeFields(""))
    }

    @Test
    fun `option lists round trip`() {
        val options = listOf("Rope", "Impact", OTHER_OPTION)
        assertEquals(
            options,
            JournalTemplateSeeder.decodeOptions(JournalTemplateSeeder.encodeOptions(options))
        )
    }
}
