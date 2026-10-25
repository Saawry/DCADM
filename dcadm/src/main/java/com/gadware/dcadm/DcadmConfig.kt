package com.gadware.dcadm

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.utils.DcadmLog
import com.gadware.dcadm.worker.BackupWorker
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Interface for receiving database lifecycle events from Dcadm.
 */
fun interface DatabaseLifecycleCallback {
    /**
     * Called when the database was closed during backup or restore operations and needs to be reopened.
     * The host app should reset its cached singleton instance (or invoke its database builder factory again)
     * so subsequent calls to `getDatabase(context)` return a live, open RoomDatabase instance instead of the closed one.
     */
    fun onDatabaseReopenNeeded()
}

/**
 * Configuration for the Dcadm library.
 * Host apps must initialize this to provide database information and lifecycle callbacks.
 */
object DcadmConfig {
    private var applicationContext: Context? = null
    private var databaseName: String = "app_db"
    private var appName: String = "App"
    private var webClientId: String = ""
    private var databaseClass: Class<out RoomDatabase>? = null
    private var homeActivityClassName: String? = null
    private var termsAndPrivacyUrl: String? = null
    private var appLogoResId: Int? = null
    private var firestoreUserCollectionName: String = "users"
    private var firestoreReportsCollectionName: String = "Reports"
    private var reportProblemCategories: List<String> = listOf(
        "Drive backup",
        "Restore",
        "Authentication",
        "Account & Profile",
        "App Performance",
        "Other"
    )
    private var crashlyticsEnabled: Boolean = true

    // Optional Publisher / Organization Branding for Login Footer
    private var showBrandingFooter: Boolean = false
    private var companyName: String? = null
    private var companyUrl: String? = null
    private var companyLogoResId: Int? = null

    private var databaseLifecycleCallback: DatabaseLifecycleCallback? = null

    /**
     * Initialize the library with host application parameters.
     *
     * @param dbName Name of the Room SQLite database file.
     * @param hostAppName Name of the host application to display in titles and dialogs.
     * @param clientId Google OAuth 2.0 Web Client ID for authentication.
     * @param dbClass RoomDatabase subclass.
     * @param homeActivityClassName Fully qualified class name of the host app's home Activity to launch after login.
     * @param termsAndPrivacyUrl URL of the host app's Terms & Conditions / Privacy Policy web page.
     * @param appLogoResId Optional custom logo drawable resource ID. If null, the library dynamically loads the host app's launcher icon.
     * @param firestoreCollection Optional Firestore collection name for user profiles (defaults to "users").
     * @param context Optional Application or Activity context to immediately sync routine backup and background jobs.
     * @param showBranding Optional flag to display the developer/publisher ribbon on the login screen.
     * @param companyName Optional publisher/developer company name for branding ribbon.
     * @param companyUrl Optional publisher/developer website URL for branding ribbon.
     * @param companyLogoResId Optional publisher/developer logo drawable resource ID.
     * @param onDatabaseReopenNeeded Callback invoked when backup/restore operations finish closing the database.
     */
    fun init(
        dbName: String,
        hostAppName: String,
        clientId: String,
        dbClass: Class<out RoomDatabase>,
        homeActivityClassName: String? = null,
        termsAndPrivacyUrl: String? = null,
        @DrawableRes appLogoResId: Int? = null,
        firestoreCollection: String = "users",
        showBranding: Boolean = false,
        companyName: String? = null,
        companyUrl: String? = null,
        @DrawableRes companyLogoResId: Int? = null,
        context: Context? = null,
        onDatabaseReopenNeeded: DatabaseLifecycleCallback? = null
    ) {
        this.databaseName = dbName
        this.appName = hostAppName
        this.webClientId = clientId
        this.databaseClass = dbClass
        this.homeActivityClassName = homeActivityClassName
        this.termsAndPrivacyUrl = termsAndPrivacyUrl
        this.appLogoResId = appLogoResId
        this.firestoreUserCollectionName = firestoreCollection
        this.showBrandingFooter = showBranding
        this.companyName = companyName
        this.companyUrl = companyUrl
        this.companyLogoResId = companyLogoResId
        this.databaseLifecycleCallback = onDatabaseReopenNeeded

        if (context != null) {
            setApplicationContext(context)
            syncRoutineBackupSchedule(context)
        } else {
            applicationContext?.let { syncRoutineBackupSchedule(it) }
        }

        try {
            if (crashlyticsEnabled) {
                val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                crashlytics.setCustomKey("host_app_name", hostAppName)
                crashlytics.setCustomKey("dcadm_db_name", dbName)
                crashlytics.setCustomKey("dcadm_version", "1.5.1")
            }
        } catch (ignored: Exception) {}

        try {
            RemoteConfigManager.init()
            RemoteConfigManager.fetch { /* silent background fetch */ }
        } catch (ignored: Exception) {}
    }

