package com.gadware.dcadm

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.room.RoomDatabase

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
    private var databaseName: String = "app_db"
    private var appName: String = "App"
    private var webClientId: String = ""
    private var databaseClass: Class<out RoomDatabase>? = null
    private var homeActivityClassName: String? = null
    private var termsAndPrivacyUrl: String? = null
    private var appLogoResId: Int? = null
    private var firestoreUserCollectionName: String = "users"

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
            onDatabaseReopenNeeded = DatabaseLifecycleCallback { onDatabaseReopenNeeded() }
        )
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
     * Invokes the registered callback when the database has been closed and needs reopening.
     */
    fun onDatabaseReopenNeeded() {
        databaseLifecycleCallback?.onDatabaseReopenNeeded()
    }

    /**
     * Closes the host application's database if possible.
     */
    fun closeDatabase(context: Context) {
        databaseClass?.let {
            try {
                val method = it.getMethod("getDatabase", Context::class.java)
                val db = method.invoke(null, context) as? RoomDatabase
                db?.close()
            } catch (e: Exception) {
                // Fallback or ignore
            }
        }
    }
}
