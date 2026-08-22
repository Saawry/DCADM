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
        DcadmConfig.init(
            dbName = AppDatabase.DATABASE_NAME,
            hostAppName = getString(R.string.app_name),
            clientId = getString(R.string.web_client_id),
            dbClass = AppDatabase::class.java,
            homeActivityClassName = MainActivity::class.java.name,
            termsAndPrivacyUrl = "https://gadwareapps.web.app/terms_condition_&_privacy_policy/store_manager/",
            onDatabaseReopenNeeded = {
                AppDatabase.resetDatabase()
            }
        )
    }
}