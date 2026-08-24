package com.gadware.dcadm.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserRepository
import com.gadware.dcadm.utils.DcadmLog
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Listener interface allowing host applications to intercept incoming push notifications.
 */
fun interface OnNotificationReceivedListener {
    fun onNotificationReceived(title: String?, body: String?, data: Map<String, String>)
}

object DcadmNotificationManager {
    const val CHANNEL_ID = "dcadm_notifications_channel"
    private const val TAG = "DcadmNotification"

    private var customListener: OnNotificationReceivedListener? = null

    /**
     * Registers a listener to receive callbacks when a push notification is delivered.
     */
    fun setNotificationReceivedListener(listener: OnNotificationReceivedListener?) {
        this.customListener = listener
    }

    fun getNotificationReceivedListener(): OnNotificationReceivedListener? = customListener

    /**
     * Ensures the default notification channel exists on Android 8.0 (API 26) and above.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.dcadm_notification_channel_name)
            val descriptionText = context.getString(R.string.dcadm_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    const val TOPIC_ALL = "all"
    const val TOPIC_UPDATES = "updates"
    const val TOPIC_PAID_USERS = "paid_users"
    const val TOPIC_FREE_USERS = "free_users"

    /**
     * Subscribes the device to an FCM topic.
     */
    fun subscribeToTopic(topic: String, onComplete: ((Boolean) -> Unit)? = null) {
        val sanitized = topic.trim().lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9-_.~%]"), "")
        if (sanitized.isBlank()) return

        FirebaseMessaging.getInstance().subscribeToTopic(sanitized)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    DcadmLog.d(TAG, "Subscribed to topic: $sanitized")
                } else {
                    DcadmLog.e(TAG, "Failed to subscribe to topic: $sanitized", task.exception)
                }
                onComplete?.invoke(task.isSuccessful)
            }
    }

    /**
     * Unsubscribes the device from an FCM topic.
     */
    fun unsubscribeFromTopic(topic: String, onComplete: ((Boolean) -> Unit)? = null) {
        val sanitized = topic.trim().lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9-_.~%]"), "")
        if (sanitized.isBlank()) return

        FirebaseMessaging.getInstance().unsubscribeFromTopic(sanitized)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    DcadmLog.d(TAG, "Unsubscribed from topic: $sanitized")
                } else {
                    DcadmLog.e(TAG, "Failed to unsubscribe from topic: $sanitized", task.exception)
                }
                onComplete?.invoke(task.isSuccessful)
            }
    }

    /**
     * Synchronizes all required FCM topic subscriptions:
     * - "all" (Global broadcast)
     * - "updates" (App updates)
     * - "paid_users" or "free_users" (Based on UserProfile.userType)
     * - "country_<countryCode>" (e.g. country_bd, country_us based on UserProfile.country or device locale)
     */
    fun syncTopics(context: Context, profile: com.gadware.dcadm.data.UserProfile? = null) {
        // 1. Subscribe to universal broadcast topics
        subscribeToTopic(TOPIC_ALL)
        subscribeToTopic(TOPIC_UPDATES)

        // 2. Resolve UserProfile
        val sessionManager = SessionManager(context)
        val userProfile = profile ?: sessionManager.getUserProfile()

        // 3. Subscription by User Type (paid_users vs free_users)
        if (userProfile != null) {
            val isPaid = userProfile.userType.equals("paid", ignoreCase = true)
            if (isPaid) {
                subscribeToTopic(TOPIC_PAID_USERS)
                unsubscribeFromTopic(TOPIC_FREE_USERS)
            } else {
                subscribeToTopic(TOPIC_FREE_USERS)
                unsubscribeFromTopic(TOPIC_PAID_USERS)
            }
        } else {
            subscribeToTopic(TOPIC_FREE_USERS)
        }

        // 4. Subscription by Country Code (country_<countryCode>)
        val countryCode = if (userProfile != null && userProfile.country.isNotBlank()) {
            com.gadware.dcadm.data.CountryData.findByNameOrCode(userProfile.country)?.code
                ?: userProfile.country.take(2)
        } else {
            java.util.Locale.getDefault().country
        }

        if (countryCode.isNotBlank()) {
            val countryTopic = "country_${countryCode.lowercase(java.util.Locale.US)}"
            subscribeToTopic(countryTopic)
        }
    }

    /**
     * Asynchronously retrieves the current FCM token, saves it locally to SessionManager,
     * updates the Firestore UserProfile if a user is currently authenticated, and syncs topics.
     */
    fun syncDeviceToken(context: Context, onComplete: ((String?) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Synchronize topic subscriptions
                syncTopics(context)

                val token = FirebaseMessaging.getInstance().token.await()
                if (!token.isNullOrBlank()) {
                    DcadmLog.d(TAG, "FCM Token retrieved: $token")
                    val sessionManager = SessionManager(context)
                    sessionManager.saveDeviceToken(token)

                    val userRepo = UserRepository()
                    val uid = userRepo.getUserId() ?: sessionManager.getUserProfile()?.userId
                    if (!uid.isNullOrBlank() && uid != "pending") {
                        userRepo.updateDeviceToken(uid, token, context)
                        DcadmLog.d(TAG, "FCM Token synced to Firestore for user: $uid")
                    }
                    onComplete?.invoke(token)
                } else {
                    onComplete?.invoke(null)
                }
            } catch (e: Exception) {
                DcadmLog.e(TAG, "Failed to retrieve or sync FCM token", e)
                onComplete?.invoke(null)
            }
        }
    }

    /**
     * Displays a system notification for the given message details.
     */
    fun showNotification(
        context: Context,
        title: String?,
        message: String?,
        data: Map<String, String> = emptyMap(),
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt()
    ) {
        createNotificationChannel(context)

        val displayTitle = title?.takeIf { it.isNotBlank() } ?: DcadmConfig.getAppName()
        val displayMessage = message ?: ""

        // Build target intent: open host app launcher or target activity
        val intent = try {
            val targetClassName = DcadmConfig.getHomeActivityClassName()
            if (!targetClassName.isNullOrBlank()) {
                Intent(context, Class.forName(targetClassName))
            } else {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent()
            }
        } catch (e: Exception) {
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        }

        // Attach notification data as extras
        for ((key, value) in data) {
            intent.putExtra(key, value)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_sync_24)
            .setContentTitle(displayTitle)
            .setContentText(displayMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        // Try setting custom logo if configured
        val customLogoRes = DcadmConfig.getAppLogoResId()
        if (customLogoRes != null && customLogoRes != 0) {
            try {
                val bitmap = BitmapFactory.decodeResource(context.resources, customLogoRes)
                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                }
            } catch (ignored: Exception) {}
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}
