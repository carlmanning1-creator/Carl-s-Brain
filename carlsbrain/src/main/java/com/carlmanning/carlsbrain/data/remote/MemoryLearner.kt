package com.carlmanning.carlsbrain.data.remote

import android.content.Context
import com.carlmanning.carlsbrain.CarlsBrainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Silently learns from every piece of content Carl adds to Carl's Brain.
 * Extracts facts worth remembering and appends them to memory.md on Drive.
 *
 * All work is fire-and-forget — callers never await results and the UI is
 * never blocked or notified.
 */
object MemoryLearner {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Serialises read-modify-write on memory.md. See [appendToMemory]. */
    private val writeMutex = Mutex()

    // In-memory cache so we don't hammer Drive on every capture
    private var cachedMemory: String? = null
    private var cacheTimestampMs: Long = 0L
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * Fire-and-forget: learn from [context] in the background.
     *
     * @param appContext  Android application context (use getApplication() from a ViewModel)
     * @param context     Plain-English description of what was just created/updated,
     *                    e.g. "Todo created: Call mum — due tomorrow, bucket: Family, priority: High"
     * @param source      Label for logging, e.g. "todo", "note", "voice", "chat"
     */
    fun learnFrom(appContext: Context, context: String, source: String) {
        val appCtx = appContext.applicationContext
        scope.launch {
            runCatching { doLearn(appCtx, context, source) }
        }
    }

    private suspend fun doLearn(appCtx: Context, context: String, source: String) {
        val claude = CarlsBrainApp.claudeClient
        val prefs = CarlsBrainApp.userPreferences

        // Bail out silently if no API key
        if (prefs.anthropicApiKey.first().isBlank()) return

        // Get memory (from cache or Drive)
        val memory = getMemory(appCtx) ?: return
        val memoryTail = memory.takeLast(500)

        val prompt = """A new item was just added to Carl's second brain app (source: $source):

"$context"

Current memory tail (last 500 chars):
...${memoryTail}

Extract any facts worth permanently remembering about Carl's life, people, routines, and recurring commitments.
Also capture explicit preferences about how the AI generates content (e.g. "Carl prefers short to-do titles", "Carl wants reminders 30 mins before events", "Carl uses military time").
Capture recurring patterns (e.g. "Carl often captures SES training todos on Thursdays").

Rules:
- Do NOT repeat facts already clearly stated in the memory tail above.
- Return ONLY new bullet(s) in format: - [YYYY-MM-DD] Fact
- Use today's date: ${dateFormat.format(Date())}
- If nothing is worth capturing, return an empty string — nothing else."""

        claude.chat(
            messages = listOf(ApiMessage("user", prompt)),
            systemPrompt = "You maintain Carl's permanent memory file. Default to capturing — Carl has ADHD and relies heavily on this memory. If there is any reasonable chance he would want this remembered, include it. Err on the side of saving. Never repeat facts already in the memory tail. Return only bullet lines or an empty string.",
            model = ClaudeClient.HAIKU
        ).onSuccess { response ->
            val trimmed = response.trim()
            if (trimmed.isBlank()) return@onSuccess

            // Validate format: at least one line starting with "- ["
            val validLines = trimmed.lines()
                .map { it.trim() }
                .filter { it.startsWith("- [") }
            if (validLines.isEmpty()) return@onSuccess

            appendToMemory(appCtx, validLines.joinToString("\n"))
        }
    }

    /**
     * Appends [toAppend] to memory.md, against a **fresh** read rather than the cache.
     *
     * The cache is fine for building the prompt — a slightly stale tail only risks re-learning a
     * fact — but it is not safe to write from. Appending to a cached copy discards anything
     * written since it was taken, which for this file means an edit made on the web app in the
     * last five minutes simply vanished, and Carl had no way to know.
     *
     * Serialised on [writeMutex] so two captures landing together cannot each read, append and
     * write, with the second erasing the first.
     */
    private suspend fun appendToMemory(appCtx: Context, toAppend: String) {
        if (toAppend.isBlank()) return
        writeMutex.withLock {
            val drive = DriveRepository(appCtx)
            val current = drive.getMemoryMd() ?: DriveRepository.INITIAL_MEMORY
            val updated = current.trimEnd() + "\n" + toAppend
            if (drive.updateMemoryMd(updated)) {
                cachedMemory = updated
                cacheTimestampMs = System.currentTimeMillis()
            } else {
                // The write failed, so the cache would be a lie. Drop it and re-read next time.
                cachedMemory = null
            }
        }
    }

    /** Returns current memory.md content from cache or Drive, or null on failure. */
    private suspend fun getMemory(appCtx: Context): String? {
        val now = System.currentTimeMillis()
        if (cachedMemory != null && (now - cacheTimestampMs) < CACHE_TTL_MS) {
            return cachedMemory
        }

        val drive = DriveRepository(appCtx)
        val fetched = drive.getMemoryMd() ?: DriveRepository.INITIAL_MEMORY
        cachedMemory = fetched
        cacheTimestampMs = System.currentTimeMillis()
        return fetched
    }

    /**
     * Force-save [context] to memory without the selective evaluator.
     * Use when Carl explicitly says "remember this" — always saves at least one bullet.
     */
    fun forceLearnFrom(appContext: Context, context: String) {
        val appCtx = appContext.applicationContext
        scope.launch {
            runCatching { doForceLearn(appCtx, context) }
        }
    }

    private suspend fun doForceLearn(appCtx: Context, context: String) {
        val claude = CarlsBrainApp.claudeClient
        val prefs = CarlsBrainApp.userPreferences
        if (prefs.anthropicApiKey.first().isBlank()) return

        val memory = getMemory(appCtx) ?: return
        val date = dateFormat.format(Date())

        val prompt = """Carl explicitly asked to remember this:

"$context"

Current memory tail: ...${memory.takeLast(400)}

Summarise the key facts or context Carl wants retained as 1-3 concise bullets.
Format every line as: - [$date] Fact
Always return at least one bullet — this is an explicit save request, never return empty."""

        claude.chat(
            messages = listOf(ApiMessage("user", prompt)),
            systemPrompt = "You maintain Carl's memory file. Carl has explicitly asked to save this — always return bullet lines, never empty or NONE.",
            model = ClaudeClient.HAIKU
        ).onSuccess { response ->
            val trimmed = response.trim()
            if (trimmed.isBlank()) return@onSuccess
            val validLines = trimmed.lines().map { it.trim() }.filter { it.startsWith("- [") }
            val toAppend = if (validLines.isNotEmpty()) validLines.joinToString("\n")
                          else "- [$date] ${trimmed.take(200)}"
            appendToMemory(appCtx, toAppend)
        }
    }

    /** Invalidate the in-memory cache (e.g. after ChatViewModel writes its own update). */
    fun invalidateCache() {
        cachedMemory = null
        cacheTimestampMs = 0L
    }
}
