package com.quickshare.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileOpener {

    fun openFile(context: Context, filePath: String) {
        if (filePath.isEmpty()) {
            Toast.makeText(context, "文件路径无效", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在或已被移动: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString()).lowercase()
            val mimeType = if (extension.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            } else {
                "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "选择打开方式").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开此文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
