package com.gadware.dcadm.drive

import android.content.Context
import com.gadware.dcadm.utils.DcadmLog
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DriveServiceHelper(context: Context, private val accessToken: String) {

    private val requestInitializer = HttpRequestInitializer { request ->
        request.headers.authorization = "Bearer $accessToken"
    }

    private val driveService: Drive = Drive.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        requestInitializer
    ).setApplicationName(com.gadware.dcadm.DcadmConfig.getAppName()).build()

    suspend fun findBackupFile(): String? = withContext(Dispatchers.IO) {
        val fileList = driveService.files().list()
            .setQ("name = 'backup.zip' and 'appDataFolder' in parents and trashed = false")
            .setSpaces("appDataFolder")
            .setFields("files(id, name)")
            .execute()
        DcadmLog.d("PerformRestore", "DriveServiceHelper: find backup file --size- ${fileList.size}")
        DcadmLog.d("PerformRestore", "DriveServiceHelper: find backup file --id- ${fileList.files.firstOrNull()?.id}")
        fileList.files.firstOrNull()?.id
    }

    suspend fun uploadBackup(localFile: File, existingFileId: String?): String = withContext(Dispatchers.IO) {
        val fileMetadata = com.google.api.services.drive.model.File()
        fileMetadata.name = "backup.zip"
        DcadmLog.d("BackupOperation", "DriveServiceHelper: localFile and existingFileId --${localFile.name} --${existingFileId}")
        val mediaContent = FileContent("application/zip", localFile)

        if (existingFileId != null) {
            // Update existing file
            driveService.files().update(existingFileId, null, mediaContent)
                .execute()
            existingFileId
        } else {
            // Create new file
            fileMetadata.parents = listOf("appDataFolder")
            val file = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
            DcadmLog.d("BackupOperation", "DriveServiceHelper: existingFileId is null, new file name and id --${file.name}-${file.id}")
            file.id

        }
    }

    suspend fun downloadBackup(fileId: String, destFile: File) = withContext(Dispatchers.IO) {
        DcadmLog.d("PerformRestore", "downloadBackup: fileId --${fileId}-- destFile ${destFile.name} ")
        val outputStream = FileOutputStream(destFile)
        driveService.files().get(fileId)
            .executeMediaAndDownloadTo(outputStream)
        outputStream.close()
    }
}
