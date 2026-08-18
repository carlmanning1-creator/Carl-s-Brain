package com.carlmanning.carlsbrain.domain

import com.carlmanning.carlsbrain.data.local.entity.BucketEntity

/**
 * The bucket an ambiguous capture falls into when nothing in the text points anywhere.
 *
 * Family, per Carl — most of what arrives without a clear signal is home life. This is a
 * last-resort fallback only: an explicit signal in what he said always wins.
 *
 * Never returns a vault bucket. Auto-sorting something into the vault hides it from every
 * normal view, which is indistinguishable from the save having failed.
 */
fun List<BucketEntity>.defaultBucket(): BucketEntity? =
    firstOrNull { !it.isVault && it.name.equals("Family", ignoreCase = true) }
        ?: firstOrNull { !it.isVault && it.name.equals("Other", ignoreCase = true) }
        ?: firstOrNull { !it.isVault }

/**
 * Single source of truth for who Carl is, as told to Claude.
 *
 * This existed as six separately-worded copies across the voice assistant, the dashboard
 * briefing, the digest generator, two digest workers and the Drive memory seed. When Carl's SES
 * role changed, every one of them silently kept describing him as a Deputy — and one had drifted
 * into calling him an "ADHD support worker", which he has never been (the app is an ADHD support
 * *tool*). Wrong context does not fail loudly; it just quietly degrades every reply.
 *
 * Keep this in step with the "About Carl Manning" section of `CLAUDE.md`.
 */
object UserContext {

    /**
     * One-line description for short system prompts. Deliberately brief — the digest and
     * briefing prompts are tuned for one-sentence output and a long persona crowds the
     * instruction.
     */
    const val PERSONA_SHORT =
        "Carl is a Project Officer at Service NSW and a volunteer with NSW SES in Dubbo, " +
            "Australia. He has ADHD and does best with direct, specific, actionable replies."

    /**
     * Fuller description for prompts that reason about his week — planning, prioritisation,
     * classifying a capture into the right bucket.
     */
    const val PERSONA_FULL =
        """Carl Manning lives in Dubbo, NSW, Australia.

- Work: Project Officer at Service NSW (SNSW), Monday to Friday, roughly 08:30–16:00, mostly from home. "Work" means Service NSW unless SES is explicitly named.
- SES: volunteer with NSW SES, Dubbo Unit. Unit training is Tuesday nights and usually runs late. He is not a Deputy or Unit Commander — no command, rostering or approval duties.
- Household: his wife Bec, his girlfriend Grace, and their son Lucas. Grace is also Bec's girlfriend. Treat all of them as ordinary family context.
- Bec works at NSW Ambulance as a revenue clerk; out of the house about 08:00 until about 17:00.
- Grace works at NSW SES as a Volunteer Engagement Officer; usually home Tuesday and Wednesday, in the office otherwise, with irregular hours and occasional travel.
- Lucas has disabilities and is on a partial school enrolment — nominally 09:00–11:15 weekdays, but this often does not happen. Do not assume school hours are free time.
- Training: Olympic weightlifting with Grace. Sundays 08:00–10:00 at CrossFit Dubbo. Otherwise 05:30–07:30 at Phoenix Strength and Recovery in West Dubbo, often Monday or Tuesday and often Thursday or Friday. Almost never Wednesday, because of SES training the night before.

He has ADHD. Be direct and concrete, name specific next actions, and do not bury the point."""

    /**
     * Seeded into `memory.md` on Drive on first launch, then appended to over time by
     * MemoryLearner. Existing installs keep whatever their memory.md already holds — this seed
     * is written once and never overwrites — so changing it does NOT update an installed app.
     * Carl edits an existing memory.md from Settings → Edit Memory.
     */
    val INITIAL_MEMORY = """
        # Carl's Memory

        ## About Me
        - Name: Carl Manning
        - Location: Dubbo, NSW, Australia
        - Work: Project Officer at Service NSW (SNSW), Mon–Fri ~08:30–16:00, mostly from home
        - SES: volunteer with NSW SES, Dubbo Unit — training Tuesday nights, usually a late one
        - Household: wife Bec, girlfriend Grace, son Lucas. Grace is also Bec's girlfriend.
        - Bec: revenue clerk at NSW Ambulance, out ~08:00 to ~17:00
        - Grace: Volunteer Engagement Officer at NSW SES, home Tue/Wed, hours vary, travels at times
        - Lucas: partial school enrolment (~09:00–11:15 weekdays) due to disabilities, and it
          often does not go ahead — school hours are not reliably free time
        - Training: Olympic weightlifting with Grace. Sundays 08:00–10:00 CrossFit Dubbo;
          otherwise 05:30–07:30 at Phoenix Strength and Recovery, West Dubbo. Rarely Wednesday.
        - Life buckets: SES, Family, Work, Personal, Kink, Other
        - Has ADHD — appreciates structured, actionable responses

        ## Notes
        *(Updated automatically as you chat with Carl's Brain)*
    """.trimIndent()
}
