package com.gadware.dcadm.auth

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.data.SessionManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GoogleAuthManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockActivity: Activity
    private lateinit var mockCredentialManager: CredentialManager
    private lateinit var fakeSessionManager: FakeSessionManager

    class FakeSessionManager(context: Context) : SessionManager(context) {
        var savedEmail: String? = null

        override fun saveUserEmail(email: String) {
            this.savedEmail = email
        }

        override fun getUserEmail(): String? = savedEmail
    }

    @Before
    fun setUp() {
        mockContext = mock()
        val mockPrefs = mock<android.content.SharedPreferences>()
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockPrefs)
        mockActivity = mock()
        mockCredentialManager = mock()
        fakeSessionManager = FakeSessionManager(mockContext)

        DcadmConfig.setWebClientId("test-web-client-id.apps.googleusercontent.com")
    }

    @Test
    fun testNonceHashing_knownVectors() {
        // Known test vector 1: "hello"
        val expectedHelloHash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(expectedHelloHash, GoogleAuthManager.hashNonce("hello"))

        // Known test vector 2: empty string ""
        val expectedEmptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expectedEmptyHash, GoogleAuthManager.hashNonce(""))

        // Random nonce format: exactly 64 lowercase hex chars
        val randomNonce = "backend-challenge-9876543210-xyz"
        val hash = GoogleAuthManager.hashNonce(randomNonce)
        assertEquals(64, hash.length)
        assertTrue("Hashed nonce should be lowercase hex", hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun testNonceHashing_passedToGoogleOptionWhenProvided() = runBlocking {
        val rawNonce = "my_secure_client_nonce_xyz"
        val expectedHashedNonce = GoogleAuthManager.hashNonce(rawNonce)

        val mockUser = mock<FirebaseUser>()
        val dummyCredential = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenReturn(GetCredentialResponse(dummyCredential))

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { mockUser },
            credentialExtractor = { Pair("test@example.com", "test.id.token") }
        )

        val result = authManager.signInWithIdToken(mockActivity, rawNonce = rawNonce)
        assertTrue(result.isSuccess)

        // Verify request option captured the hashed nonce
        val requestCaptor = argumentCaptor<GetCredentialRequest>()
        verify(mockCredentialManager).getCredential(any<Context>(), requestCaptor.capture())

        val googleIdOption = requestCaptor.firstValue.credentialOptions.firstOrNull() as? GetGoogleIdOption
        assertNotNull(googleIdOption)
        assertEquals(expectedHashedNonce, googleIdOption?.nonce)
    }

    @Test
    fun testNonceHashing_nullWhenRawNonceNotProvided() = runBlocking {
        val mockUser = mock<FirebaseUser>()
        val dummyCredential = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenReturn(GetCredentialResponse(dummyCredential))

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { mockUser },
            credentialExtractor = { Pair("test@example.com", "test.id.token") }
        )

        val result = authManager.signInWithIdToken(mockActivity, rawNonce = null)
        assertTrue(result.isSuccess)

        val requestCaptor = argumentCaptor<GetCredentialRequest>()
        verify(mockCredentialManager).getCredential(any<Context>(), requestCaptor.capture())

        val googleIdOption = requestCaptor.firstValue.credentialOptions.firstOrNull() as? GetGoogleIdOption
        assertNotNull(googleIdOption)
        assertNull("Nonce should be null when rawNonce is omitted", googleIdOption?.nonce)
    }

    @Test
    fun testSignInWithIdToken_success() = runBlocking {
        val testEmail = "developer@example.com"
        val testIdToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEyMzQ1In0.payload.signature"
        val mockUser = mock<FirebaseUser>()
        whenever(mockUser.uid).thenReturn("firebase-uid-999")
        whenever(mockUser.email).thenReturn(testEmail)

        val dummyCredential = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenReturn(GetCredentialResponse(dummyCredential))

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { token ->
                assertEquals(testIdToken, token)
                mockUser
            },
            credentialExtractor = { Pair(testEmail, testIdToken) }
        )

        val result = authManager.signInWithIdToken(mockActivity, rawNonce = "nonce123")

        assertTrue("Expected signInWithIdToken to succeed", result.isSuccess)
        val signInResult = result.getOrThrow()

        // 1. Check returned GoogleSignInResult
        assertEquals(mockUser, signInResult.user)
        assertEquals(testIdToken, signInResult.idToken)

        // 2. Security check: User email saved to session, but ID token is NEVER stored in SessionManager
        assertEquals(testEmail, fakeSessionManager.savedEmail)
        assertNull("ID Token must never be stored in SessionManager or on disk", fakeSessionManager.getDriveToken())

        // 3. Security check: toString() masks the idToken to prevent accidental log leaks
        val toStringOutput = signInResult.toString()
        assertTrue("toString() should mask token", toStringOutput.contains("idToken=[PROTECTED]"))
        assertFalse("toString() must not expose the raw idToken", toStringOutput.contains(testIdToken))
    }

    @Test
    fun testSignInWithIdToken_userCancel() = runBlocking {
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenAnswer { throw GetCredentialCancellationException("User cancelled the prompt") }

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { null },
            credentialExtractor = { null }
        )

        val result = authManager.signInWithIdToken(mockActivity, rawNonce = null)

        assertTrue("Expected failure on cancellation", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is GetCredentialCancellationException)
        assertEquals("User cancelled the prompt", exception?.message)

        // Ensure session manager is not touched
        assertNull(fakeSessionManager.savedEmail)
    }

    @Test
    fun testSignInWithIdToken_noCredentialException() = runBlocking {
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenAnswer { throw NoCredentialException("No Google credentials found") }

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { null },
            credentialExtractor = { null }
        )

        val result = authManager.signInWithIdToken(mockActivity)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoCredentialException)
        assertEquals("No Google credentials found", result.exceptionOrNull()?.message)
    }

    @Test
    fun testSignInWithIdToken_unexpectedCredentialType() = runBlocking {
        val unknownCredential = CustomCredential("com.example.unknown.CREDENTIAL_TYPE", Bundle())
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenReturn(GetCredentialResponse(unknownCredential))

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { null },
            credentialExtractor = { null }
        )

        val result = authManager.signInWithIdToken(mockActivity)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("Expected error to mention unexpected credential type, got: $message",
            message.contains("Unexpected credential type: com.example.unknown.CREDENTIAL_TYPE"))
    }

    @Test
    fun testSignInWithIdToken_firebaseAuthFailed() = runBlocking {
        val dummyCredential = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenReturn(GetCredentialResponse(dummyCredential))

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { null }, // Firebase returns null
            credentialExtractor = { Pair("test@example.com", "dummy-token") }
        )

        val result = authManager.signInWithIdToken(mockActivity)

        assertTrue(result.isFailure)
        assertEquals("Firebase auth failed.", result.exceptionOrNull()?.message)
    }

    @Test
    fun testExistingSignIn_backwardCompatibility() = runBlocking {
        val testEmail = "legacy@example.com"
        val testIdToken = "legacy.id.token.jwt"
        val mockUser = mock<FirebaseUser>()
        whenever(mockUser.uid).thenReturn("legacy-uid")

        val dummyCredential = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
        whenever(mockCredentialManager.getCredential(any<Context>(), any<GetCredentialRequest>()))
            .thenReturn(GetCredentialResponse(dummyCredential))

        val authManager = GoogleAuthManager(
            context = mockContext,
            credentialManager = mockCredentialManager,
            sessionManager = fakeSessionManager,
            firebaseAuthHandler = { mockUser },
            credentialExtractor = { Pair(testEmail, testIdToken) }
        )

        // Existing signIn(activity) signature returning Result<FirebaseUser>
        val result: Result<FirebaseUser> = authManager.signIn(mockActivity)

        assertTrue(result.isSuccess)
        assertEquals(mockUser, result.getOrThrow())
        assertEquals(testEmail, fakeSessionManager.savedEmail)
    }
}
