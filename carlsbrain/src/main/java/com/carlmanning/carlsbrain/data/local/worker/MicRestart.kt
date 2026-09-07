package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.data.local.ErrorLog
import kotlinx.coroutines.flow.first

/**
 * Recovers the microphone services after a reboot Android would not let them start from.
 *
 * Android 14+ refuses a `microphone`-type foreground service started from the BOOT_COMPLETED
 * exemption. `BootReceiver` recorded the refusal and carried on, so "Hey Brain" was very likely
 * dead after every reboot while its toggle still read "on" — a silent failure with no symptom
 * except the wake word not answering, which is indistinguishable from it mishearing.
 *
 * The recovery is deliberately narrow:
 *
 * - It is a **retry of something Carl already switched on**, never the app deciding a microphone
 *   should be live. [retryIfPending] re-reads each on/off setting and does nothing if it is off,
 *   so turning the ambient buffer off between the failed boot and the next launch stands.
 * - The ambient buffer's setting remains the consent control. Nothing here can set it.
 * - It runs from [MainActivity], where the app genuinely is in the foreground and the start is
 *   allowed, rather than from anything that could fire in the background.
 */
object MicRestart {

    const val CHANNEL_ID = "mic_restart"
    private const val NOTIFICATION_ID = 9401

    /**
     * Tells Carl the wake word needs the app opened once.
     *
     * Posted rather than fixed silently because a failure he cannot see is precisely the shape
     * this whole class exists to undo.
     */
    fun notifyNeedsReopening(context: Context) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hey Brain needs reopening")
            .setContentText("Android wouldn't let it start after the restart. Open the app once and it's back.")
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { ErrorLog.record("MicRestart/notify", it) }
    }

    /**
     * Starts whichever microphone services are switched on but were refused at boot.
     *
     * Safe to call on every foreground launch: it returns immediately unless the marker is set,
     * and the marker is cleared whatever the outcome — a retry that fails again should not nag
     * on every single launch, and the next reboot will set it afresh.
     */
    suspend fun retryIfPending(context: Context) {
        val prefs = CarlsBrainApp.userPreferences
        if (!prefs.micRestartPending.first()) return

        // Cleared first. If a start throws again, the failure is logged rather than re-queued:
        // a marker that survives its own retry becomes a permanent notification.
        prefs.setMicRestartPending(false)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        // Both settings are re-read here, not carried over from boot. Carl may have turned one
        // off in between, and the stale intention must not win over the current setting.
        if (prefs.wakeWordEnabled.first()) {
            runCatching {
                context.startForegroundService(Intent(context, VoiceCaptureService::class.java))
            }.onFailure { ErrorLog.record("MicRestart/wake word", it) }
        }
        if (prefs.ambientBufferEnabled.first()) {
            runCatching {
                AmbientBufferService.send(context, AmbientBufferService.ACTION_START_BUFFER)
            }.onFailure { ErrorLog.record("MicRestart/ambient buffer", it) }
        }
    }
}
