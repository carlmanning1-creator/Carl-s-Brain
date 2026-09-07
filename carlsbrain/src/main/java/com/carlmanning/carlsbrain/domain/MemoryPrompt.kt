package com.carlmanning.carlsbrain.domain

/**
 * How much of `memory.md` goes into a Claude prompt.
 *
 * ## Why there is a limit at all
 *
 * `memory.md` only ever grows. `MemoryLearner` appends to it after captures, notes, calendar
 * events and voice conversations, and nothing has ever removed a line — by design, because it is
 * Carl's file and the app rewriting it unasked is not something it should do. But the whole file
 * is prepended to *every* Claude call on both clients, so its length is a tax on every briefing,
 * every auto-tag, every chat message, forever. Left alone it gets slower and dearer every month,
 * and the oldest facts — which are the least likely to still matter — cost exactly as much as
 * today's.
 *
 * ## What this does, and deliberately does not do
 *
 * It trims what is **sent**. The file itself is untouched: still complete, still editable in
 * Settings, still exported whole. Nothing is destroyed, and turning the cap up tomorrow restores
 * everything immediately — which is the whole reason for capping the prompt rather than
 * compacting the file.
 *
 * The tail is kept rather than the head, because appended facts are the newest ones. When
 * anything is dropped, the prompt says so in a line of its own: a model told it has partial
 * context can say "I don't have that" instead of confidently asserting Carl has no such
 * commitment.
 *
 * The cut lands on a line boundary, so a fact is never half-quoted into a prompt.
 */
object MemoryPrompt {

    /**
     * Characters of memory kept for a prompt.
     *
     * Roughly two thousand tokens — comfortably more than Carl's file holds today, so this is a
     * ceiling that does nothing until it is needed, rather than a trim he would notice now.
     */
    const val MAX_CHARS = 8_000

    private const val TRUNCATION_NOTE =
        "[Older entries in Carl's memory file are not included here. " +
            "If something seems missing, say so rather than assuming it does not exist.]"

    /**
     * @return [memory] unchanged when it fits, or its most recent [MAX_CHARS] with a note saying
     *   that earlier entries were left out.
     */
    fun forPrompt(memory: String): String {
        if (memory.length <= MAX_CHARS) return memory
        val tail = memory.takeLast(MAX_CHARS)
        // Start at a line boundary so a fact is never quoted half-way through.
        val whole = tail.substringAfter('\n', tail)
        return "$TRUNCATION_NOTE\n\n$whole"
    }
}
