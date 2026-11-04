package com.shehrozsf.ytmcontroller

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only YouTube access via Google Play services. No client secret or API key
 * lives in the app: Google identifies us by package name + signing certificate.
 */
object GoogleAuth {
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
        .build()

    /** Starts authorization; the result either has a token or a consent screen to show. */
    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request).await()

    /** A fresh token if the user already granted access, without showing any UI; else null. */
    suspend fun token(context: Context): String? {
        val result = authorize(context)
        return if (result.hasResolution()) null else result.accessToken
    }

    /** Reads the token after the consent screen returns. */
    fun tokenFromConsent(context: Context, data: Intent?): String? =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
