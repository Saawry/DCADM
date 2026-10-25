package com.gadware.dcadm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.gadware.dcadm.utils.DcadmLog

/**
 * Internal ContentProvider that automatically captures Application Context
 * and synchronizes the routine backup schedule with WorkManager upon application startup.
 */
class DcadmInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            context?.applicationContext?.let { appContext ->
                DcadmConfig.setApplicationContext(appContext)
                DcadmConfig.syncRoutineBackupSchedule(appContext)
                DcadmLog.d("DcadmInitProvider", "Dcadm initialized automatically via ContentProvider.")
            }
        } catch (e: Exception) {
            DcadmLog.e("DcadmInitProvider", "Error during automatic routine backup sync: ${e.message}")
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
