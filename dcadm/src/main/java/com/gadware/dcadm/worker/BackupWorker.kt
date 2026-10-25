package com.gadware.dcadm.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.BackupRepository
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.drive.DriveServiceHelper
import com.gadware.dcadm.utils.DcadmLog

class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val authManager = GoogleAuthManager(appContext)

    private val database: androidx.room.RoomDatabase by lazy {
        DcadmConfig.getDatabase(appContext)
    }

    private val backupRepository by lazy { BackupRepository(appContext, database, authManager) }
    private val sessionManager = SessionManager(appContext)

    override suspend fun doWork(): Result {
        // 1. Resolve Drive Email from input, session, or Firebase
        val driveEmail = inputData.getString("DRIVE_EMAIL")?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserEmail()?.takeIf { it.isNotBlank() }
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.takeIf { it.isNotBlank() }

        if (driveEmail.isNullOrBlank()) {
            DcadmLog.w("BackupWorker", "Missing authenticated email for routine backup. Postponing to next cycle.")
            // Return success with reason so WorkManager does not permanently cancel periodic schedule
            return Result.success(workDataOf("reason" to "missing_email"))
        }

        // 2. Resolve Access Token
        val tokenFromInput = inputData.getString("ACCESS_TOKEN")
        var accessToken: String? = if (!tokenFromInput.isNullOrBlank()) tokenFromInput else null

        // Try existing saved session token first
        if (accessToken.isNullOrBlank()) {
            val savedToken = sessionManager.getDriveToken()
            if (!savedToken.isNullOrBlank()) {
                try {
                    val driveHelper = DriveServiceHelper(applicationContext, savedToken)
                    driveHelper.findBackupFile()
                    accessToken = savedToken
                    DcadmLog.d("BackupWorker", "Existing session token is valid for $driveEmail")
                } catch (e: Exception) {
                    DcadmLog.d("BackupWorker", "Saved token invalid or expired: ${e.message}")
                }
            }
        }

        // Attempt silent background renewal
        if (accessToken.isNullOrBlank()) {
            val authResult = authManager.silentDriveAccess(applicationContext, driveEmail)
            if (authResult.isSuccess) {
                accessToken = authResult.getOrThrow()
            } else {
                val exception = authResult.exceptionOrNull()
                DcadmLog.w("BackupWorker", "Silent auth failed: ${exception?.message}")
                // If temporary failure, retry with backoff. If exhausted, succeed to preserve schedule
                return if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.success(workDataOf("reason" to (exception?.message ?: "auth_failed")))
                }
            }
        }

        val dbName = DcadmConfig.getDatabaseName()
        val dbFile = applicationContext.getDatabasePath(dbName)
        val isLargeDb = dbFile.exists() && dbFile.length() > 5 * 1024 * 1024 // 5MB

        // Safe foreground notification if DB is large (protected against Android 12+ background exceptions)
        if (isLargeDb) {
            try {
                setForeground(createForegroundInfo(applicationContext.getString(R.string.dcadm_worker_backup_progress)))
            } catch (e: Exception) {
                DcadmLog.w("BackupWorker", "Could not set foreground info (normal for background execution): ${e.message}")
            }
        }

        // 3. Execute Backup
        return try {
            val result = backupRepository.performBackup(driveEmail, accessToken)
            if (result.isSuccess) {
                val now = System.currentTimeMillis()
                sessionManager.saveLastBackupDate(now)
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))
                val userRepo = com.gadware.dcadm.data.UserRepository()
                val uid = userRepo.getUserId() ?: sessionManager.getUserProfile()?.userId ?: ""
                if (uid.isNotBlank() && uid != "pending") {
                    userRepo.updateLastBackupDate(uid, dateStr, applicationContext)
                } else {
                    val profile = sessionManager.getUserProfile()
                    if (profile != null) {
                        sessionManager.saveUserProfile(profile.copy(lastBackupDate = dateStr))
                    }
                }
                DcadmLog.d("BackupWorker", "Routine backup completed successfully for $driveEmail")
                Result.success()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Backup failed"
                DcadmLog.e("BackupWorker", "Routine backup failed: $errorMsg")
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.success(workDataOf("error" to errorMsg))
                }
            }
        } catch (e: Exception) {
            DcadmLog.e("BackupWorker", "Exception during routine backup", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.success(workDataOf("error" to (e.message ?: "Backup failed with exception")))
            }
        }
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val channelId = "backup_channel"
        val title = applicationContext.getString(R.string.dcadm_worker_backup_title)
        val cancel = applicationContext.getString(R.string.dcadm_worker_cancel)

        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                applicationContext.getString(R.string.dcadm_worker_backup_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(progress)
            .setSmallIcon(R.drawable.baseline_sync_24)
            .setOngoing(true)
            .addAction(R.drawable.baseline_delete_24, cancel, intent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1, notification)
        }
    }
}
