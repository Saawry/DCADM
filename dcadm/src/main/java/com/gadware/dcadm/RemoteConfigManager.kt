package com.gadware.dcadm

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

object RemoteConfigManager {

    private val remoteConfig = Firebase.remoteConfig

    fun init() {
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600 // 1 hour (use 0 for testing)
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // Default values
        remoteConfig.setDefaultsAsync(
            mapOf(
                "latest_version_code" to 1,
                "min_required_version_code" to 1,
                "update_url" to "",
                "update_message" to "Please update the app",
                "gdbs_status" to "not_running",
                "support_url" to "",
            )
        )
    }

    fun fetch(onComplete: (Boolean) -> Unit) {
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener {
                onComplete(it.isSuccessful)
            }
    }

    fun getLatestVersion() = remoteConfig.getLong("latest_version_code").toInt()
    fun getMinRequiredVersion() = remoteConfig.getLong("min_required_version_code").toInt()
    fun getUpdateUrl() = remoteConfig.getString("update_url")
    fun getUpdateMessage() = remoteConfig.getString("update_message")
    fun getGDBSStatus() = remoteConfig.getString("gdbs_status")
    fun getSupportUrl() = remoteConfig.getString("support_url")
}