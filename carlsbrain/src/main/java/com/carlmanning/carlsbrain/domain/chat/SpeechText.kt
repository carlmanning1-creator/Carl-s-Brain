package com.carlmanning.carlsbrain.domain.chat

/**
 * Turns a written reply into something worth hearing.
 *
 * Markdown is written to be read. Anything left in reaches the TTS engine as literal
 * characters, which is why Carl kept hearing "dash dash" partway through a sentence — an em
 * dash, or a `--`, spoken aloud.
 *
 * Shared by Chat and the voice service. The voice prompt tells Claude not to use markdown at
 * all, but an instruction is not a guarantee, and the one time it slips is the time Carl is
 * driving and cannot look at the screen.
 */
object SpeechText {

    /**
     * @return [text] with markup removed and dashes turned into pauses, ready to speak.
     *   Blank if there was nothing but markup, which callers should treat as "say nothing".
     */
    fun forSpeaking(text: String): String = text
        // Fenced code first, or the inline rules below half-strip its contents.
        .replace(Regex("```[\\s\\S]*?```"), "code block")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        // Underscore emphasis — used by the loop-limit note the app writes itself.
        .replace(Regex("_(.+?)_"), "$1")
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        // A horizontal rule is silence on the page and "dash dash dash" out loud.
        .replace(Regex("^\\s*([-*_])\\s*\\1\\s*\\1[-*_\\s]*$", RegexOption.MULTILINE), "")
        // Bullet and numbered markers: the pause carries the list, the character does not.
        .replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
        // Em and en dashes are read as the word "dash". A comma gives the same pause silently.
        .replace(Regex("\\s*[—–]\\s*"), ", ")
        // A double hyphen used as a dash, which the engine reads out twice over.
        .replace(Regex("(?<=\\S)\\s*--\\s*(?=\\S)"), ", ")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
