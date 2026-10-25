package com.gadware.drivebackup

import android.app.Application
import com.gadware.dcadm.DcadmConfig
import com.gadware.drivebackup.room.AppDatabase

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initializeDcadm()
    }

    private fun initializeDcadm() {
        // Create push notification channel
        com.gadware.dcadm.notification.DcadmNotificationManager.createNotificationChannel(this)

        // Register push notification reception listener
        com.gadware.dcadm.notification.DcadmNotificationManager.setNotificationReceivedListener { title, body, data ->
            android.util.Log.d("DriveBackupApp", "Push Notification Received: $title - $body | data: $data")
        }

        // Initialize DCADM core configuration with branding & assets
        DcadmConfig.init(
            context = this,
            dbName = AppDatabase.DATABASE_NAME,
            hostAppName = getString(R.string.app_name),
            clientId = getString(R.string.web_client_id),
            dbClass = AppDatabase::class.java,
            homeActivityClassName = MainActivity::class.java.name,
            termsAndPrivacyUrl = "https://gadwareapps.web.app/terms_condition_&_privacy_policy/store_manager/",
            appLogoResId = R.mipmap.ic_launcher,
            showBranding = true,
            companyName = "Gadware Technologies",
            companyUrl = "https://gadwareapps.web.app",
            companyLogoResId = R.mipmap.ic_launcher,
            onDatabaseReopenNeeded = {
                AppDatabase.resetDatabase()
            }
        )

        // Problem Reporting & Support
        DcadmConfig.setSupportEmail("support@gadware.com")
        DcadmConfig.setFirestoreReportsCollectionName("Reports")
        DcadmConfig.setReportProblemCategories(
            listOf(
                "Drive backup",
                "Restore",
                "Database Export / Import",
                "Authentication",
                "Account & Profile",
                "App Performance",
                "Feature Request",
                "Other"
            )
        )
    }
}