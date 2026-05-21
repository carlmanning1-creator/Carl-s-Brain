package com.carlmanning.carlsbrain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Activity-scoped facade over the process-scoped state held in [CarlsBrainApp]. Screens get
 * the StateFlows they need (lock state, vault visibility) and action callbacks without having
 * to know that the underlying source of truth lives on the Application — important because
 * lock and vault state must survive Activity recreation (rotation, theme change) and only
 * react to actual process-level background events.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CarlsBrainApp = application as CarlsBrainApp

    val isLocked: StateFlow<Boolean> = app.isLocked
    val isVaultVisible: StateFlow<Boolean> = app.isVaultVisible

    fun unlock() = app.unlock()
    fun toggleVaultVisibility() = app.toggleVaultVisibility()
}
