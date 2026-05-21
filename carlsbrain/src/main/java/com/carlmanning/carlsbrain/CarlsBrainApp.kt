package com.carlmanning.carlsbrain

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CarlsBrainApp : Application(), Configuration.Provider {

    /** Single Room instance for the whole process. Lazy so the file isn't touched until needed. */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** DataStore-backed user settings (Anthropic API key, digest time, vault toggles). */
    val userPreferences: UserPreferences by lazy { UserPreferences(this) }

    // ---------- Process-scoped UI state ----------
    //
    // Lock and vault visibility live here (not in an Activity-scoped ViewModel) for two reasons:
    //   1. Lock-on-background must reliably fire whenever the whole process leaves the
    //      foreground, regardless of which Activity is on top. ProcessLifecycleOwner is the
    //      only source of truth that ignores Activity config changes (rotation, theme switch)
    //      while still catching real background transitions.
    //   2. Vault visibility must auto-hide on backgrounding so a sensitive bucket revealed in
    //      one session doesn't stay revealed after the phone is put down and picked up again.

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _isVaultVisible = MutableStateFlow(false)
    val isVaultVisible: StateFlow<Boolean> = _isVaultVisible.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // Whole process moved to background (not a config change — ProcessLifecycle
                    // debounces those out). Re-lock and hide any revealed vault items.
                    lock()
                }
            }
        )
    }

    fun unlock() {
        _isLocked.value = false
    }

    fun lock() {
        _isLocked.value = true
        _isVaultVisible.value = false
    }

    fun toggleVaultVisibility() {
        _isVaultVisible.value = !_isVaultVisible.value
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
