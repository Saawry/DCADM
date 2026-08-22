# Dcadm - Android Google Drive Backup & Cloud Auth Module

`dcadm` is a lightweight, drop-in Android library that handles **Google Sign-In (Credential Manager + Firebase Auth)**, **Google Drive AppData Backup & Restore (`DRIVE_APPDATA`)**, **Firestore User Profile Management**, and **Periodic Background Sync via WorkManager** for Room SQLite databases.

---

## 🌟 Key Features

- 🔐 **Modern Authentication**: Seamless Google Sign-In using Android Credential Manager + Firebase Auth handshake.
- ☁️ **Google Drive AppData Backup**: Backs up and restores your Room SQLite database to the user's hidden Google Drive `appDataFolder`.
- 🔄 **Silent Background Backups**: Daily/Weekly/Monthly automated backups via `WorkManager` with silent OAuth token renewal.
- 📋 **Firestore User Registration**: Checks for existing users and collects business/profile details for new users in Cloud Firestore.
- 🎨 **100% Theme & Asset Inheritance**: Inherits your application's Material theme and launcher icon automatically.
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
    implementation("com.github.Saawry:dcadm:1.1.0")
    
    // Google Services plugin is required for Firebase
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

1. **Firebase Console**:
   - Enable **Google Sign-In** under **Authentication -> Sign-in method**.
   - Enable **Cloud Firestore**.
   - Add your debug and release **SHA-1 and SHA-256 fingerprints** in Firebase Project Settings.
   - Download and place `google-services.json` inside your `app/` folder.
2. **Google Cloud Console**:
   - In the same Google Cloud project associated with your Firebase app, go to **APIs & Services -> Library**.
   - Search for **Google Drive API** and click **Enable**.
   - (Optional for testing) Add your test email accounts under **OAuth consent screen -> Test users** while your app is in testing mode.

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

### 3. Launching Login & Backup Screens

#### A. Starting the Onboarding / Login Flow
Launch `LoginActivity` to start the Google Sign-In, immediate Drive scope authorization, and Firestore registration check:

```kotlin
val intent = Intent(this, com.gadware.dcadm.ui.login.LoginActivity::class.java)
startActivity(intent)
```

#### B. Opening the Backup & Restore Settings Screen
Launch `BackupActivity` to view profile information, trigger manual backup/restore, and configure automatic backup routines (Daily, Weekly, Monthly):

```kotlin
val intent = Intent(this, com.gadware.dcadm.ui.backup.BackupActivity::class.java)
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

---

## 🛡 ProGuard / R8 Rules

If your app enables code shrinking (`isMinifyEnabled = true`), the library packages its own consumers rules automatically. If needed, ensure Google Drive and Room classes are kept:

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
