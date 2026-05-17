package com.carlmanning.carlsbrain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        CoroutineScope(Dispatchers.IO).launch {
            val prefs = UserPreferences(context)
            val hour = prefs.morningDigestHour.first()
            val minute = prefs.morningDigestMinute.first()
            DigestScheduler.schedule(context, hour, minute, ExistingPeriodicWorkPolicy.REPLACE)
        }
    }
}
