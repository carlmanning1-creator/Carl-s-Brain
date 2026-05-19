package com.carlmanning.carlsbrain.data.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

private const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"

class GoogleAuthManager(context: Context) {

    private val client = Identity.getAuthorizationClient(context)

    private val fullRequest = AuthorizationRequest.builder()
        .setRequestedScopes(
            listOf(
                Scope("https://www.googleapis.com/auth/drive.file"),
                Scope(CALENDAR_SCOPE)
            )
        )
        .build()

    private val calendarOnlyRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(CALENDAR_SCOPE)))
        .build()

    fun authorize(
        onSuccess: (String) -> Unit,
        onResolutionRequired: (PendingIntent) -> Unit,
        onError: (Exception) -> Unit
    ) {
        client.authorize(fullRequest)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let(onResolutionRequired)
                        ?: onError(Exception("No pending intent returned"))
                    return@addOnSuccessListener
                }

                val token = result.accessToken ?: run {
                    onError(Exception("No access token returned"))
                    return@addOnSuccessListener
                }

                // Check that Calendar scope was actually granted.
                // grantedScopes is null on some older Play Services builds — treat null as "assume granted".
                val grantedScopes = result.grantedScopes
                val calendarGranted = grantedScopes == null ||
                        grantedScopes.any { it.toString() == CALENDAR_SCOPE }

                if (calendarGranted) {
                    onSuccess(token)
                } else {
                    // Drive token returned but Calendar scope missing.
                    // Re-request specifically for Calendar to get the consent PendingIntent.
                    client.authorize(calendarOnlyRequest)
                        .addOnSuccessListener { calResult ->
                            if (calResult.hasResolution()) {
                                calResult.pendingIntent?.let(onResolutionRequired)
                                    ?: onError(Exception("Calendar consent required but no intent available"))
                            } else {
                                // Calendar scope still not in granted set after re-request.
                                // Pass the token through — the 401 at API level will surface the real error.
                                onSuccess(token)
                            }
                        }
                        .addOnFailureListener { onError(it) }
                }
            }
            .addOnFailureListener { onError(it) }
    }

    /**
     * Must be called with the Intent from StartIntentSenderForResult after the user completes the
     * Google consent screen. This exchanges the authorization code for an access token inside
     * Google Play Services so the next authorize() call returns a valid token.
     */
    fun processConsentResult(data: Intent?): String? =
        runCatching { client.getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}
