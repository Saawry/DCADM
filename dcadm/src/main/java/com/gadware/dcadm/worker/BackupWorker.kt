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
import com.gadware.dcadm.auth.AuthResolutionRequiredException
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.BackupRepository
import com.gadware.dcadm.data.SessionManager

class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val authManager = GoogleAuthManager(appContext)
    
    private val database: androidx.room.RoomDatabase by lazy {
        val dbClass = DcadmConfig.getDatabaseClass() ?: throw IllegalStateException("DcadmConfig not initialized")
        val method = dbClass.getMethod("getDatabase", Context::class.java)
        method.invoke(null, appContext) as androidx.room.RoomDatabase
    }

    private val backupRepository by lazy { BackupRepository(appContext, database, authManager) }
    private val sessionManager = SessionManager(appContext)

    override suspend fun doWork(): Result {
        val driveEmail = inputData.getString("DRIVE_EMAIL")
            ?: sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserEmail()
            ?: return Result.failure(workDataOf("reason" to "missing_email"))

        val tokenFromInput = inputData.getString("ACCESS_TOKEN")
        val accessToken = if (!tokenFromInput.isNullOrBlank()) {
            tokenFromInput
        } else {
            val authResult = authManager.silentDriveAccess(applicationContext, driveEmail)
            if (authResult.isFailure) {
                val exception = authResult.exceptionOrNull()
                return if (exception is AuthResolutionRequiredException) {
                    Result.failure(workDataOf("reason" to "consent_required"))
                } else {
                    Result.failure(workDataOf("reason" to (exception?.message ?: "auth_failed")))
                }
            }
            authResult.getOrThrow()
        }

        val dbName = DcadmConfig.getDatabaseName()
        val dbFile = applicationContext.getDatabasePath(dbName)
        val isLargeDb = dbFile.exists() && dbFile.length() > 5 * 1024 * 1024 // 5MB

        // Set foreground info to show notification if DB is large
        if (isLargeDb) {
            setForeground(createForegroundInfo(applicationContext.getString(R.string.dcadm_worker_backup_progress)))
        }

        return try {
            val result = backupRepository.performBackup(driveEmail, accessToken)
            if (result.isSuccess) {
                // Save last backup date
                sessionManager.saveLastBackupDate(System.currentTimeMillis())
                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
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
