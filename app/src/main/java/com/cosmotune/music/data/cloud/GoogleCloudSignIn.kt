package com.cosmotune.music.data.cloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.cosmotune.music.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Asks Google for an ID token through Credential Manager, for Firebase to sign the cloud account in with. The token is for the
 * Firebase project's web client (the server client id), which is what Firebase checks it against; the app's own package and
 * signing key are registered with that project, which is what lets Google hand the token to this app at all.
 */
internal object GoogleCloudSignIn {
    private const val SERVER_CLIENT_ID = "805536285560-uqg9elhnmh2o3c7v07nj7pvmgedtfrba.apps.googleusercontent.com"

    /** A sign-in that did not produce a token, with what to tell the person. */
    class Failure(@get:StringRes val messageRes: Int, cause: Throwable? = null) : Exception(cause)

    /** Needs an Activity: the account chooser is a sheet over it. */
    suspend fun idToken(context: Context): String {
        val activity = context.findActivity() ?: throw Failure(R.string.cloud_sign_in_failed)
        val manager = CredentialManager.create(activity)
        val response = try {
            // Every Google account on the phone, not only ones that used Cosmotune before: a first sign-in has none of the latter.
            manager.getCredential(activity, request(GetGoogleIdOption.Builder()
                .setServerClientId(SERVER_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false)
                .build()))
        } catch (none: NoCredentialException) {
            // The bottom sheet finds nothing on some phones (no account offered to it, or it was dismissed too often and is cooling
            // down). The "Sign in with Google" flow is Google's own full-screen chooser, which also lets an account be added.
            try {
                manager.getCredential(activity, request(GetSignInWithGoogleOption.Builder(SERVER_CLIENT_ID).build()))
            } catch (failure: GetCredentialException) {
                throw Failure(messageFor(failure), failure)
            }
        } catch (failure: GetCredentialException) {
            throw Failure(messageFor(failure), failure)
        }
        val credential = response.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw Failure(R.string.cloud_sign_in_failed)
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (failure: GoogleIdTokenParsingException) {
            throw Failure(R.string.cloud_sign_in_failed, failure)
        }
    }

    /** So the next sign-in asks which account again instead of quietly reusing this one. */
    suspend fun forget(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun request(option: CredentialOption) = GetCredentialRequest.Builder().addCredentialOption(option).build()

    @StringRes
    private fun messageFor(failure: GetCredentialException): Int = when {
        failure is GetCredentialCancellationException -> R.string.cloud_sign_in_cancelled
        failure is NoCredentialException -> R.string.cloud_sign_in_no_account
        // Play services reports a lost connection as an unknown error whose message says so; there is no type of its own for it.
        failure.errorMessage?.contains("network", ignoreCase = true) == true -> R.string.cloud_sign_in_offline
        else -> R.string.cloud_sign_in_failed
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
