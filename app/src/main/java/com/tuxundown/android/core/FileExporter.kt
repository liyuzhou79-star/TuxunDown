package com.tuxundown.android.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Exports a finished download out of the app's private scratch area into
 * a place the user can actually find it, under its real file name:
 * - a custom folder picked with the system picker (SAF), if one is set;
 * - otherwise the public Download/TuxunDown folder via MediaStore
 *   (no storage permission needed on Android 10+).
 * v1 never did this: finished files stayed inside the app as
 * "<uuid>.bin", invisible to file managers — a big part of "用不了".
 */
object FileExporter {

    data class Exported(val uri: Uri, val displayName: String, val where: String)

    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun export(context: Context, src: File, displayName: String): Exported {
        val custom = DownloadCenter.downloadDirUri
        return if (custom.isNotBlank()) {
            exportToTree(context, src, displayName, Uri.parse(custom))
        } else {
            exportToDownloads(context, src, displayName)
        }
    }

    private fun exportToTree(context: Context, src: File, displayName: String, treeUri: Uri): Exported {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("自定义文件夹打不开")
        if (!tree.canWrite()) throw IllegalStateException("自定义文件夹不可写（授权可能已失效，请在设置里重选）")
        // Replace any older export of the same name instead of piling up "name (1)".
        tree.findFile(displayName)?.delete()
        val target = tree.createFile(mimeOf(displayName), displayName)
            ?: throw IllegalStateException("在自定义文件夹里建不了文件")
        val out = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: throw IllegalStateException("自定义文件夹写入失败")
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
        return Exported(target.uri, displayName, "你选择的文件夹")
    }

    private fun exportToDownloads(context: Context, src: File, displayName: String): Exported {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Replace an older export with the same name in Download/TuxunDown.
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(displayName, Environment.DIRECTORY_DOWNLOADS + "/TuxunDown/"),
            null,
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(displayName))
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/TuxunDown"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("在下载文件夹里建不了文件")
        try {
            val out = resolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("下载文件夹写入失败")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return Exported(uri, displayName, "下载/TuxunDown 文件夹")
    }
}
