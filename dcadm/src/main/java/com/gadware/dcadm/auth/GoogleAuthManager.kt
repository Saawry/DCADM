package com.gadware.dcadm.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.VisibleForTesting
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
import java.security.MessageDigest

/**
 * Top-level alias pointing to [GoogleAuthManager.GoogleSignInResult] for convenient imports:
 * `import com.gadware.dcadm.auth.GoogleSignInResult`
 */
typealias GoogleSignInResult = GoogleAuthManager.GoogleSignInResult

class GoogleAuthManager(private val context: Context) {

    /**
     * Result of a successful Google Sign-In containing the authenticated [FirebaseUser]
     * and the raw Google [idToken].
     *
     * **Security Notice:**
     * - The [idToken] is intended for one-time exchange/verification with your backend server and expires in approximately one hour (~3600 seconds).
     * - Never log the [idToken] to logcat, analytics, or remote logging services.
     * - Never persist the [idToken] in [SessionManager], SharedPreferences, databases, or local disk storage.
     *
     * @property user The authenticated Firebase user.
     * @property idToken The Google ID token string for backend verification.
     */
    data class GoogleSignInResult(
        val user: FirebaseUser,
        val idToken: String
    ) {
        override fun toString(): String {
            return "GoogleSignInResult(user=$user, idToken=[PROTECTED])"
        }
    }

    private var credentialManager: CredentialManager = CredentialManager.create(context)
    private var sessionManager: SessionManager = SessionManager(context)
    private var firebaseAuthHandler: suspend (String) -> FirebaseUser? = { idToken ->
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val auth = FirebaseAuth.getInstance()
        try {
            auth.signInWithCredential(credential).await().user
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Firebase auth failed", e)
            null
        }
    }
    internal var credentialExtractor: (CustomCredential) -> Pair<String, String>? = { credential ->
        try {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            Pair(googleIdTokenCredential.id, googleIdTokenCredential.idToken)
        } catch (e: Exception) {
            DcadmLog.e("GoogleAuthManager", "Failed to parse GoogleIdTokenCredential", e)
            null
        }
    }

    @VisibleForTesting
    internal constructor(
        context: Context,
        credentialManager: CredentialManager,
        sessionManager: SessionManager,
        firebaseAuthHandler: suspend (String) -> FirebaseUser?,
        credentialExtractor: ((CustomCredential) -> Pair<String, String>?)? = null
    ) : this(context) {
        this.credentialManager = credentialManager
        this.sessionManager = sessionManager
        this.firebaseAuthHandler = firebaseAuthHandler
        if (credentialExtractor != null) {
            this.credentialExtractor = credentialExtractor
        }
    }

    /**
     * Authenticates the user using Android Credential Manager and Firebase Auth,
     * returning both the authenticated [FirebaseUser] and the Google [idToken].
     *
     * @param activity The host Activity used by Credential Manager to present the sign-in bottom sheet.
     * @param rawNonce Optional unhashed, raw nonce string. If provided, its SHA-256 hex digest is
     *                 computed and sent to Google via [GetGoogleIdOption.Builder.setNonce].
     *                 The caller passes the same [rawNonce] to their backend server for verification.
     * @return [Result] containing [GoogleSignInResult] on success, or a failure exception.
     *
     * **Security Notice:**
     * - The [GoogleSignInResult.idToken] is intended for one-time exchange/verification with your backend server
     *   and expires in approximately one hour (~3600 seconds).
     * - Never log the token and never store it in [SessionManager], SharedPreferences, or on-disk storage.
     */
    suspend fun signInWithIdToken(
        activity: Activity,
        rawNonce: String? = null
    ): Result<GoogleSignInResult> {
        val googleIdOptionBuilder = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(DcadmConfig.getWebClientId())
            .setAutoSelectEnabled(false)

        if (rawNonce != null) {
            googleIdOptionBuilder.setNonce(hashNonce(rawNonce))
        }

        val request = GetCredentialRequest.Builder()
            .setCredentialOptions(listOf(googleIdOptionBuilder.build()))
            .build()

        return try {
            val result = credentialManager.getCredential(
                request = request,
                context = activity
            )

            val credential = result.credential

            when {
                credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val extracted = credentialExtractor(credential)
                    if (extracted == null) {
                        DcadmLog.e("GoogleAuthManager", "Failed to parse Google ID token credential")
                        Result.failure(Exception("Failed to parse Google ID token credential"))
                    } else {
                        val (email, idToken) = extracted
                        val firebaseUser = firebaseAuthWithGoogle(idToken)

                        if (firebaseUser != null) {
                            sessionManager.saveUserEmail(email)
                            Result.success(GoogleSignInResult(user = firebaseUser, idToken = idToken))
                        } else {
                            Result.failure(Exception("Firebase auth failed."))
                        }
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

    /**
     * Authenticates the user using Android Credential Manager and Firebase Auth.
     * Preserved for backward compatibility.
     */
    suspend fun signIn(activity: Activity): Result<FirebaseUser> {
        return signInWithIdToken(activity, null).map { it.user }
    }

    private suspend fun firebaseAuthWithGoogle(idToken: String): FirebaseUser? {
        return firebaseAuthHandler(idToken)
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
                DcadmLog.d("GoogleAuthManager", "Silent authorization resolution returned for $email. Trying GoogleAuthUtil fallback.")
                val fallbackToken = getGoogleAuthUtilToken(context, email)
                if (fallbackToken != null) {
                    sessionManager.saveDriveToken(fallbackToken)
                    return Result.success(fallbackToken)
                }
                Result.failure(AuthResolutionRequiredException(result.pendingIntent))
            } else {
                val accessToken = result.accessToken
                if (accessToken != null) {
                    DcadmLog.d("GoogleAuthManager", "Fresh silent access token acquired for $email, saving to session")
                    sessionManager.saveDriveToken(accessToken)
                    Result.success(accessToken)
                } else {
                    val fallbackToken = getGoogleAuthUtilToken(context, email)
                    if (fallbackToken != null) {
                        sessionManager.saveDriveToken(fallbackToken)
                        Result.success(fallbackToken)
                    } else {
                        DcadmLog.e("GoogleAuthManager", "Silent authorization succeeded but access token is null for $email")
                        Result.failure(Exception("Authorization succeeded but access token is null"))
                    }
                }
            }
        } catch (e: Exception) {
            DcadmLog.d("GoogleAuthManager", "Identity.authorize failed for $email: ${e.message}. Trying GoogleAuthUtil fallback.")
            val fallbackToken = getGoogleAuthUtilToken(context, email)
            if (fallbackToken != null) {
                sessionManager.saveDriveToken(fallbackToken)
                Result.success(fallbackToken)
            } else {
                DcadmLog.e("GoogleAuthManager", "Silent auth error for $email", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun getGoogleAuthUtilToken(context: Context, email: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val scopeStr = "oauth2:${DriveScopes.DRIVE_APPDATA}"
            val account = Account(email, "com.google")
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, account, scopeStr)
        } catch (e: Exception) {
            DcadmLog.d("GoogleAuthManager", "GoogleAuthUtil.getToken background attempt: ${e.message}")
            null
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

    companion object {
        /**
         * Computes the lowercase SHA-256 hexadecimal string of the given [rawNonce].
         * Suitable for passing to Google Credential Manager ([GetGoogleIdOption.Builder.setNonce]).
         */
        @JvmStatic
        fun hashNonce(rawNonce: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(rawNonce.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}

class AuthResolutionRequiredException(val pendingIntent: PendingIntent?) : Exception("User resolution required")
