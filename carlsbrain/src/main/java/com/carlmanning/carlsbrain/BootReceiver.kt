package com.carlmanning.carlsbrain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.data.local.worker.DigestAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.MicRestart
import com.carlmanning.carlsbrain.data.local.worker.RearmRemindersWorker
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Everything below runs off the main thread, so onReceive returns immediately — and
        // once it returns the process is an ordinary candidate for termination, at exactly the
        // moment a booting device is under most memory pressure. Reaped here, the per-to-do
        // reminders are simply gone: nothing outside this receiver ever rebuilds them, so they
        // stay dead until Carl notices a reminder never arrived. The lease keeps the process
        // alive until the work is done, as every other receiver in the app already does.
        val pending = goAsync()
        // Every step is guarded on its own, and a failure is recorded rather than thrown.
        //
        // This ran on a bare scope with no exception handler, so one throw — most plausibly a
        // SecurityException from an exact alarm whose permission has been revoked — reached the
        // default handler, killed the process on every boot, and took every *later* step with
        // it. The reminders after the failure point were then simply gone: nothing outside this
        // receiver ever rebuilds them, so they stayed dead until Carl noticed one never arrived.
        // Rearming is a list of independent jobs, and it should behave like one.
        val job = CarlsBrainApp.appScope.launch {
            val prefs = CarlsBrainApp.userPreferences

            // Cancel any leftover WorkManager periodic jobs from the old implementation
            step("cancel legacy work") {
                val wm = WorkManager.getInstance(context)
                wm.cancelUniqueWork("morning_digest")
                wm.cancelUniqueWork("smart_notif_morning")
                wm.cancelUniqueWork("smart_notif_midday")
                wm.cancelUniqueWork("smart_notif_afternoon")
                wm.cancelUniqueWork("smart_notif_evening")
            }

            // Morning digest — AlarmManager exact alarm
            step("digest alarm") {
                if (prefs.digestEnabled.first()) {
                    val hour = prefs.morningDigestHour.first()
                    val minute = prefs.morningDigestMinute.first()
                    DigestAlarmScheduler.schedule(context, hour, minute)
                }
            }

            // Four smart notification slots — AlarmManager exact alarms. Separately guarded, so
            // one slot failing still leaves the other three armed.
            step("morning slot") {
                SmartNotificationAlarmScheduler.scheduleSlot(
                    context, SmartNotificationWorker.Slot.MORNING,
                    prefs.notifMorningEnabled.first(),
                    prefs.notifMorningHour.first(), prefs.notifMorningMinute.first()
                )
            }
            step("midday slot") {
                SmartNotificationAlarmScheduler.scheduleSlot(
                    context, SmartNotificationWorker.Slot.MIDDAY,
                    prefs.notifMiddayEnabled.first(),
                    prefs.notifMiddayHour.first(), prefs.notifMiddayMinute.first()
                )
            }
            step("afternoon slot") {
                SmartNotificationAlarmScheduler.scheduleSlot(
                    context, SmartNotificationWorker.Slot.AFTERNOON,
                    prefs.notifAfternoonEnabled.first(),
                    prefs.notifAfternoonHour.first(), prefs.notifAfternoonMinute.first()
                )
            }
            step("evening slot") {
                SmartNotificationAlarmScheduler.scheduleSlot(
                    context, SmartNotificationWorker.Slot.EVENING,
                    prefs.notifEveningEnabled.first(),
                    prefs.notifEveningHour.first(), prefs.notifEveningMinute.first()
                )
            }

            // Reminder alarms (AlarmManager clears them all on reboot) are rebuilt by a worker,
            // not here.
            //
            // The goAsync lease is roughly ten seconds and this receiver was holding it across
            // DataStore reads, a Room query, an unbounded loop over every active reminder and
            // two service starts, on a device at its busiest. Past the window Android may kill
            // the process mid-rearm, and every reminder after that point is simply gone.
            // WorkManager has no such window and retries; the receiver keeps only the short,
            // ordered work that has to happen now. Note reminders were never rearmed at all —
            // only to-dos were — and the worker covers both.
            step("queue reminder rearm") { RearmRemindersWorker.enqueue(context) }

            // Restart the microphone services if they were on.
            //
            // Android 14+ refuses a `microphone`-type foreground service started from the
            // BOOT_COMPLETED exemption. `step` recorded the refusal and carried on, so "Hey
            // Brain" was very likely dead after every reboot with the toggle still reading on —
            // and nothing anywhere said so. A refusal is now remembered and retried from
            // MainActivity, where the app genuinely is in the foreground.
            //
            // This is a retry of something Carl already switched on, not the app arming a
            // microphone on its own: the flag is only set when the setting is already true, and
            // MicRestart re-reads the setting before acting on it.
            var refused = false

            step("wake word") {
                if (prefs.wakeWordEnabled.first()) {
                    runCatching {
                        context.startForegroundService(
                            Intent(context, VoiceCaptureService::class.java)
                        )
                    }.onFailure {
                        refused = true
                        ErrorLog.record("BootReceiver/wake word refused", it)
                    }
                }
            }

            // Started after the wake word so it sees the right microphone owner and does not
            // open a second AudioRecord.
            step("ambient buffer") {
                if (prefs.ambientBufferEnabled.first()) {
                    runCatching {
                        AmbientBufferService.send(context, AmbientBufferService.ACTION_START_BUFFER)
                    }.onFailure {
                        refused = true
                        ErrorLog.record("BootReceiver/ambient buffer refused", it)
                    }
                }
            }

            step("mic restart marker") {
                if (refused) {
                    prefs.setMicRestartPending(true)
                    MicRestart.notifyNeedsReopening(context)
                }
            }
        }
        // Released however the job ends, including on failure — a lease that leaks would keep
        // the process alive indefinitely.
        job.invokeOnCompletion { pending.finish() }
    }

    /**
     * Runs one rearming step, recording a failure instead of propagating it.
     *
     * Named rather than anonymous `runCatching` at each site so the log says which step failed —
     * "the reminders stopped working after a reboot" is otherwise indistinguishable from "the
     * reminders were never set".
     */
    private suspend fun step(label: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { ErrorLog.record("BootReceiver/$label", it) }
    }
}