    /**
     * Convenience overload with lambda callback.
     */
    fun init(
        dbName: String,
        hostAppName: String,
        clientId: String,
        dbClass: Class<out RoomDatabase>,
        homeActivityClassName: String? = null,
        termsAndPrivacyUrl: String? = null,
        @DrawableRes appLogoResId: Int? = null,
        firestoreCollection: String = "users",
        showBranding: Boolean = false,
        companyName: String? = null,
        companyUrl: String? = null,
        @DrawableRes companyLogoResId: Int? = null,
        context: Context? = null,
        onDatabaseReopenNeeded: () -> Unit
    ) {
        init(
            dbName = dbName,
            hostAppName = hostAppName,
            clientId = clientId,
            dbClass = dbClass,
            homeActivityClassName = homeActivityClassName,
            termsAndPrivacyUrl = termsAndPrivacyUrl,
            appLogoResId = appLogoResId,
            firestoreCollection = firestoreCollection,
            showBranding = showBranding,
            companyName = companyName,
            companyUrl = companyUrl,
            companyLogoResId = companyLogoResId,
            context = context,
            onDatabaseReopenNeeded = DatabaseLifecycleCallback { onDatabaseReopenNeeded() }
        )
    }

    fun setApplicationContext(context: Context) {
        this.applicationContext = context.applicationContext
    }

    fun getApplicationContext(): Context? = applicationContext

