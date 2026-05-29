package com.carlmanning.carlsbrain

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.carlmanning.carlsbrain.data.local.worker.WeeklyReviewWorker
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.navigation.AppNavigation
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    private val prefs by lazy { UserPreferences(this) }
    private val biometricAvailable: Boolean by lazy {
        BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // Mutable state owned by Activity so Compose observes it.
    // Initialised from savedInstanceState so rotation doesn't relock.
    private var isAuthenticated by mutableStateOf(false)
    private var showRetry by mutableStateOf(false)
    private var showVaultPinFallback by mutableStateOf(false)
    // Cached preference so onStop() never blocks the main thread
    private var biometricLockEnabledCache = true
    // Grace-period job: only sets a flag — the actual auth reset happens in onStart()
    // so the biometric prompt is never shown while the app is in the background.
    private var authResetJob: Job? = null
    private var needsReauth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Restore auth across config changes (rotation) — don't relock on rotation
        if (savedInstanceState?.getBoolean(KEY_IS_AUTHENTICATED) == true) {
            isAuthenticated = true
        }
        handleIntent(intent)

        lifecycleScope.launch {
            prefs.biometricLockEnabled.collect { biometricLockEnabledCache = it }
        }

        setContent {
            CarlsBrainTheme {
                val lockEnabled by prefs.biometricLockEnabled.collectAsState(initial = true)

                val notificationsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* user made their choice */ }

                val vaultPinHash by prefs.vaultPinHash.collectAsState(initial = "")

                fun promptBiometric() {
                    showRetry = false
                    showVaultPinFallback = false
                    showBiometricPrompt(
                        onSuccess = { isAuthenticated = true },
                        onDismissed = {
                            if (vaultPinHash.isNotBlank()) {
                                showVaultPinFallback = true
                            } else {
                                showRetry = true
                            }
                        }
                    )
                }

                // Prompt whenever auth state or lock setting changes
                LaunchedEffect(lockEnabled, isAuthenticated) {
                    when {
                        !biometricAvailable -> isAuthenticated = true
                        !lockEnabled -> isAuthenticated = true
                        !isAuthenticated -> promptBiometric()
                    }
                }

                LaunchedEffect(isAuthenticated) {
                    if (isAuthenticated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // AppNavigation is ALWAYS in the composition so NavController state
                // (current screen, back stack) survives biometric re-auth cycles.
                // The lock screen is an overlay on top, not a replacement.
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(appViewModel = appViewModel)

                    if (!isAuthenticated) {
                        // Vault PIN fallback dialog
                        if (showVaultPinFallback) {
                            com.carlmanning.carlsbrain.ui.components.VaultPinDialog(
                                mode = com.carlmanning.carlsbrain.ui.components.VaultPinDialogMode.ENTER,
                                storedPinHash = vaultPinHash,
                                onSuccess = {
                                    isAuthenticated = true
                                    showVaultPinFallback = false
                                },
                                onDismiss = {
                                    showVaultPinFallback = false
                                    showRetry = true
                                }
                            )
                        }

                        Surface(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Carl's Brain",
                                    style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.padding(16.dp)
                                )
                                if (showRetry) {
                                    Spacer(Modifier.height(24.dp))
                                    Button(onClick = { promptBiometric() }) {
                                        Text("Unlock")
                                    }
                                    if (vaultPinHash.isNotBlank()) {
                                        Spacer(Modifier.height(8.dp))
                                        Button(onClick = { showVaultPinFallback = true; showRetry = false }) {
                                            Text("Use PIN")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Cancel the grace-period timer if the user returned quickly
        authResetJob?.cancel()
        authResetJob = null
        // Apply a pending reauth now that we're in the foreground — biometric prompt
        // must never be shown while the app is backgrounded (Android silently rejects it).
        if (needsReauth) {
            needsReauth = false
            isAuthenticated = false
            showRetry = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (biometricAvailable && biometricLockEnabledCache) {
            // 3-second grace period: only set a flag here — don't touch isAuthenticated
            // while backgrounded, or the LaunchedEffect fires with no foreground window.
            authResetJob = lifecycleScope.launch {
                delay(3_000)
                needsReauth = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_AUTHENTICATED, isAuthenticated)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_CAPTURE -> appViewModel.requestCapture()
            ACTION_OPEN_CAPTURE_TODO -> appViewModel.requestCapture(type = "TODO")
            ACTION_OPEN_CAPTURE_NOTE -> appViewModel.requestCapture(type = "NOTE")
            ACTION_OPEN_CAPTURE_VOICE -> appViewModel.requestCapture(type = "TODO", startVoice = true)
            ACTION_START_MEETING -> appViewModel.requestStartMeeting()
            WeeklyReviewWorker.ACTION_OPEN_WEEKLY_REVIEW -> {
                val prompt = intent.getStringExtra(WeeklyReviewWorker.EXTRA_REVIEW_PROMPT)
                if (!prompt.isNullOrBlank()) appViewModel.requestChatPrompt(prompt)
            }
        }
        // Deep-link from voice capture and meeting notifications
        intent?.getLongExtra(EXTRA_OPEN_NOTE_ID, -1L)?.takeIf { it != -1L }
            ?.let { appViewModel.requestOpenNote(it) }
        intent?.getLongExtra(EXTRA_OPEN_TODO_ID, -1L)?.takeIf { it != -1L }
            ?.let { appViewModel.requestOpenTodo(it) }
        intent?.getLongExtra(EXTRA_OPEN_MEETING_ID, -1L)?.takeIf { it != -1L }
            ?.let { appViewModel.requestOpenMeeting(it) }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit, onDismissed: () -> Unit = {}) {
        val executor = ContextCompat.getMainExecutor(this)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onDismissed()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        private const val KEY_IS_AUTHENTICATED = "is_authenticated"
        const val ACTION_OPEN_CAPTURE = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE"
        const val ACTION_OPEN_CAPTURE_TODO = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE_TODO"
        const val ACTION_OPEN_CAPTURE_NOTE = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE_NOTE"
        const val ACTION_OPEN_CAPTURE_VOICE = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE_VOICE"
        const val ACTION_START_MEETING = "com.carlmanning.carlsbrain.ACTION_START_MEETING"
        const val EXTRA_OPEN_NOTE_ID = "open_note_id"
        const val EXTRA_OPEN_TODO_ID = "open_todo_id"
        const val EXTRA_OPEN_MEETING_ID = "open_meeting_id"
    }
}
