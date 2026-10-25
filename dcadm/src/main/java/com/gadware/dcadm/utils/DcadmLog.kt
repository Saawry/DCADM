package com.gadware.dcadm.utils

import android.util.Log
import com.gadware.dcadm.BuildConfig
import com.gadware.dcadm.DcadmConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

object DcadmLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }

        // Record non-fatal error and breadcrumb in Firebase Crashlytics
        try {
            if (DcadmConfig.isCrashlyticsEnabled()) {
                val crashlytics = FirebaseCrashlytics.getInstance()
                crashlytics.log("$tag [ERROR]: $message")
                if (throwable != null) {
                    crashlytics.recordException(throwable)
                }
            }
        } catch (ignored: Exception) {
            // Safe fallback if Firebase is not initialized
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
        }

        try {
            if (DcadmConfig.isCrashlyticsEnabled()) {
                val crashlytics = FirebaseCrashlytics.getInstance()
                crashlytics.log("$tag [WARN]: $message")
                if (throwable != null) {
                    crashlytics.recordException(throwable)
                }
            }
        } catch (ignored: Exception) {
            // Safe fallback if Firebase is not initialized
        }
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }

        // Add breadcrumb in Firebase Crashlytics
        try {
            if (DcadmConfig.isCrashlyticsEnabled()) {
                FirebaseCrashlytics.getInstance().log("$tag [INFO]: $message")
            }
        } catch (ignored: Exception) {
            // Safe fallback if Firebase is not initialized
        }
    }
}
