package com.carlmanning.carlsbrain.data.local

import android.content.Context
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Cleans up after Fireflies, which was removed in v2.28. Each half runs once per install.
 *
 * Nothing here deletes a meeting or a recording. A meeting that was waiting on Fireflies is
 * handed back to the Whisper ladder if its audio is still here, and otherwise parked with the
 * reason in its summary — the same place every other failed meeting says why.
 */
object FirefliesRetirement {

    /** The status pre-2.28 builds left a meeting in while Fireflies owned it. Nothing sets it now. */
    private const val LEGACY_STATUS = "FIREFLIES_PROCESSING"

    // The markers the old sync worker wrapped its memory.md section in. Matched on the markers,
    // never the heading text, because anything outside them is Carl's own writing.
    private const val AUTO_START = "<!-- carlsbrain:auto:fireflies:start -->"
    private const val AUTO_END = "<!-- carlsbrain:auto:fireflies:end -->"

    suspend fun run(ctx: Context) {
        val prefs = CarlsBrainApp.userPreferences
        if (!prefs.firefliesRetired.first()) {
            runCatching {
                rerouteStuckMeetings(ctx)
                prefs.clearRemovedFirefliesKey()
                prefs.markFirefliesRetired()
            }.onFailure { ErrorLog.record("FirefliesRetirement/meetings", it) }
        }
        if (!prefs.firefliesMemoryRemoved.first()) {
            // mutate returns false when Drive could not be read or the write lost a race. The
            // flag stays unset in that case, so the next launch tries again — a bad signal in
            // Dubbo must not leave the section in every prompt for good.
            val ok = runCatching {
                MemoryLearner.mutate(ctx.applicationContext) { current -> removeAutoSection(current) }
            }.onFailure { ErrorLog.record("FirefliesRetirement/memory", it) }.getOrDefault(false)
            if (ok) prefs.markFirefliesMemoryRemoved()
        }
    }

    /**
     * Local audio → PROCESSING with no transcript, which is exactly the shape
     * MeetingViewModel's recovery sweep picks up and sends down the Whisper → Claude ladder.
     * Audio only on Drive → ERROR with a blank transcript, whose screen offers "Transcribe from
     * audio" and fetches it back. Neither → ERROR. Either way the reason is in the summary.
     */
    private suspend fun rerouteStuckMeetings(ctx: Context) {
        val dao = AppDatabase.getInstance(ctx).meetingDao()
        for (m in dao.getMeetingsWithStatus(LEGACY_STATUS)) {
            val now = System.currentTimeMillis()
            // The placeholder title was "Awaiting Fireflies transcription…", which would now be
            // a lie. Blank on the Whisper path lets the analysis title it; the parked ones get a
            // plain name, since nothing else will.
            val base = m.copy(title = "Untranscribed recording", updatedAt = now)
            val local = File(m.localAudioPath)
            val updated = when {
                m.localAudioPath.isNotBlank() && local.exists() && local.length() > 0 ->
                    base.copy(title = "", status = "PROCESSING", transcript = "")
                // ERROR rather than AUDIO_ONLY: the AUDIO_ONLY screen shows neither the summary
                // nor "Transcribe from audio", and this one needs both.
                m.driveAudioFileId.isNotBlank() ->
                    base.copy(
                        status = "ERROR",
                        summary = "Was waiting on Fireflies, which has been removed. The audio " +
                            "is still on Drive — tap Transcribe from audio to send it to Whisper."
                    )
                else ->
                    base.copy(
                        status = "ERROR",
                        summary = "Was waiting on Fireflies, which has been removed, and the " +
                            "audio is no longer on this phone."
                    )
            }
            dao.updateMeeting(updated)
        }
    }

    /** Returns [current] with the marked Fireflies section removed, or unchanged if absent. */
    internal fun removeAutoSection(current: String): String {
        val start = current.indexOf(AUTO_START)
        if (start < 0) return current
        val end = current.indexOf(AUTO_END, start)
        if (end < 0) return current
        val before = current.substring(0, start).trimEnd('\n')
        val after = current.substring(end + AUTO_END.length).trimStart('\n')
        val rebuilt = listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n\n")
        // A blank memory.md reads as absent to MemoryLearner.mutate, which would seed it.
        // Leaving the section in place is the safe direction.
        if (rebuilt.isBlank()) return current
        return rebuilt + "\n"
    }
}
