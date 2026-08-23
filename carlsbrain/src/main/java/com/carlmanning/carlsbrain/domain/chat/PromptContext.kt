package com.carlmanning.carlsbrain.domain.chat

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pieces every Claude prompt in the app needs, in one place so Chat and the voice service
 * cannot drift apart.
 *
 * Both surfaces answer the same questions from the same person and can both create calendar
 * events; a fact that is true in one and absent in the other produces the kind of difference
 * Carl notices and cannot explain.
 */
object PromptContext {

    /**
     * The current date, time and zone.
     *
     * The model has no clock. Without this it guesses — a "good morning" on a Monday came back
     * describing Sunday — and it guesses silently, which is worse than refusing. It matters
     * beyond greetings: every `[CALENDAR:]` marker turns a relative phrase ("tomorrow at two")
     * into an absolute timestamp, and "overdue" means nothing without today's date.
     *
     * Read from the device rather than hardcoded to Australia/Sydney, so it stays correct if
     * Carl travels — and so a phone whose zone is wrong shows an obviously wrong answer rather
     * than a subtly wrong one.
     */
    fun rightNow(zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = LocalDateTime.now(zone)
            .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH))
        return """
            ## Right now
            It is $stamp in ${zone.id}.
            Use this for anything relative — today, tomorrow, this week, overdue, and for every
            date you put in a marker. Never guess the date or the day of the week, and never
            infer the time of day from how Carl greets you.
        """.trimIndent()
    }

    /**
     * Speech-to-text renders the app's name as a person's name more often than not.
     *
     * Deliberately handled here rather than by rewriting the transcript. Substituting "Brain"
     * for every "Brian" would corrupt a genuine one — "call Brian about the depot" is a
     * perfectly ordinary capture, and silently rewriting Carl's own words is exactly the sort
     * of damage this app must never do. Telling the model instead costs nothing and is
     * reversible; it can use the sentence to tell the two apart, which a regex cannot.
     */
    val MISHEARD_NAME = """
        ## A quirk of the microphone
        Carl talks to you by voice, and speech-to-text usually writes the app's name — Brain —
        as "Brian". If he greets or addresses "Brian", he is addressing you. Do not treat it as
        a person and do not correct him. If the sentence is genuinely about a person called
        Brian — someone to ring, meet or follow up — read it as the person it plainly is.
    """.trimIndent()

    /**
     * How long an answer should be.
     *
     * Carl has ADHD; the standing instruction in this project is that when he asks what to do,
     * you name one thing, because a ranked list of eight is the problem rather than the answer.
     * The same logic applies to prose: length is a cost he pays, not a courtesy he receives.
     */
    val BREVITY = """
        ## How to answer
        Carl has ADHD. Length is a cost, not a courtesy.

        - Lead with the answer. No preamble, no restating the question, no summary at the end.
        - Two or three sentences is usually right. A short list is fine when the answer really
          is a list; prose padding around it is not.
        - When he asks what to do, name ONE thing.
        - Do not offer further help, and do not end with a question unless you actually need an
          answer to proceed.
        - Go longer only if he asks for detail, or the subject genuinely cannot be said briefly.
    """.trimIndent()
}
