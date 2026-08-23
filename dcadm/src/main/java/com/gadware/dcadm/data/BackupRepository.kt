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

data class DatabaseStats(
    val dbName: String,
    val totalSizeBytes: Long,
    val formattedSize: String,
    val dbPath: String,
    val walFileExists: Boolean,
    val isEncryptedOrOpen: Boolean
)

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

    /**
     * Creates a local zip file containing checkpointed database files for export / sharing.
     */
    suspend fun createLocalBackupZipFile(): Result<File> = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val backupDir = File(cacheDir, "local_backup_temp")
        val zipFile = File(cacheDir, "${DcadmConfig.getDatabaseName()}_backup_${System.currentTimeMillis()}.zip")

        try {
            // 1. Checkpoint Database
            try {
                database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            } catch (e: Exception) {
                DcadmLog.d("BackupRepository", "Checkpoint warning: ${e.message}")
            }
            database.close()

            // 2. Identify DB files
            val dbName = DcadmConfig.getDatabaseName()
            val dbFile = context.getDatabasePath(dbName)
            val walFile = context.getDatabasePath("$dbName-wal")
            val shmFile = context.getDatabasePath("$dbName-shm")

            val filesToZip = mutableListOf<File>()
            if (dbFile.exists()) filesToZip.add(dbFile)
            if (walFile.exists()) filesToZip.add(walFile)
            if (shmFile.exists()) filesToZip.add(shmFile)

            if (filesToZip.isEmpty()) {
                return@withContext Result.failure(Exception("No database files found to export"))
            }

            if (backupDir.exists()) backupDir.deleteRecursively()
            backupDir.mkdirs()

            val filesToPack = filesToZip.map { file ->
                val dest = File(backupDir, file.name)
                file.copyTo(dest, overwrite = true)
                dest
            }

            if (zipFile.exists()) zipFile.delete()
            ZipUtils.zipFiles(filesToPack, zipFile)

            Result.success(zipFile)
        } catch (e: Exception) {
            DcadmLog.e("BackupRepository", "Local backup zip creation failed", e)
            Result.failure(e)
        } finally {
            try {
                if (backupDir.exists()) backupDir.deleteRecursively()
            } catch (ignored: Exception) {}
            DcadmConfig.onDatabaseReopenNeeded()
        }
    }

    /**
     * Exports database to a SAF document destination Uri.
     */
    suspend fun exportDatabaseToUri(destinationUri: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val zipResult = createLocalBackupZipFile()
        if (zipResult.isFailure) {
            return@withContext Result.failure(zipResult.exceptionOrNull() ?: Exception("Export creation failed"))
        }

        val zipFile = zipResult.getOrThrow()
        try {
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                zipFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Unable to open output stream for destination URI"))

            Result.success(Unit)
        } catch (e: Exception) {
            DcadmLog.e("BackupRepository", "Failed writing export to URI", e)
            Result.failure(e)
        } finally {
            try {
                if (zipFile.exists()) zipFile.delete()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Imports database from a SAF source Uri (.zip or raw .db SQLite file).
     */
    suspend fun importDatabaseFromUri(sourceUri: android.net.Uri): Result<Boolean> = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tempImportFile = File(cacheDir, "imported_temp_${System.currentTimeMillis()}")
        val extractDir = File(cacheDir, "import_extract_${System.currentTimeMillis()}")

        try {
            // Copy incoming Uri content to a temp cache file
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                tempImportFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Unable to read selected file"))

            val dbName = DcadmConfig.getDatabaseName()
            val dbFile = context.getDatabasePath(dbName)
            val walFile = context.getDatabasePath("$dbName-wal")
            val shmFile = context.getDatabasePath("$dbName-shm")

            var dbRestored = false

            // Try unzipping if it's a zip archive
            extractDir.mkdirs()
            var isZip = false
            try {
                ZipUtils.unzipFiles(tempImportFile, extractDir)
                val files = extractDir.listFiles()
                if (files != null && files.isNotEmpty()) {
                    isZip = true
                }
            } catch (e: Exception) {
                isZip = false
            }

            database.close()

            if (isZip) {
                // Find matching database file inside unzipped content
                val unzippedDb = File(extractDir, dbName).takeIf { it.exists() }
                    ?: extractDir.listFiles()?.firstOrNull { it.name.endsWith(".db") || it.name == dbName }

                if (unzippedDb != null && unzippedDb.exists()) {
                    unzippedDb.copyTo(dbFile, overwrite = true)
                    dbRestored = true

                    val unzippedWal = File(extractDir, "$dbName-wal").takeIf { it.exists() }
                        ?: extractDir.listFiles()?.firstOrNull { it.name.endsWith("-wal") }
                    if (unzippedWal != null && unzippedWal.exists()) {
                        unzippedWal.copyTo(walFile, overwrite = true)
                    } else if (walFile.exists()) {
                        walFile.delete()
                    }

                    val unzippedShm = File(extractDir, "$dbName-shm").takeIf { it.exists() }
                        ?: extractDir.listFiles()?.firstOrNull { it.name.endsWith("-shm") }
                    if (unzippedShm != null && unzippedShm.exists()) {
                        unzippedShm.copyTo(shmFile, overwrite = true)
                    } else if (shmFile.exists()) {
                        shmFile.delete()
                    }
                }
            }

            if (!dbRestored) {
                // If not a zip or no db found inside zip, assume raw SQLite db file
                tempImportFile.copyTo(dbFile, overwrite = true)
                if (walFile.exists()) walFile.delete()
                if (shmFile.exists()) shmFile.delete()
                dbRestored = true
            }

            Result.success(dbRestored)
        } catch (e: Exception) {
            DcadmLog.e("BackupRepository", "Local database import failed", e)
            Result.failure(e)
        } finally {
            try {
                if (tempImportFile.exists()) tempImportFile.delete()
                if (extractDir.exists()) extractDir.deleteRecursively()
            } catch (ignored: Exception) {}
            DcadmConfig.onDatabaseReopenNeeded()
        }
    }

    /**
     * Gathers database statistics including size, path, and WAL state.
     */
    fun getDatabaseStats(): DatabaseStats {
        val dbName = DcadmConfig.getDatabaseName()
        val dbFile = context.getDatabasePath(dbName)
        val walFile = context.getDatabasePath("$dbName-wal")
        val shmFile = context.getDatabasePath("$dbName-shm")

        var totalSize = 0L
        if (dbFile.exists()) totalSize += dbFile.length()
        if (walFile.exists()) totalSize += walFile.length()
        if (shmFile.exists()) totalSize += shmFile.length()

        val formattedSize = when {
            totalSize >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", totalSize / (1024.0 * 1024.0))
            totalSize >= 1024 -> String.format(java.util.Locale.US, "%.2f KB", totalSize / 1024.0)
            else -> "$totalSize Bytes"
        }

        return DatabaseStats(
            dbName = dbName,
            totalSizeBytes = totalSize,
            formattedSize = formattedSize,
            dbPath = dbFile.absolutePath,
            walFileExists = walFile.exists() && walFile.length() > 0,
            isEncryptedOrOpen = database.isOpen
        )
    }

    /**
     * Executes PRAGMA integrity_check and WAL checkpoint.
     */
    suspend fun performIntegrityCheck(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cursor = database.openHelper.writableDatabase.query("PRAGMA integrity_check")
            var resultStr = "ok"
            if (cursor.moveToFirst()) {
                resultStr = cursor.getString(0) ?: "ok"
            }
            cursor.close()

            // Run quick checkpoint
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()

            Result.success(resultStr)
        } catch (e: Exception) {
            DcadmLog.e("BackupRepository", "Integrity check error", e)
            Result.failure(e)
        }
    }
}

