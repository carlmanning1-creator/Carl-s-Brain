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
import com.carlmanning.carlsbrain.data.preferences.UserPreferences

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
                            if (pin.length < 4) {
                                errorMessage = "PIN must be at least 4 digits"
                            } else if (pin != confirmPin) {
                                errorMessage = "PINs do not match"
                            } else {
                                onSuccess(pin)
                            }
                        }
                        VaultPinDialogMode.ENTER -> {
                            val enteredHash = UserPreferences.hashPin(pin)
                            if (enteredHash == storedPinHash) {
                                onSuccess(pin)
                            } else {
                                errorMessage = "Incorrect PIN"
                                pin = ""
                            }
                        }
                    }
                },
                enabled = pin.isNotBlank()
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
