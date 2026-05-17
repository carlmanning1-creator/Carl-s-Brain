package com.carlmanning.carlsbrain

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.carlmanning.carlsbrain.navigation.AppNavigation
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme

class MainActivity : FragmentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        val biometricAvailable = canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS

        setContent {
            CarlsBrainTheme {
                var isAuthenticated by remember { mutableStateOf(!biometricAvailable) }

                LaunchedEffect(Unit) {
                    if (biometricAvailable) {
                        showBiometricPrompt(
                            onSuccess = { isAuthenticated = true }
                        )
                    }
                }

                if (isAuthenticated) {
                    AppNavigation(appViewModel = appViewModel)
                } else {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Carl's Brain",
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
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
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
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
    }
}
