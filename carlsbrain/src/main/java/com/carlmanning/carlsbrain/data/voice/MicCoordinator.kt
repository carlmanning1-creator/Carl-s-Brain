package com.carlmanning.carlsbrain.data.voice

import android.os.Handler
import android.os.Looper
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.data.local.worker.AmbientState
import com.carlmanning.carlsbrain.data.local.worker.MeetingRecordingService
import com.carlmanning.carlsbrain.data.local.worker.MeetingServiceState
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one place that answers "who may hold the microphone right now".
 *
 * Android gives the on-screen app's own capture priority over every other client, including the
 * speech recogniser — which runs in Google's process. So while a background listener of ours
 * (the wake word, the ambient buffer) held an AudioRecord and a Carl's Brain screen was open,
 * Chat, two-way voice and the keyboard's own dictation all received silence. The app cannot know
 * when the keyboard microphone is pressed, so the rule is blunt: **no passive listener holds the
 * microphone while the app is on screen.** Both stand down on show and re-arm on hide.
 *
 * What this is not: it does not replace the STOP/RESUME messages the services send each other
 * around a recording. Those stay. This owns the visibility rule, the shared "is something
 * recording" answer, and the record of what happened.
 *
 * Nothing here arms anything. Re-arming always goes back through each service's own check of
 * its own setting.
 */
object MicCoordinator {

    /** True while any Carl's Brain activity is started. */
    @Volatile var appVisible = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var startedActivities = 0

    /**
     * Leaving is debounced: a rotation or one activity handing over to another stops one screen
     * just before starting the next, and re-opening the microphone for that instant helps nobody.
     */
    private const val REARM_DELAY_MS = 500L

    /** Main thread — called from the Application's activity callbacks. */
    fun onActivityStarted() {
        startedActivities++
        setVisible(true)
    }

    /** Main thread. */
    fun onActivityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            main.postDelayed({ if (startedActivities == 0) setVisible(false) }, REARM_DELAY_MS)
        }
    }

    private fun setVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        note("app", if (visible) "on screen — passive listeners stand down" else "off screen — re-arm")
        // Each is a no-op when its service is not running; neither call starts one.
        AmbientBufferService.onAppVisibilityChanged()
        VoiceCaptureService.onAppVisibilityChanged()
    }

    /** A meeting, however it was started, owns the microphone outright. */
    fun recordingActive(): Boolean =
        AmbientBufferService.state.value is AmbientState.Recording ||
            MeetingRecordingService.state.value is MeetingServiceState.Recording

    // ── Record of events ──────────────────────────────────────────────────────

    private const val MAX_EVENTS = 40
    private val events = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Routine acquire/release. Kept in memory only: the on-device error log is capped at 64 KB
     * and trims from the front, so writing every one of these there would evict the crash
     * reports it exists to keep. They reach the log attached to a [fail], which is when they
     * are wanted.
     */
    fun note(owner: String, event: String) {
        synchronized(events) {
            events.addLast("${stamp.format(Date())} $owner: $event")
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** A microphone failure or refusal — written to Diagnostics with the recent history. */
    fun fail(owner: String, detail: String) {
        note(owner, "FAILED — $detail")
        val history = synchronized(events) { events.toList() }.takeLast(12).joinToString("\n")
        ErrorLog.record("Mic/$owner", "$detail\nRecent microphone events:\n$history")
    }
}
