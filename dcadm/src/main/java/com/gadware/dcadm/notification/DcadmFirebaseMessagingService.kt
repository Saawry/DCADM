package com.gadware.dcadm.notification

import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserRepository
import com.gadware.dcadm.utils.DcadmLog
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DcadmFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "DcadmFCMService"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DcadmLog.d(TAG, "New FCM device token generated: $token")

        val sessionManager = SessionManager(applicationContext)
        sessionManager.saveDeviceToken(token)
        DcadmNotificationManager.syncTopics(applicationContext)

        val userRepo = UserRepository()
        val uid = userRepo.getUserId() ?: sessionManager.getUserProfile()?.userId
        if (!uid.isNullOrBlank() && uid != "pending") {
            CoroutineScope(Dispatchers.IO).launch {
                userRepo.updateDeviceToken(uid, token, applicationContext)
                DcadmLog.d(TAG, "Updated new FCM token in Firestore for user $uid")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        DcadmLog.d(TAG, "Push message received from: ${remoteMessage.from}")

        val data = remoteMessage.data

        // Extract title and body from notification payload or data payload
        val title = remoteMessage.notification?.title
            ?: data["title"]
            ?: data["subject"]

        val body = remoteMessage.notification?.body
            ?: data["body"]
            ?: data["message"]
            ?: data["content"]

        // Notify custom listener if registered by host application
        try {
            DcadmNotificationManager.getNotificationReceivedListener()
                ?.onNotificationReceived(title, body, data)
        } catch (e: Exception) {
            DcadmLog.e(TAG, "Error in custom notification listener", e)
        }

        // Display standard notification if title or body is present
        if (!title.isNullOrBlank() || !body.isNullOrBlank()) {
            DcadmNotificationManager.showNotification(
                context = applicationContext,
                title = title,
                message = body,
                data = data
            )
        }
    }
}
