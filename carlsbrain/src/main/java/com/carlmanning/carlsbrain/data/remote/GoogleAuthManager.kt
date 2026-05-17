package com.carlmanning.carlsbrain.data.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

class GoogleAuthManager(context: Context) {

    private val client = Identity.getAuthorizationClient(context)

    private val authRequest = AuthorizationRequest.builder()
        .setRequestedScopes(
            listOf(
                Scope("https://www.googleapis.com/auth/drive.file"),
                Scope("https://www.googleapis.com/auth/calendar")
            )
        )
        .build()

    fun authorize(
        onSuccess: (String) -> Unit,
        onResolutionRequired: (PendingIntent) -> Unit,
        onError: (Exception) -> Unit
    ) {
        client.authorize(authRequest)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let(onResolutionRequired)
                        ?: onError(Exception("No pending intent returned"))
                } else {
                    result.accessToken?.let(onSuccess)
                        ?: onError(Exception("No access token returned"))
                }
            }
            .addOnFailureListener { onError(it) }
    }

    fun getTokenFromResult(data: Intent?): String? =
        runCatching { client.getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}
