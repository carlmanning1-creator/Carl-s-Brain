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

/**
 * Silently learns from every piece of content Carl adds to Carl's Brain.
 * Extracts facts worth remembering and appends them to memory.md on Drive.
 *
 * All work is fire-and-forget — callers never await results and the UI is
 * never blocked or notified.
 */
object MemoryLearner {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
            systemPrompt = "You maintain Carl's permanent memory file. Be selective — only capture genuinely important, durable facts. Never repeat existing facts. Return only bullet lines or an empty string.",
            model = ClaudeClient.HAIKU
        ).onSuccess { response ->
            val trimmed = response.trim()
            if (trimmed.isBlank()) return@onSuccess

            // Validate format: at least one line starting with "- ["
            val validLines = trimmed.lines()
                .map { it.trim() }
                .filter { it.startsWith("- [") }
            if (validLines.isEmpty()) return@onSuccess

            val toAppend = validLines.joinToString("\n")
            val updated = memory + "\n" + toAppend

            // Update cache first so subsequent calls within TTL see the new facts
            cachedMemory = updated
            cacheTimestampMs = System.currentTimeMillis()

            // Persist to Drive
            val drive = DriveRepository(appCtx)
            drive.updateMemoryMd(updated)
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

    /** Invalidate the in-memory cache (e.g. after ChatViewModel writes its own update). */
    fun invalidateCache() {
        cachedMemory = null
        cacheTimestampMs = 0L
    }
}
