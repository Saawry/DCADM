# Dcadm - Android Google Drive Backup & Cloud Auth Module

`dcadm` is a lightweight, drop-in Android library that handles **Google Sign-In (Credential Manager + Firebase Auth)**, **Google Drive AppData Backup & Restore (`DRIVE_APPDATA`)**, **Local Database Export & Import**, **Firestore User Profile Management**, and **Periodic Background Sync via WorkManager** for Room SQLite databases.

---

## 🌟 Key Features

- 🔐 **Modern Authentication**: Seamless Google Sign-In using Android Credential Manager + Firebase Auth handshake.
- ☁️ **Google Drive AppData Backup**: Backs up and restores your Room SQLite database to the user's hidden Google Drive `appDataFolder`.
- 📁 **Offline Local Database Export & Import**:
  - Export database backups directly to device storage via Android Storage Access Framework (SAF).
  - Share backup archives directly to other apps (Email, WhatsApp, Drive, etc.) via Android `ACTION_SEND`.
  - Import and restore from `.zip` or raw SQLite `.db` files with safety overwrite confirmations.
- ⚙️ **Advance Options Activity (`AdvanceOptionsActivity`)**:
  - Dedicated screen for local backup export, import, sharing, database diagnostics, integrity checks (`PRAGMA integrity_check`), and problem reporting.
- 🔄 **Silent Background Backups**: Daily/Weekly/Monthly automated backups via `WorkManager` with silent OAuth token renewal.
- 📋 **Firestore User Registration**: Checks for existing users and collects business/profile details for new users in Cloud Firestore.
- 🎨 **100% Theme & Asset Inheritance**: Inherits your application's Material theme, color palette, and launcher icon automatically.
- 🔒 **Encrypted Local Session**: Encrypted SharedPreferences for user profiles, Drive auth tokens, and backup metadata.

---

## 📦 Installation

### Step 1: Add JitPack Repository

