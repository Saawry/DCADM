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

class RestoreWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val authManager = GoogleAuthManager(appContext)
    private val sessionManager = SessionManager(appContext)

    private val database: androidx.room.RoomDatabase by lazy {
        DcadmConfig.getDatabase(appContext)
    }

    private val backupRepository by lazy { BackupRepository(appContext, database, authManager) }

    override suspend fun doWork(): Result {
        val driveEmail = inputData.getString("DRIVE_EMAIL")?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserEmail()?.takeIf { it.isNotBlank() }
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.takeIf { it.isNotBlank() }

        if (driveEmail.isNullOrBlank()) {
            DcadmLog.w("RestoreWorker", "Missing authenticated email for restore operation.")
            return Result.failure(workDataOf("reason" to "missing_email"))
        }

        val tokenFromInput = inputData.getString("ACCESS_TOKEN")
        var accessToken: String? = if (!tokenFromInput.isNullOrBlank()) tokenFromInput else null

        if (accessToken.isNullOrBlank()) {
            val savedToken = sessionManager.getDriveToken()
            if (!savedToken.isNullOrBlank()) {
                try {
                    val driveHelper = DriveServiceHelper(applicationContext, savedToken)
                    driveHelper.findBackupFile()
                    accessToken = savedToken
                    DcadmLog.d("RestoreWorker", "Existing session token is valid for $driveEmail")
                } catch (e: Exception) {
                    DcadmLog.d("RestoreWorker", "Saved token invalid or expired: ${e.message}")
                }
            }
        }

        if (accessToken.isNullOrBlank()) {
            val authResult = authManager.silentDriveAccess(applicationContext, driveEmail)
            if (authResult.isSuccess) {
                accessToken = authResult.getOrThrow()
            } else {
                val exception = authResult.exceptionOrNull()
                DcadmLog.w("RestoreWorker", "Silent auth failed: ${exception?.message}")
                return Result.failure(workDataOf("reason" to (exception?.message ?: "auth_failed")))
            }
        }

        val dbName = DcadmConfig.getDatabaseName()
        val dbFile = applicationContext.getDatabasePath(dbName)
        val isLargeDb = dbFile.exists() && dbFile.length() > 5 * 1024 * 1024 // 5MB

        // Safe foreground notification if DB is large
        if (isLargeDb) {
            try {
                setForeground(createForegroundInfo(applicationContext.getString(R.string.dcadm_worker_restore_progress)))
            } catch (e: Exception) {
                DcadmLog.w("RestoreWorker", "Could not set foreground info: ${e.message}")
            }
        }

        return try {
            val result = backupRepository.performRestore(driveEmail, accessToken)
            if (result.isSuccess) {
                if (result.getOrNull() == true) {
                    Result.success()
                } else {
                    val noBackupMsg = applicationContext.getString(R.string.dcadm_restore_no_backup_found)
                    Result.failure(workDataOf("error" to noBackupMsg))
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Restore failed"
                Result.failure(workDataOf("error" to errorMsg))
            }
        } catch (e: Exception) {
            DcadmLog.e("RestoreWorker", "Exception during restore", e)
            Result.failure(workDataOf("error" to (e.message ?: "Restore failed with exception")))
        }
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val channelId = "restore_channel"
        val title = applicationContext.getString(R.string.dcadm_worker_restore_title)
        val cancel = applicationContext.getString(R.string.dcadm_worker_cancel)

        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                applicationContext.getString(R.string.dcadm_worker_restore_channel_name),
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
            ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2, notification)
        }
    }
}