    /**
     * Synchronizes and verifies the routine backup schedule with Android WorkManager.
     * Host applications can call this at any time (e.g. during Application.onCreate()),
     * and it is also invoked automatically on application launch via ContentProvider.
     *
     * If the user configured routine backup to "Daily", "Weekly", or "Monthly",
     * it ensures the unique PeriodicWorkRequest is active with network constraints.
     * If configured to "Never", any active routine backup work is cancelled.
     *
     * @param context Application or Activity context.
     */
    fun syncRoutineBackupSchedule(context: Context) {
        val appContext = context.applicationContext
        setApplicationContext(appContext)

        val sessionManager = SessionManager(appContext)
        val config = sessionManager.getRoutineBackupConfig()
        val workName = "routine_backup"
        val workManager = WorkManager.getInstance(appContext)

        if (config == "Never" || config.isBlank()) {
            workManager.cancelUniqueWork(workName)
            DcadmLog.d("DcadmConfig", "syncRoutineBackupSchedule: routine backup cancelled (config is $config).")
            return
        }

        val driveEmail = sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserEmail()?.takeIf { it.isNotBlank() }
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email

        val inputDataBuilder = Data.Builder()
        if (!driveEmail.isNullOrBlank()) {
            inputDataBuilder.putString("DRIVE_EMAIL", driveEmail)
        }

        val repeatInterval = when (config) {
            "Daily" -> 1L to TimeUnit.DAYS
            "Weekly" -> 7L to TimeUnit.DAYS
            "Monthly" -> 30L to TimeUnit.DAYS
            else -> return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatInterval.first, repeatInterval.second
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(inputDataBuilder.build())
            .addTag("routine_backup")
            .build()

        workManager.enqueueUniquePeriodicWork(
            workName,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        DcadmLog.d("DcadmConfig", "syncRoutineBackupSchedule: routine backup verified and scheduled ($config for $driveEmail).")
    }

    /**
     * Immediately triggers an on-demand execution of [BackupWorker] via WorkManager
     * using the saved routine backup configuration and credentials.
     * This allows host apps and developers to verify that routine background backups
     * execute successfully in their environment without waiting for periodic intervals.
     *
     * @param context Application or Activity context.
     * @return UUID of the enqueued one-time WorkRequest to allow status observation.
     */
    fun triggerRoutineBackupNow(context: Context): UUID {
        val appContext = context.applicationContext
        val sessionManager = SessionManager(appContext)

        val driveEmail = sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserEmail()?.takeIf { it.isNotBlank() }
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email

        val inputDataBuilder = Data.Builder()
        if (!driveEmail.isNullOrBlank()) {
            inputDataBuilder.putString("DRIVE_EMAIL", driveEmail)
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(inputDataBuilder.build())
            .addTag("routine_backup_immediate")
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "routine_backup_immediate",
            ExistingWorkPolicy.REPLACE,
            request
        )
        DcadmLog.d("DcadmConfig", "triggerRoutineBackupNow: dispatched immediate BackupWorker with id ${request.id}")
        return request.id
    }

    fun getDatabaseName() = databaseName
    fun getAppName() = appName
    fun getWebClientId() = webClientId
    fun getDatabaseClass() = databaseClass
    fun getHomeActivityClassName() = homeActivityClassName
    fun getTermsAndPrivacyUrl() = termsAndPrivacyUrl
    fun getAppLogoResId() = appLogoResId
    fun getFirestoreUserCollectionName() = firestoreUserCollectionName

    fun isBrandingFooterEnabled() = showBrandingFooter
    fun getCompanyName() = companyName
    fun getCompanyUrl() = companyUrl
    fun getCompanyLogoResId() = companyLogoResId

    private var reportProblemActivityClassName: String? = null
    private var supportEmail: String? = null

    /**
     * Set the class name of the host app's problem report Activity.
     */
    fun setReportProblemActivityClassName(className: String?) {
        this.reportProblemActivityClassName = className
    }

    fun getReportProblemActivityClassName() = reportProblemActivityClassName

    /**
     * Set support contact email for error diagnostics and reporting.
     */
    fun setSupportEmail(email: String?) {
        this.supportEmail = email
    }

    fun getSupportEmail() = supportEmail

    /**
     * Opens the AdvanceOptions Activity.
     */
    fun openAdvanceOptions(context: Context) {
        val intent = android.content.Intent(context, com.gadware.dcadm.ui.advance.AdvanceOptionsActivity::class.java)
        if (context !is android.app.Activity) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun getFirestoreReportsCollectionName() = firestoreReportsCollectionName
    fun setFirestoreReportsCollectionName(collectionName: String) {
        this.firestoreReportsCollectionName = collectionName
    }

    fun getReportProblemCategories() = reportProblemCategories
    fun setReportProblemCategories(categories: List<String>) {
        this.reportProblemCategories = categories
    }

    /**
     * Opens the Report Problem Activity.
     */
    fun openReportProblem(context: Context) {
        val intent = android.content.Intent(context, com.gadware.dcadm.ui.report.ReportProblemActivity::class.java)
        if (context !is android.app.Activity) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens the Profile & Account Management Activity.
     */
    fun openProfile(context: Context) {
        val intent = android.content.Intent(context, com.gadware.dcadm.ui.profile.ProfileActivity::class.java)
        if (context !is android.app.Activity) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens the Backup & Restore Settings Activity.
     */
    fun openBackup(context: Context, userType: String = "") {
        val intent = android.content.Intent(context, com.gadware.dcadm.ui.backup.BackupActivity::class.java).apply {
            if (userType.isNotBlank()) {
                putExtra("UserType", userType)
            }
        }
        if (context !is android.app.Activity) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens the Login Activity for Google Sign-In and account authentication.
     */
    fun openLogin(context: Context) {
        val intent = android.content.Intent(context, com.gadware.dcadm.ui.login.LoginActivity::class.java)
        if (context !is android.app.Activity) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens the Registration Activity for profile onboarding.
     */
    fun openRegistration(context: Context, email: String = "") {
        val intent = android.content.Intent(context, com.gadware.dcadm.ui.registration.RegistrationActivity::class.java).apply {
            if (email.isNotBlank()) {
                putExtra("email", email)
            }
        }
        if (context !is android.app.Activity) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // Firebase Crashlytics Configuration & Diagnostics
    fun isCrashlyticsEnabled(): Boolean = crashlyticsEnabled

    fun setCrashlyticsEnabled(enabled: Boolean) {
        this.crashlyticsEnabled = enabled
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        } catch (ignored: Exception) {}
    }

    fun recordCrashlyticsException(throwable: Throwable) {
        try {
            if (crashlyticsEnabled) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(throwable)
            }
        } catch (ignored: Exception) {}
    }

    fun logCrashlyticsBreadcrumb(message: String) {
        try {
            if (crashlyticsEnabled) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log(message)
            }
        } catch (ignored: Exception) {}
    }

    fun setCrashlyticsUserId(userId: String) {
        try {
            if (crashlyticsEnabled) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(userId)
            }
        } catch (ignored: Exception) {}
    }

    fun setCrashlyticsCustomKey(key: String, value: String) {
        try {
            if (crashlyticsEnabled) {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCustomKey(key, value)
            }
        } catch (ignored: Exception) {}
    }

    // Google Play In-App Review Helpers
    /**
     * Directly launches the Google Play In-App Review flow.
     */
    fun launchInAppReview(
        activity: android.app.Activity,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        com.gadware.dcadm.review.DcadmReviewManager.launchReview(activity, onComplete)
    }

    /**
     * Smart In-App Review trigger checking install age, prompt intervals, and user milestones.
     */
    fun checkAndLaunchInAppReview(
        activity: android.app.Activity,
        minDaysSinceInstall: Int = 2,
        minDaysBetweenPrompts: Int = 14,
        minActions: Int = 3,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        return com.gadware.dcadm.review.DcadmReviewManager.checkAndLaunchReview(
            activity,
            minDaysSinceInstall,
            minDaysBetweenPrompts,
            minActions,
            onComplete
        )
    }

    /**
     * Increments the milestone interaction counter for smart in-app review triggering.
     */
    fun recordReviewMilestoneAction(context: Context) {
        com.gadware.dcadm.review.DcadmReviewManager.recordAction(context)
    }

    /**
     * Opens the Google Play Store listing page directly.
     */
    fun openPlayStore(context: Context, customPackageName: String? = null) {
        com.gadware.dcadm.review.DcadmReviewManager.openPlayStore(context, customPackageName)
    }

    /**
     * Enables or disables test mode using FakeReviewManager for testing on debug builds and emulators.
     */
    fun setInAppReviewTestMode(enabled: Boolean) {
        com.gadware.dcadm.review.DcadmReviewManager.setTestMode(enabled)
    }

    // Google Play In-App Update Helpers
    /**
     * Checks for Google Play in-app updates and initiates the update flow if available.
     * Automatically registers lifecycle hooks to handle onResume state and restart prompts.
     *
     * @param activity Foreground activity to host the update flow.
     * @param mode Update mode: [com.gadware.dcadm.update.DcadmUpdateMode.FLEXIBLE] (default) or [com.gadware.dcadm.update.DcadmUpdateMode.IMMEDIATE].
     * @param onUpdateDownloaded Optional custom callback when a flexible update finishes downloading.
     *                           If null, a default Material Snackbar with "Restart" action will be shown.
     */
    fun checkForUpdates(
        activity: android.app.Activity,
        mode: com.gadware.dcadm.update.DcadmUpdateMode = com.gadware.dcadm.update.DcadmUpdateMode.FLEXIBLE,
        onUpdateDownloaded: (() -> Unit)? = null
    ) {
        com.gadware.dcadm.update.DcadmUpdateManager.checkForUpdates(activity, mode, onUpdateDownloaded)
    }

    /**
     * Completes a flexible in-app update by restarting the application.
     */
    fun completeUpdate() {
        com.gadware.dcadm.update.DcadmUpdateManager.completeUpdate()
    }

    /**
     * Enables or disables test mode using FakeAppUpdateManager for testing on debug builds and emulators.
     */
    fun setInAppUpdateTestMode(enabled: Boolean, isUpdateAvailable: Boolean = true, isImmediate: Boolean = false) {
        com.gadware.dcadm.update.DcadmUpdateManager.setTestMode(enabled, isUpdateAvailable, isImmediate)
    }

    /**
     * Loads the app logo into the provided ImageView.
     * If a custom logo was provided via [appLogoResId], it is used;
     * otherwise, it dynamically loads the host application's launcher icon.
     */
    fun loadAppLogo(context: Context, imageView: ImageView) {
        val customLogo = appLogoResId
        if (customLogo != null && customLogo != 0) {
            imageView.setImageResource(customLogo)
        } else {
            try {
                val icon: Drawable = context.packageManager.getApplicationIcon(context.packageName)
                imageView.setImageDrawable(icon)
            } catch (e: Exception) {
                // Fallback or ignore
            }
        }
    }

    /**
     * Obtains the host application's RoomDatabase instance safely across all Kotlin/Java architectures.
     * Tries:
     * 1. Static methods (e.g. @JvmStatic fun getDatabase(context)) on databaseClass
     * 2. Kotlin Companion object methods (e.g. Companion.getDatabase(context))
     * 3. Fallback: Room.databaseBuilder(...)
     */
    fun getDatabase(context: Context): RoomDatabase {
        val dbClass = databaseClass ?: throw IllegalStateException("Database class must be configured via DcadmConfig.init()")
        val appContext = context.applicationContext

        // 1. Try static methods on dbClass
        for (methodName in listOf("getDatabase", "getInstance", "create", "get")) {
            try {
                val method = dbClass.getMethod(methodName, Context::class.java)
                val db = method.invoke(null, appContext) as? RoomDatabase
                if (db != null) return db
            } catch (ignored: NoSuchMethodException) {
            } catch (e: Exception) {
                // Ignore invocation error and continue fallback
            }
        }

        // 2. Try Kotlin companion object methods (standard Kotlin companion without @JvmStatic)
        try {
            val companionField = dbClass.getField("Companion")
            val companionObj = companionField.get(null)
            if (companionObj != null) {
                val companionClass = companionObj.javaClass
                for (methodName in listOf("getDatabase", "getInstance", "create", "get")) {
                    try {
                        val method = companionClass.getMethod(methodName, Context::class.java)
                        val db = method.invoke(companionObj, appContext) as? RoomDatabase
                        if (db != null) return db
                    } catch (ignored: NoSuchMethodException) {
                    } catch (e: Exception) {
                        // Ignore invocation error and continue fallback
                    }
                }
            }
        } catch (ignored: Exception) {}

        // 3. Fallback: Room.databaseBuilder
        return Room.databaseBuilder(appContext, dbClass, getDatabaseName()).build()
    }

    /**
     * Invokes the registered callback when the database has been closed and needs reopening.
     */
    fun onDatabaseReopenNeeded() {
        databaseLifecycleCallback?.onDatabaseReopenNeeded()
    }

    /**
     * Closes the host application's database if possible.
     */
    fun closeDatabase(context: Context) {
        try {
            getDatabase(context).close()
        } catch (e: Exception) {
            // Fallback or ignore
        }
    }
}

