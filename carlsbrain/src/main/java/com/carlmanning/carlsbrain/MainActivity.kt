package com.carlmanning.carlsbrain

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlmanning.carlsbrain.navigation.AppNavigation
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme

class MainActivity : FragmentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CarlsBrainTheme {
                val isLocked by appViewModel.isLocked.collectAsStateWithLifecycle()

                // Fire the biometric prompt every time the Activity is brought to the
                // foreground while still locked. ON_START fires on cold start, after a
                // background → foreground transition, and after the prompt itself is
                // dismissed by the system (e.g. an incoming call). This replaces the
                // previous LaunchedEffect(Unit) which only fired once for the lifetime
                // of the Activity and left the user stuck on a dead lock screen if the
                // prompt was cancelled or interrupted.
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    if (isLocked) showBiometricPrompt()
                }

                // Render the nav graph unconditionally so its remembered state (selected
                // tab, scroll positions, draft text) survives a lock cycle. The lock
                // screen is an opaque overlay on top — never simultaneously visible with
                // the underlying nav.
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(appViewModel = appViewModel)
                    if (isLocked) {
                        LockScreen(onRetry = { showBiometricPrompt() })
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device has no biometric AND no PIN/pattern/password — we cannot enforce a
            // lock without a credential to compare against. Unlock with a logged warning
            // rather than trapping the user forever. The recommended remediation is for
            // the user to set a device screen lock; in practice every modern phone has one.
            Log.w(
                "MainActivity",
                "No biometric or device credential available (code=$canAuthenticate); unlocking without prompt"
            )
            appViewModel.unlock()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appViewModel.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled, hardware error, or lockout. We do NOT auto-retry —
                    // the lock screen stays visible with an Unlock button so the user
                    // can decide when to try again.
                    Log.i("MainActivity", "Biometric error $errorCode: $errString")
                }

                override fun onAuthenticationFailed() {
                    // Single biometric mismatch. BiometricPrompt retries internally up to
                    // its lockout threshold; nothing to do here.
                }
            }
        )

        prompt.authenticate(promptInfo)
    }
}

@Composable
private fun LockScreen(onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Carl's Brain",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = onRetry) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Unlock")
            }
        }
    }
}
