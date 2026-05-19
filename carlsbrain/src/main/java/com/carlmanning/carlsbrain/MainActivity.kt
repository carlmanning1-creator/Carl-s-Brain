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
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.navigation.AppNavigation
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    private val prefs by lazy { UserPreferences(this) }
    private val biometricAvailable: Boolean by lazy {
        BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // Mutable state owned by Activity so Compose observes it and resets on ON_STOP
    private var isAuthenticated by mutableStateOf(false)
    private var showRetry by mutableStateOf(false)
    // Cached preference so onStop() never blocks the main thread
    private var biometricLockEnabledCache = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

                fun promptBiometric() {
                    showRetry = false
                    showBiometricPrompt(
                        onSuccess = { isAuthenticated = true },
                        onDismissed = { showRetry = true }
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

                if (isAuthenticated) {
                    AppNavigation(appViewModel = appViewModel)
                } else {
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
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (biometricAvailable && biometricLockEnabledCache) {
            isAuthenticated = false
            showRetry = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_CAPTURE -> appViewModel.requestCapture()
            ACTION_OPEN_CAPTURE_TODO -> appViewModel.requestCapture(type = "TODO")
            ACTION_OPEN_CAPTURE_NOTE -> appViewModel.requestCapture(type = "NOTE")
            ACTION_OPEN_CAPTURE_VOICE -> appViewModel.requestCapture(type = "TODO", startVoice = true)
        }
        // Deep-link from voice capture notifications
        intent?.getLongExtra(EXTRA_OPEN_NOTE_ID, -1L)?.takeIf { it != -1L }
            ?.let { appViewModel.requestOpenNote(it) }
        intent?.getLongExtra(EXTRA_OPEN_TODO_ID, -1L)?.takeIf { it != -1L }
            ?.let { appViewModel.requestOpenTodo(it) }
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
        const val ACTION_OPEN_CAPTURE = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE"
        const val ACTION_OPEN_CAPTURE_TODO = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE_TODO"
        const val ACTION_OPEN_CAPTURE_NOTE = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE_NOTE"
        const val ACTION_OPEN_CAPTURE_VOICE = "com.carlmanning.carlsbrain.ACTION_OPEN_CAPTURE_VOICE"
        const val EXTRA_OPEN_NOTE_ID = "open_note_id"
        const val EXTRA_OPEN_TODO_ID = "open_todo_id"
    }
}
