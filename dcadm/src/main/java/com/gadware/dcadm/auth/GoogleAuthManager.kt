package com.gadware.dcadm.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.utils.DcadmLog
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await


class GoogleAuthManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)
    private val sessionManager = SessionManager(context)

    suspend fun signIn(activity: Activity): Result<FirebaseUser> {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(DcadmConfig.getWebClientId())
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .setCredentialOptions(listOf(googleIdOption))
            .build()

        return try {
            val result = credentialManager.getCredential(
                request = request,
                context = activity
            )

            val credential = result.credential

            when {
                credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    val email = googleIdTokenCredential.id

                    val firebaseUser = firebaseAuthWithGoogle(idToken)

                    if (firebaseUser != null) {
                        sessionManager.saveUserEmail(email)
                        Result.success(firebaseUser)
                    } else {
                        Result.failure(Exception("Firebase auth failed."))
                    }
                }
                else -> {
                    DcadmLog.e("GoogleAuthManager", "Unexpected credential type: ${credential.type}")
                    Result.failure(Exception("Unexpected credential type: ${credential.type}"))
                }
            }
        } catch (e: GetCredentialException) {
            DcadmLog.e("GoogleAuthManager", "Sign in failed with GetCredentialException: ${e.type}", e)
            Result.failure(e)
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Sign in error", e)
            Result.failure(e)
        }
    }

    private suspend fun firebaseAuthWithGoogle(idToken: String): FirebaseUser? {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val auth = FirebaseAuth.getInstance()
        return try {
            auth.signInWithCredential(credential).await().user
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Firebase auth failed", e)
            null
        }
    }
    suspend fun requestDriveAccess(activity: Activity, email: String): Result<String> {
        val authorizationClient = Identity.getAuthorizationClient(activity)
        val requestedScopes = listOf(Scope(DriveScopes.DRIVE_APPDATA))
        
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .setAccount(Account(email, "com.google"))
            .build()

        return try {
            // authorize() returns AuthorizationResult
            val result = authorizationClient.authorize(authorizationRequest).await()
            if (result.hasResolution()) {
                DcadmLog.d("GoogleAuthManager", "Authorization resolution required for $email")
                Result.failure(AuthResolutionRequiredException(result.pendingIntent))
            } else {
                // Granted, extract the access token
                val accessToken = result.accessToken
                if (accessToken != null) {
                    DcadmLog.d("GoogleAuthManager", "Fresh access token acquired for $email, saving to session")
                    sessionManager.saveDriveToken(accessToken)
                    Result.success(accessToken)
                } else {
                    DcadmLog.e("GoogleAuthManager", "Authorization succeeded but access token is null for $email")
                    Result.failure(Exception("Authorization succeeded but access token is null"))
                }
            }
        } catch (e: ApiException) {
             DcadmLog.e("GoogleAuthManager", "Auth failed for $email", e)
             Result.failure(e)
        }

    }

    suspend fun silentDriveAccess(context: Context = this.context, email: String): Result<String> {
        val authorizationClient = Identity.getAuthorizationClient(context)
        val requestedScopes = listOf(Scope(DriveScopes.DRIVE_APPDATA))

        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .setAccount(Account(email, "com.google"))
            .build()

        return try {
            val result = authorizationClient.authorize(authorizationRequest).await()
            if (result.hasResolution()) {
                DcadmLog.d("GoogleAuthManager", "Silent authorization resolution required for $email")
                Result.failure(AuthResolutionRequiredException(result.pendingIntent))
            } else {
                val accessToken = result.accessToken
                if (accessToken != null) {
                    DcadmLog.d("GoogleAuthManager", "Fresh silent access token acquired for $email, saving to session")
                    sessionManager.saveDriveToken(accessToken)
                    Result.success(accessToken)
                } else {
                    DcadmLog.e("GoogleAuthManager", "Silent authorization succeeded but access token is null for $email")
                    Result.failure(Exception("Authorization succeeded but access token is null"))
                }
            }
        } catch (e: ApiException) {
            DcadmLog.e("GoogleAuthManager", "Silent auth failed for $email", e)
            Result.failure(e)
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Silent auth error for $email", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            FirebaseAuth.getInstance().signOut()
            Identity.getSignInClient(context).signOut().await()
            sessionManager.clearSession()
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Sign out error", e)
        }
    }

    suspend fun deleteAccount(): Result<Unit> {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return Result.failure(Exception("No user logged in"))
        
        return try {
            user.delete().await()
            signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Delete account error", e)
            Result.failure(e)
        }
    }
}

class AuthResolutionRequiredException(val pendingIntent: PendingIntent?) : Exception("User resolution required")