In your root `settings.gradle.kts` (or root `build.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 2: Add the Dependency

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Saawry:dcadm:1.2.3")
    
    // Room and Coroutines as used in your host app
}
```

Also make sure the Google Services plugin is applied:
```kotlin
plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}
```

---

## ⚙️ Google Cloud & Firebase Setup

### 1. Firebase Console Setup
1. **Create/Open Firebase Project**:
   - Go to [Firebase Console](https://console.firebase.google.com/) and create or select your project.
2. **Add Your Android App**:
   - Register your Android app with its exact **Package Name** (e.g. `com.example.myapp`).
   - Add your **SHA-1** and **SHA-256** certificate fingerprints (both Debug and Release).
     ```bash
     # Run in Android Studio terminal to find your SHA fingerprints:
     ./gradlew signingReport
     ```
3. **Enable Authentication**:
   - Navigate to **Build > Authentication > Sign-in method**.
   - Enable **Google** sign-in and save.
4. **Enable Cloud Firestore**:
   - Navigate to **Build > Firestore Database** and click **Create database**.
   - The library manages user registration in the `"users"` collection by default.
5. **Download `google-services.json`**:
   - Navigate to **Project Settings > General > Your apps**.
   - Download `google-services.json` and place it in your app module directory (`app/google-services.json`).

---

### 2. Google Cloud Console Setup
Firebase projects automatically have an identical underlying project in Google Cloud Console.

1. Open [Google Cloud Console](https://console.cloud.google.com/) and select the **same project**.
2. **Enable Google Drive API**:
   - Go to **APIs & Services > Library**.
   - Search for **Google Drive API** and click **Enable** (required for SQLite backup to the hidden `appDataFolder`).
3. **Configure OAuth Consent Screen**:
   - Go to **APIs & Services > OAuth consent screen**.
   - Select **External** user type.
   - Enter **App Name**, **User support email**, and **Developer contact email**.
   - Under **Scopes**, click **Add or remove scopes** and add:
     - `https://www.googleapis.com/auth/drive.appdata`
   - Under **Test Users** (if app publishing status is *Testing*), add the tester Google email addresses.

---

### 🔑 Which Key to Use Where?

| Key / Credential | Where to Find It | Where to Use It in App |
| :--- | :--- | :--- |
| **`google-services.json`** | Firebase Console &rarr; *Project Settings > General > Your Apps* | Place at root of app module:<br>`app/google-services.json` |
| **Web Client ID** (`clientId`) | **Auto-generated** by Firebase Google Services plugin as `@string/default_web_client_id`.<br>*(Also viewable in Firebase Auth &rarr; Sign-in method &rarr; Google &rarr; Web SDK config, or Google Cloud &rarr; Credentials &rarr; OAuth 2.0 Client IDs &rarr; Web client)* | Passed to `DcadmConfig.init(..., clientId = getString(R.string.default_web_client_id), ...)` in `Application.onCreate()`. |
| **SHA-1 / SHA-256 Fingerprints** | Run `./gradlew signingReport` in your project terminal. | Add under **Firebase Project Settings &rarr; Your apps &rarr; SHA certificate fingerprints**. |

---

## 🚀 Quick Start Guide

### 1. Update your `RoomDatabase` Class

Provide a singleton reset method so the database instance can be safely reopened after a backup or restore operation:

```kotlin
@Database(entities = [User::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    companion object {
        const val DATABASE_NAME = "my_app_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).build().also { INSTANCE = it }
            }
        }

        fun resetDatabase() {
            INSTANCE = null
        }
    }
}
```

---

### 2. Initialize in `Application.onCreate()`

Initialize `DcadmConfig` in your custom `Application` class:

```kotlin
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        DcadmConfig.init(
            dbName = AppDatabase.DATABASE_NAME,
            hostAppName = getString(R.string.app_name),
            clientId = getString(R.string.default_web_client_id), // Auto-generated from google-services.json
            dbClass = AppDatabase::class.java,
            homeActivityClassName = MainActivity::class.java.name, // Where to navigate after login/registration
            termsAndPrivacyUrl = "https://yourwebsite.com/privacy-policy", // Optional: terms URL
            appLogoResId = null, // Optional: custom drawable resource ID (defaults to host launcher icon)
            firestoreCollection = "users", // Optional: defaults to "users"
            onDatabaseReopenNeeded = {
                // Reset your Room database instance so next access opens a fresh connection
                AppDatabase.resetDatabase()
            }
        )

        // Optional: Configure Problem Reporting & Support
        DcadmConfig.setReportProblemActivityClassName("com.example.myapp.ReportProblemActivity")
        DcadmConfig.setSupportEmail("support@example.com")
    }
}
```

Make sure your `Application` class is registered in `app/src/main/AndroidManifest.xml`:
```xml
<application
    android:name=".MyApplication"
    android:theme="@style/Theme.MyApp"
    ...>
```

---

### 3. Launching Screens

#### A. Starting the Onboarding / Login Flow
Launch `LoginActivity` to start Google Sign-In, immediate Drive scope authorization, and Firestore registration check:

```kotlin
val intent = Intent(this, com.gadware.dcadm.ui.login.LoginActivity::class.java)
startActivity(intent)
```

#### B. Opening the Backup & Restore Settings Screen
Launch `BackupActivity` to view profile information, trigger manual Google Drive backup/restore, configure automatic routines, or navigate to Advance Options:

```kotlin
val intent = Intent(this, com.gadware.dcadm.ui.backup.BackupActivity::class.java)
startActivity(intent)
```

#### C. Opening Advance Options Directly
Launch `AdvanceOptionsActivity` directly for local database export, import, diagnostics, and problem reporting:

```kotlin
// Option 1: Via Helper
DcadmConfig.openAdvanceOptions(this)

// Option 2: Via Intent
val intent = Intent(this, com.gadware.dcadm.ui.advance.AdvanceOptionsActivity::class.java)
startActivity(intent)
```

---

## 🎨 Customization & Branding

`DcadmConfig.init(...)` provides complete control over your app's branding:

```kotlin
DcadmConfig.init(
    dbName = "my_db",
    hostAppName = "Store Manager",
    clientId = getString(R.string.default_web_client_id),
    dbClass = AppDatabase::class.java,
    homeActivityClassName = MainActivity::class.java.name,
    termsAndPrivacyUrl = "https://example.com/terms",
    
    // Custom App Logo (if omitted or null, dynamically uses app's launcher icon)
    appLogoResId = R.drawable.my_custom_logo,
    
    // Optional Developer / Publisher Ribbon on Login Screen
    showBranding = true,
    companyName = "My Studio Inc.",
    companyUrl = "mystudio.com",
    companyLogoResId = R.drawable.ic_company_logo,
    
    onDatabaseReopenNeeded = {
        AppDatabase.resetDatabase()
    }
)

// Configure optional support channels
DcadmConfig.setReportProblemActivityClassName("com.example.myapp.ReportProblemActivity")
DcadmConfig.setSupportEmail("support@example.com")
```

---

## 🛠 Advanced Programmatic Usage

### Accessing User Profile & Session Data

```kotlin
val sessionManager = SessionManager(context)

// Get cached user profile
val profile: UserProfile? = sessionManager.getUserProfile()
val userEmail: String? = sessionManager.getUserEmail()
val lastBackupDate: Long = sessionManager.getLastBackupDate()
val isRegistered: Boolean = sessionManager.getRegStatus()

// Silent sign-out
val authManager = GoogleAuthManager(context)
lifecycleScope.launch {
    authManager.signOut()
}
```

### Performing Local Database Export / Import Programmatically

```kotlin
val authManager = GoogleAuthManager(context)
val repository = BackupRepository(context, database, authManager)

lifecycleScope.launch {
    // Export to destination URI (Storage Access Framework)
    val exportResult = repository.exportDatabaseToUri(destinationUri)

    // Import from source URI
    val importResult = repository.importDatabaseFromUri(sourceUri)

    // Get database size & metrics
    val stats = repository.getDatabaseStats()
    Log.d("DB", "Size: ${stats.formattedSize}, Name: ${stats.dbName}")

    // Run integrity check
    val integrityResult = repository.performIntegrityCheck()
}
```

---

## 🛡 ProGuard / R8 Rules

If your app enables code shrinking (`isMinifyEnabled = true`), the library packages its own consumer rules automatically. If needed, ensure Google Drive and Room classes are kept:

```proguard
# Google Drive API & HTTP
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**

# Dcadm Models
-keep class com.gadware.dcadm.data.** { *; }
```

---

## 📄 License
MIT License
