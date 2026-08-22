package com.gadware.dcadm.utils

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtils {
    fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            for (file in files) {
                if (!file.exists()) continue
                
                FileInputStream(file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        origin.copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }
    }

    fun unzipFiles(zipFile: File, extractDir: File) {
        if (!extractDir.exists()) extractDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val file = File(extractDir, entry.name)
                // Basic path traversal prevention
                if (!file.canonicalPath.startsWith(extractDir.canonicalPath)) {
                    throw SecurityException("Invalid zip entry: ${entry.name}")
                }
                FileOutputStream(file).use { out ->
                    zin.copyTo(out)
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
