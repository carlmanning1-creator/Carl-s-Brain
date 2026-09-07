package com.carlmanning.carlsbrain.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.delay

/**
 * Dialog for setting or entering the vault PIN.
 *
 * @param mode SET — shows two fields (PIN + confirm) for creating/changing a PIN.
 *             ENTER — shows a single field for unlocking.
 * @param storedPinHash The currently stored SHA-256 hash; used in ENTER mode to verify.
 * @param onSuccess Called when PIN is confirmed/verified successfully.
 * @param onDismiss Called when the user cancels.
 */
@Composable
fun VaultPinDialog(
    mode: VaultPinDialogMode,
    storedPinHash: String = "",
    onSuccess: (pin: String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Backoff state. Deliberately per-dialog rather than persisted: this raises the cost of
    // guessing at the keypad, which is the threat here. Persisting it would let a failed guess
    // lock Carl out of his own app across restarts, which is a worse outcome than the attack.
    var attempts by remember { mutableIntStateOf(0) }
    var lockedUntilMs by remember { mutableLongStateOf(0L) }
    // Re-composes while a lockout is counting down, so the button re-enables by itself.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntilMs) {
        while (lockedUntilMs > System.currentTimeMillis()) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
        nowMs = System.currentTimeMillis()
    }
    val lockedOut = lockedUntilMs > nowMs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (mode) {
                    VaultPinDialogMode.SET -> "Set Vault PIN"
                    VaultPinDialogMode.CHANGE -> "Change Vault PIN"
                    VaultPinDialogMode.ENTER -> "Enter Vault PIN"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == VaultPinDialogMode.ENTER) {
                    Text(
                        text = "Biometrics unavailable. Enter your Vault PIN to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() } },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errorMessage != null
                )
                if (mode == VaultPinDialogMode.SET || mode == VaultPinDialogMode.CHANGE) {
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() } },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = errorMessage != null
                    )
                }
                errorMessage?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    errorMessage = null
                    when (mode) {
                        VaultPinDialogMode.SET, VaultPinDialogMode.CHANGE -> {
                            // Six digits for a *new* PIN, and trivial values refused. This
                            // accepted four digits and "1234" — on a secret that is also the
                            // whole-app unlock when biometrics are unavailable. An existing
                            // shorter PIN keeps working; only setting a new one is held to this.
                            if (pin.length < UserPreferences.MIN_NEW_PIN_LENGTH) {
                                errorMessage =
                                    "PIN must be at least ${UserPreferences.MIN_NEW_PIN_LENGTH} digits"
                            } else if (UserPreferences.isTrivialPin(pin)) {
                                errorMessage = "That PIN is too easy to guess — try another"
                            } else if (pin != confirmPin) {
                                errorMessage = "PINs do not match"
                            } else {
                                onSuccess(pin)
                            }
                        }
                        VaultPinDialogMode.ENTER -> {
                            if (lockedUntilMs > System.currentTimeMillis()) {
                                errorMessage = waitMessage(lockedUntilMs)
                            } else if (UserPreferences.verifyPin(pin, storedPinHash)) {
                                attempts = 0
                                onSuccess(pin)
                            } else {
                                // Backing off after a few wrong entries. Without it the PIN space
                                // can be walked at typing speed by anyone holding the phone —
                                // and this PIN opens the app as well as the vault.
                                attempts++
                                pin = ""
                                if (attempts >= FREE_ATTEMPTS) {
                                    val delayMs = backoffMs(attempts)
                                    lockedUntilMs = System.currentTimeMillis() + delayMs
                                    errorMessage = waitMessage(lockedUntilMs)
                                } else {
                                    errorMessage = "Incorrect PIN"
                                }
                            }
                        }
                    }
                },
                enabled = pin.isNotBlank() && !lockedOut
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

enum class VaultPinDialogMode { SET, CHANGE, ENTER }

/** Wrong entries allowed before the delay starts. Three fat-fingered tries is not an attack. */
private const val FREE_ATTEMPTS = 3

/** Doubling delay, capped at five minutes so a genuine lockout is survivable. */
private fun backoffMs(attempts: Int): Long {
    val steps = (attempts - FREE_ATTEMPTS).coerceIn(0, 6)
    return (5_000L shl steps).coerceAtMost(5 * 60_000L)
}

private fun waitMessage(untilMs: Long): String {
    val seconds = ((untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
    return if (seconds < 60) "Too many attempts — wait ${seconds}s"
    else "Too many attempts — wait ${(seconds + 59) / 60} min"
}
