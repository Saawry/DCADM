package com.gadware.dcadm.data

import android.content.Context
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.drive.DriveServiceHelper
import androidx.room.RoomDatabase
import com.gadware.dcadm.utils.DcadmLog
import com.gadware.dcadm.utils.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BackupRepository(
    private val context: Context,
    private val database: RoomDatabase,
    private val authManager: GoogleAuthManager
) {

    suspend fun performBackup(email: String, accessToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val backupDir = File(cacheDir, "backup_temp")
        val zipFile = File(cacheDir, "backup.zip")

        try {
            // 1. Initialize Drive Service
            val driveHelper = DriveServiceHelper(context, accessToken)

            // 2. Checkpoint Database (Ensure all data is in the main .db file or synced)
            // Using a raw query to checkpoint
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            database.close()

            // 3. Identify DB files
            val dbName = DcadmConfig.getDatabaseName()
            val dbFile = context.getDatabasePath(dbName)
            val walFile = context.getDatabasePath("$dbName-wal")
            val shmFile = context.getDatabasePath("$dbName-shm")

            val filesToZip = mutableListOf<File>()
            if (dbFile.exists()) filesToZip.add(dbFile)
            if (walFile.exists()) filesToZip.add(walFile)
            if (shmFile.exists()) filesToZip.add(shmFile)

            if (filesToZip.isEmpty()) {
                return@withContext Result.failure(Exception("No database files found"))
            } else {
                DcadmLog.d("BackupOperation", "backupRepository --files to zip: not empty.")
            }

            // 4. Copy to Cache and Zip
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            } else {
                DcadmLog.d("BackupOperation", "backupRepository-- backup directory not exists")
            }
            backupDir.mkdirs()

            // We copy files to a temp dir first to avoid file locking issues during zip if possible,
            // though with checkpoint they should be safe to read.
            val filesToPack = filesToZip.map { file ->
                val dest = File(backupDir, file.name)
                file.copyTo(dest, overwrite = true)
                dest
            }

            ZipUtils.zipFiles(filesToPack, zipFile)

            // 5. Upload to Drive
            val existingId = driveHelper.findBackupFile()
            DcadmLog.d("BackupOperation", "backupRepository: existing id --${existingId}")
            driveHelper.uploadBackup(zipFile, existingId)

            Result.success(Unit)
        } catch (e: Exception) {
            DcadmLog.d("BackupOperation", "Backup Repository: exception --${e}")
            Result.failure(e)
        } finally {
            try {
                if (backupDir.exists()) backupDir.deleteRecursively()
                if (zipFile.exists()) zipFile.delete()
            } catch (cleanupEx: Exception) {
                DcadmLog.d("BackupOperation", "backupRepository: cleanup exception --${cleanupEx}")
            }
            DcadmConfig.onDatabaseReopenNeeded()
        }
    }

    suspend fun performRestore(email: String, accessToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val zipFile = File(cacheDir, "downloaded_backup.zip")
        val tempDir = File(cacheDir, "restore_temp")

        try {
            val driveHelper = DriveServiceHelper(context, accessToken)
            
            val existingId = driveHelper.findBackupFile()
            DcadmLog.d("PerformRestore", "BackupRepository: existing id --${existingId}")
            if (existingId == null) {
                return@withContext Result.success(false)
            }

            driveHelper.downloadBackup(existingId, zipFile)

            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            ZipUtils.unzipFiles(zipFile, tempDir)

            database.close()

            val dbName = DcadmConfig.getDatabaseName()
            val dbFile = context.getDatabasePath(dbName)
            val walFile = context.getDatabasePath("$dbName-wal")
            val shmFile = context.getDatabasePath("$dbName-shm")

            val tempDb = File(tempDir, dbFile.name)
            if (tempDb.exists()) tempDb.copyTo(dbFile, overwrite = true)
            
            val tempWal = File(tempDir, walFile.name)
            if (tempWal.exists()) {
                tempWal.copyTo(walFile, overwrite = true)
            } else if (walFile.exists()) {
                walFile.delete()
            }

            val tempShm = File(tempDir, shmFile.name)
            if (tempShm.exists()) {
                tempShm.copyTo(shmFile, overwrite = true)
            } else if (shmFile.exists()) {
                shmFile.delete()
            }

            Result.success(true)
        } catch (e: Exception) {
            DcadmLog.e("BackupRepository", "Restore failed", e)
            Result.failure(e)
        } finally {
            try {
                if (zipFile.exists()) zipFile.delete()
                if (tempDir.exists()) tempDir.deleteRecursively()
            } catch (e: Exception) {
                DcadmLog.d("PerformRestore", "cleanup exception: ${e.message}")
            }
            DcadmConfig.onDatabaseReopenNeeded()
        }
    }
}
