package com.carlmanning.carlsbrain

import android.app.Application
import androidx.work.Configuration
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.preferences.UserPreferences

class CarlsBrainApp : Application(), Configuration.Provider {

    /** Single Room instance for the whole process. Lazy so the file isn't touched until needed. */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** DataStore-backed user settings (Anthropic API key, digest time, vault toggles). */
    val userPreferences: UserPreferences by lazy { UserPreferences(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
