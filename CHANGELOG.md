# Changelog

All notable changes to the `dcadm` Android library will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.6.0] - 2026-10-31

### Added
- **`GoogleAuthManager.signInWithIdToken(activity, rawNonce)`**: New public suspend API returning `Result<GoogleSignInResult>` containing both the authenticated `FirebaseUser` and the Google `idToken`.
- **`GoogleSignInResult` data class**: Encapsulates `(user: FirebaseUser, idToken: String)`. Masked `toString()` to prevent accidental token exposure in logs (`idToken=[PROTECTED]`).
- **Cryptographic Nonce Hashing**:
  - Automatic SHA-256 hex digest calculation (`hashNonce`) sent to Google Identity Services via `GetGoogleIdOption.Builder.setNonce`.
  - Enables end-to-end replay attack protection: caller forwards unhashed `rawNonce` to backend, which validates against the `nonce` claim in the verified Google ID token.
  - Exposed helper `GoogleAuthManager.hashNonce(rawNonce: String): String`.
- **Comprehensive Unit Tests**:
  - `testSignInWithIdToken_success`: Validates successful authentication, token retrieval, user email caching, and string masking.
  - `testSignInWithIdToken_userCancel`: Validates `GetCredentialCancellationException` handling and clean session state.
  - `testSignInWithIdToken_noCredentialException`: Validates handling of `NoCredentialException`.
  - `testSignInWithIdToken_unexpectedCredentialType`: Validates rejection of non-Google credentials.
  - `testSignInWithIdToken_firebaseAuthFailed`: Validates error propagation if Firebase handshake fails.
  - `testNonceHashing_knownVectors`: Verifies SHA-256 test vectors (including `"hello"` and empty string).
  - `testNonceHashing_passedToGoogleOptionWhenProvided`: Verifies hashed nonce is attached to Credential Manager request.
  - `testNonceHashing_nullWhenRawNonceNotProvided`: Verifies nonce is omitted when not provided.
  - `testExistingSignIn_backwardCompatibility`: Verifies existing `signIn(activity)` signature and behavior.

### Security
- The Google ID token is strictly intended for one-time backend verification/exchange and expires in ~1 hour (~3600 seconds).
- The ID token is **never** logged to logcat/analytics.
- The ID token is **never** stored in `SessionManager`, SharedPreferences, SQLite databases, or local disk storage.

### Fixed
- Corrected documentation referencing Cloud Firestore user profiles to the real case-sensitive collection path: `/users/{uid}` (defaulting to `"users"` lowercase, or as configured in `DcadmConfig.init(..., firestoreCollection = ...)`).

---

## [1.5.1] - 2026-10-25

### Added
- `DcadmInitProvider` startup content provider for zero-boilerplate WorkManager schedule initialization and preservation.
- `DcadmConfig.triggerRoutineBackupNow(context): UUID` diagnostic API for on-demand background worker testing.
- `DcadmConfig.syncRoutineBackupSchedule(context)` explicit schedule synchronization.
- Transitive `androidx.work:work-runtime-ktx` exposure via `api()`.

### Changed
- `BackupWorker` retries with exponential backoff on transient failures to prevent WorkManager job cancellation.
- Silent OAuth token renewal via `GoogleAuthUtil.getToken(...)` fallback on `Dispatchers.IO`.
- Android 14+ `dataSync` foreground service compliance.

### Fixed
- Fixed `BackupActivity` spinner item selection reset bug.

---

## [1.5.0] - 2026-10-15

### Added
- Individual Database Export and Import cards directly in `BackupActivity`, conditionally displayed when `profile.export == true`.
- Library-level `FileProvider` (`${applicationId}.fileprovider`) and `dcadm_file_paths.xml`.

### Changed
- Cleaned up `ReportProblemActivity` UI (removed redundant country badge).

---

## [1.4.0] - 2026-09-30

### Added
- Google Play In-App Review module with quota throttling and `FakeReviewManager` test mode.
- Google Play In-App Update module with flexible/immediate update modes and `FakeAppUpdateManager`.
- Built-in `ReportProblemActivity` workflow saving to Cloud Firestore (`/Reports`).
- Firebase Crashlytics non-fatal error reporting and breadcrumb logging.

---

## [1.3.1] - 2026-09-10

### Fixed
- Version bump and POM configuration for JitPack release.

---

## [1.3.0] - 2026-09-01

### Added
- FCM Push notifications and automatic topic subscriptions (`all`, `updates`, `paid_users`/`free_users`, `country_<code >`).
- Editable profile and avatar management in `ProfileActivity`.
- Advance options screen (`AdvanceOptionsActivity`) for database integrity checks and diagnostics.

---

## [1.2.3] - 2026-08-28

### Added
- App module completion and sample implementation.
- Database reflection fixes and silent Drive authentication.

---

## [1.2.0] - 2026-08-23

### Added
- Initial modular release of `dcadm`: Google Sign-In, Google Drive AppData backup/restore, Room database lifecycle hooks.
