package com.gadware.dcadm.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = getOrCreateEncryptedPrefs(context)
    private val gson = Gson()

    companion object {
        private const val LEGACY_PREFS_NAME = "dcadm_session_prefs"
        private const val ENCRYPTED_PREFS_NAME = "dcadm_session_prefs_encrypted"

        private fun getOrCreateEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                migrateLegacyPrefsIfNeeded(context, encryptedPrefs)
                encryptedPrefs
            } catch (e: Exception) {
                // Fallback to standard prefs if Keystore encryption fails on specific OEM/device states
                context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }

        private fun migrateLegacyPrefsIfNeeded(context: Context, encryptedPrefs: SharedPreferences) {
            try {
                val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val legacyAll = legacyPrefs.all
                if (legacyAll.isNotEmpty()) {
                    val editor = encryptedPrefs.edit()
                    for ((key, value) in legacyAll) {
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Long -> editor.putLong(key, value)
                            is Int -> editor.putInt(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                editor.putStringSet(key, value as? Set<String>)
                            }
                        }
                    }
                    editor.apply()
                    legacyPrefs.edit().clear().apply()
                }
            } catch (e: Exception) {
                // Ignore migration errors to avoid crashing session initialization
            }
        }
    }

    fun saveUserProfile(profile: UserProfile) {
        val json = gson.toJson(profile)
        prefs.edit().putString("user_profile_json", json).apply()
    }

    fun getUserProfile(): UserProfile? {
        val json = prefs.getString("user_profile_json", null) ?: return null
        return try {
            gson.fromJson(json, UserProfile::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun saveLastProfileSyncDate(date: String) {
        prefs.edit().putString("last_profile_sync_date", date).apply()
    }

    fun getLastProfileSyncDate(): String {
        return prefs.getString("last_profile_sync_date", "") ?: ""
    }

    fun saveUserEmail(email: String) {
        prefs.edit().putString("user_email", email).apply()
    }

    fun getUserEmail(): String? {
        return prefs.getString("user_email", null)
    }

    fun saveDriveToken(token: String) {
        prefs.edit().putString("drive_token", token).apply()
    }

    fun getDriveToken(): String? {
        return prefs.getString("drive_token", null)
    }

    fun saveRegStatus() {
        prefs.edit().putBoolean("is_registered", true).apply()
    }

    fun getRegStatus(): Boolean {
        return prefs.getBoolean("is_registered", false)
    }

    fun saveLastBackupDate(timestamp: Long) {
        prefs.edit().putLong("last_backup_date", timestamp).apply()
    }

    fun getLastBackupDate(): Long {
        return prefs.getLong("last_backup_date", 0L)
    }

    fun saveRoutineBackupConfig(config: String) {
        prefs.edit().putString("routine_backup_config", config).apply()
    }

    fun getRoutineBackupConfig(): String {
        return prefs.getString("routine_backup_config", "Never") ?: "Never"
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
