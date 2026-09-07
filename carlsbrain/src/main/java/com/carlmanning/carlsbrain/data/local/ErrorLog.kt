package com.carlmanning.carlsbrain.data.local

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A crash and error log Carl can read, copy and paste, without a cable.
 *
 * ## Why this exists
 *
 * The navigation-off-the-main-thread crash took two days to find, and the only thing standing
 * between the symptom ("the app just closes") and the cause was a stack trace that lived on the
 * device and needed a laptop and `adb` to retrieve. Anything that runs on a background thread —
 * every editor save, every sync, every service — fails with no screen watching it, so the app
 * vanishing was the *entire* diagnostic signal available.
 *
 * ## The constraints that shape it
 *
 * **Writing happens while the process is dying.** The uncaught-exception path gets one chance,
 * so it writes synchronously with plain file I/O: no coroutines, no WorkManager, nothing that
 * needs a scheduler which is about to stop existing.
 *
 * **It must never itself crash.** A logger that throws inside a crash handler replaces a
 * diagnosable failure with an undiagnosable one, so every operation is wrapped.
 *
 * **The system handler still runs.** [install] chains to whatever was there before, so Android
 * still shows its dialog and still reports the crash. This observes; it does not swallow.
 *
 * ## What it deliberately does not record
 *
 * No note, journal or to-do content. Entries are the exception type, its message and the stack.
 * Carl pastes these into a chat, so the contents leave the phone — a log that quietly carried a
 * vault note's text into a conversation would be a leak with his own hand on the button.
 * Exception messages can still name a file or a title, which is a judgement call rather than a
 * guarantee; nothing here adds content on purpose.
 */
object ErrorLog {

    private const val DIR = "diagnostics"
    private const val FILE = "errors.log"

    /**
     * Roughly a dozen stack traces. Big enough that a crash loop does not evict the first
     * failure — usually the informative one — and small enough to paste into a chat.
     */
    private const val MAX_BYTES = 64 * 1024

    /**
     * `DateTimeFormatter`, not `SimpleDateFormat`.
     *
     * [record] is documented as safe from any thread, and SimpleDateFormat is not — concurrent
     * use throws, and the throw escaped through the string template into the caller. Inside an
     * uncaught-exception handler that turns a diagnosable failure into an undiagnosable one,
     * which is the single thing this class must never do. DateTimeFormatter is immutable and
     * thread-safe.
     */
    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.UK)
            .withZone(ZoneId.systemDefault())

    /** Never throws: a clock or formatter failure must not cost us the stack trace below it. */
    private fun now(): String =
        runCatching { stamp.format(Instant.now()) }.getOrDefault("(time unavailable)")

    @Volatile
    private var appContext: Context? = null

    /**
     * Starts capturing. Call once from `Application.onCreate`, as early as possible — a crash
     * during startup is exactly the one with no other way to see it.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Recorded first: the chained handler almost certainly ends the process, so anything
            // after it may never run.
            runCatching { record("FATAL on ${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records a throwable with its stack. Safe to call from any thread, including a dying one. */
    fun record(tag: String, throwable: Throwable) {
        val trace = runCatching {
            StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        }.getOrDefault("${throwable::class.java.name}: ${throwable.message}")
        append("${now()}  $tag\n$trace")
    }

    /** Records a plain message, for a failure that is not an exception. */
    fun record(tag: String, message: String) {
        append("${now()}  $tag\n$message")
    }

    /** The whole log, oldest first, or a note saying it is empty. */
    fun read(): String = runCatching {
        val file = logFile() ?: return "Diagnostics not available."
        if (!file.exists() || file.length() == 0L) "No errors recorded." else file.readText()
    }.getOrElse { "Could not read the log: ${it.message}" }

    fun clear() {
        runCatching { logFile()?.delete() }
    }

    fun hasEntries(): Boolean = runCatching {
        logFile()?.let { it.exists() && it.length() > 0L } ?: false
    }.getOrDefault(false)

    private fun append(entry: String) {
        runCatching {
            val file = logFile() ?: return
            file.appendText("$entry\n\n")
            trimIfNeeded(file)
        }
    }

    /**
     * Keeps the newest [MAX_BYTES], dropping whole entries from the front.
     *
     * Trimmed after the write rather than before, so a crash that arrives mid-trim still has its
     * trace on disk — losing the oldest entry is survivable, losing the newest is the point.
     */
    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        runCatching {
            val kept = file.readText()
                .takeLast(MAX_BYTES)
                // Start at an entry boundary so the log never opens mid-stack-trace.
                .substringAfter("\n\n", "")
            file.writeText("[older entries trimmed]\n\n$kept")
        }
    }

    private fun logFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }
        return File(dir, FILE)
    }
}
