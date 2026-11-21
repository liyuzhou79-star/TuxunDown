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
 * Where download bytes actually live (v1.2, user-corrected):
 *
 * - The DEFAULT location is the public Download folder ITSELF —
 *   "/storage/emulated/0/Download", the root every app shares and
 *   every file manager shows on its first screen. NOT a
 *   "Download/TuxunDown" subfolder, and NEVER the app's private
 *   Android/data directory.
 * - In-progress downloads are written straight there as
 *   "<real name>.part", seekable via the item's ParcelFileDescriptor,
 *   so the half-finished file is visible in the file manager the
 *   whole time. On completion the entry is simply renamed in place
 *   (v1.1's "download privately, export afterwards" dance is gone).
 * - If the user picked a custom folder (SAF), that folder is used
 *   the same way: .part written directly inside it, renamed at the
 *   end. The custom-folder freedom from v1.1 is unchanged.
 *
 * The default path needs no permission at all: the MediaStore
 * Downloads collection root (RELATIVE_PATH = "Download/").
 * Small metadata (chunk bitmaps etc.) stays in the app's data area —
 * that is bookkeeping, not the downloaded file.
 */
object PublicStorage {

    class Obtained(val target: DownloadTarget, val fresh: Boolean)

    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun partName(finalName: String): String = "$finalName.part"

    /** Human-readable name of the current download location, for the UI. */
    fun locationLabel(context: Context): String {
        val custom = DownloadCenter.downloadDirName
        return if (DownloadCenter.downloadDirUri.isNotBlank() && custom.isNotBlank()) {
            "自定义文件夹：$custom"
        } else {
            "公共「下载」目录"
        }
    }

    /**
     * Open (or create) the seekable .part target for [task] in the
     * public location. Reuses the persisted target Uri on resume.
     * If the persisted target has vanished, the task's chunk state is
     * reset — the bytes are gone, pretending otherwise would corrupt
     * the file.
     */
    fun obtainTarget(context: Context, task: TaskRecord): Obtained {
        val resolver = context.contentResolver
        if (task.targetUri.isNotBlank()) {
            val uri = Uri.parse(task.targetUri)
            val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
            if (pfd != null) return Obtained(PfdTarget(pfd), fresh = false)
            // Target file was deleted behind our back: start over.
            task.targetUri = ""
            task.chunkDone = BooleanArray(0)
            task.chunkSize = 0
            task.downloaded = 0
        }
        val name = task.fileName.ifBlank {
            DownloadEngine.fileNameFrom(null, task.url).also { task.fileName = it }
        }
        // Audit A2: two live tasks must never share one .part file.
        // If another task in the store already owns the plain
        // "<name>.part", this task gets a per-task part name instead
        // (the finished file still lands under the real name).
        val shared = DownloadCenter.store.tasks.any {
            it.id != task.id && it.targetUri.isNotBlank() &&
                it.status != TaskStatus.DONE &&
                (it.fileName == name)
        }
        val part = if (shared) {
            "${name}.${task.id.take(6)}.part"
        } else {
            partName(name)
        }
        val custom = DownloadCenter.downloadDirUri
        val uri: Uri = if (custom.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(custom))
                ?: throw IllegalStateException("自定义文件夹打不开，请在设置里重选下载目录")
            if (!tree.canWrite()) {
                throw IllegalStateException("自定义文件夹不可写（授权可能已失效，请在设置里重选）")
            }
            val doc = tree.findFile(part)
                ?: tree.createFile("application/octet-stream", part)
                ?: throw IllegalStateException("在自定义文件夹里建不了文件")
            doc.uri
        } else {
            findDownloadsEntry(context, part) ?: insertDownloadsEntry(context, part)
        }
        task.targetUri = uri.toString()
        val pfd = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("下载文件打不开（公共下载目录写入失败）")
        return Obtained(PfdTarget(pfd), fresh = true)
    }

    /**
     * One-time migration for tasks paused under v1.1 or earlier,
     * whose partial bytes still sit in the private scratch file:
     * copy them into the fresh public .part once, then drop the
     * private copy. New tasks never touch the private directory.
     */
    fun migrateLegacyIfNeeded(task: TaskRecord, obtained: Obtained, legacy: File) {
        if (!obtained.fresh || !legacy.exists() || task.downloaded <= 0) return
        runCatching {
            legacy.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                var pos = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    obtained.target.writeAt(pos, buf, 0, n)
                    pos += n
                }
            }
            legacy.delete()
        }
    }

    /**
     * Rename the finished .part to its real name, in place, and fill
     * in the task's saved Uri/name. Returns the display name.
     */
    fun finalizeTarget(context: Context, task: TaskRecord): String {
        val resolver = context.contentResolver
        val name = task.fileName.ifBlank { "download" }
        val uri = Uri.parse(
            task.targetUri.ifBlank { throw IllegalStateException("找不到下载文件") }
        )
        val custom = DownloadCenter.downloadDirUri
        var displayName = name
        if (custom.isNotBlank()) {
            // SAF: renaming changes the document id on some providers,
            // so re-find the file in the tree afterwards.
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(custom))
            // Audit A1 (SAF side): never overwrite a stranger's file —
            // pick the first free "name (n).ext" instead.
            val finalName = freeName(name) { candidate ->
                val existing = tree?.findFile(candidate)
                existing != null && existing.uri != uri
            }
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc != null && doc.name != finalName) {
                runCatching { doc.renameTo(finalName) }
            }
            val found = tree?.findFile(finalName)
            if (found != null) {
                task.savedUri = found.uri.toString()
                displayName = finalName
            } else {
                task.savedUri = uri.toString()
                displayName = doc?.name ?: partName(name)
            }
        } else {
            // MediaStore, audit A1: v1.2 DELETED any same-name entry
            // already sitting in the Download folder — including the
            // user's own pre-existing files. Never again: if a
            // different entry already owns the final name, ours is
            // renamed to the first free "name (n).ext" instead (the
            // Uri/id stays stable either way).
            val finalName = freeName(name) { candidate ->
                findDownloadsEntry(context, candidate) != null &&
                    findDownloadsEntry(context, candidate) != uri
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(finalName))
            }
            resolver.update(uri, values, null, null)
            task.savedUri = uri.toString()
            displayName = finalName
        }
        task.savedName = displayName
        return displayName
    }

    /** First free variant of [name]: name, "name (1).ext", "name (2).ext"… */
    private fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    // ---------- v1.7: orphan .part cleanup ----------

    /** A ".part" entry in the download location that NO task references. */
    data class OrphanPart(val uri: Uri, val name: String, val size: Long)

    /**
     * Scan the CURRENT download location (public Download root, or
     * the user's SAF folder) for ".part" files that no task in the
     * store points at — leftovers from deleted tasks, crashed runs
     * or manual file-manager shuffles. Referenced .part files (any
     * live or paused task's target) are never reported.
     */
    fun scanOrphanParts(context: Context): List<OrphanPart> {
        val referenced = HashSet<String>()
        for (t in DownloadCenter.store.tasks) {
            if (t.targetUri.isNotBlank()) referenced.add(t.targetUri)
            if (t.savedUri.isNotBlank()) referenced.add(t.savedUri)
        }
        val out = mutableListOf<OrphanPart>()
        val custom = DownloadCenter.downloadDirUri
        if (custom.isNotBlank()) {
            val tree = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(custom))
            }.getOrNull()
            if (tree != null && tree.canRead()) {
                for (f in tree.listFiles()) {
                    val name = f.name ?: continue
                    if (!name.endsWith(".part", ignoreCase = true)) continue
                    if (f.uri.toString() in referenced) continue
                    out.add(
                        OrphanPart(
                            f.uri, name,
                            runCatching { f.length() }.getOrDefault(-1),
                        )
                    )
                }
            }
        } else {
            val cursor = runCatching {
                context.contentResolver.query(
                    downloadsCollection(),
                    arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                    ),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                        "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf("%.part", Environment.DIRECTORY_DOWNLOADS + "/"),
                    "${MediaStore.Downloads.DISPLAY_NAME} ASC",
                )
            }.getOrNull()
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (it.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        downloadsCollection(), it.getLong(idIdx).toString()
                    )
                    if (uri.toString() in referenced) continue
                    out.add(
                        OrphanPart(
                            uri,
                            it.getString(nameIdx) ?: "",
                            runCatching { it.getLong(sizeIdx) }.getOrDefault(-1),
                        )
                    )
                }
            }
        }
        return out
    }

    // ---------- v1.9: finished-file scan for the files page ----------

    /** A finished (non-.part) file found in the download location. */
    data class ScannedFile(val uri: Uri, val name: String, val size: Long, val modifiedAt: Long)

    /**
     * List the FINISHED files in the current download location
     * (public Download root, or the user's SAF folder). The
     * caller merges this with the task records; entries a record
     * already claims are dropped there. ".part" files belong to
     * the orphan cleanup, not to this list.
     */
    fun scanDownloadedFiles(context: Context): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        val custom = DownloadCenter.downloadDirUri
        if (custom.isNotBlank()) {
            val tree = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(custom))
            }.getOrNull()
            if (tree != null && tree.canRead()) {
                for (f in tree.listFiles()) {
                    val name = f.name ?: continue
                    if (!f.isFile) continue
                    if (name.endsWith(".part", ignoreCase = true)) continue
                    out.add(
                        ScannedFile(
                            f.uri, name,
                            runCatching { f.length() }.getOrDefault(-1),
                            runCatching { f.lastModified() }.getOrDefault(0),
                        )
                    )
                }
            }
        } else {
            val cursor = runCatching {
                context.contentResolver.query(
                    downloadsCollection(),
                    arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                    ),
                    "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(Environment.DIRECTORY_DOWNLOADS + "/"),
                    "${MediaStore.Downloads.DISPLAY_NAME} ASC",
                )
            }.getOrNull()
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val modIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    if (name.endsWith(".part", ignoreCase = true)) continue
                    val uri = Uri.withAppendedPath(
                        downloadsCollection(), it.getLong(idIdx).toString()
                    )
                    out.add(
                        ScannedFile(
                            uri, name,
                            runCatching { it.getLong(sizeIdx) }.getOrDefault(-1),
                            // MediaStore DATE_MODIFIED is SECONDS.
                            runCatching { it.getLong(modIdx) * 1000 }.getOrDefault(0),
                        )
                    )
                }
            }
        }
        return out
    }

    /** Delete one file by its Uri string; false when refused. */
    fun deleteByUri(context: Context, uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        if (doc != null && doc.exists()) {
            return runCatching { doc.delete() }.getOrDefault(false)
        }
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    /** Delete one orphan .part; false when the system refused. */
    fun deleteOrphan(context: Context, orphan: OrphanPart): Boolean {
        val doc = runCatching {
            DocumentFile.fromSingleUri(context, orphan.uri)
        }.getOrNull()
        if (doc != null && doc.exists()) {
            return runCatching { doc.delete() }.getOrDefault(false)
        }
        return runCatching {
            context.contentResolver.delete(orphan.uri, null, null) > 0
        }.getOrDefault(false)
    }

    /** Delete the task's public file (.part or finished). Best effort. */
    fun deleteTarget(context: Context, task: TaskRecord) {
        val resolver = context.contentResolver
        for (s in listOf(task.savedUri, task.targetUri)) {
            if (s.isBlank()) continue
            val uri = Uri.parse(s)
            val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            if (doc != null && doc.exists()) {
                runCatching { doc.delete() }
            } else {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    // ---------- MediaStore Downloads collection, ROOT folder ----------

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun findDownloadsEntry(context: Context, displayName: String): Uri? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            downloadsCollection(),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(displayName, Environment.DIRECTORY_DOWNLOADS + "/"),
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return Uri.withAppendedPath(downloadsCollection(), it.getLong(0).toString())
            }
        }
        return null
    }

    private fun insertDownloadsEntry(context: Context, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            // The public Download folder ROOT — no subfolder.
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        return context.contentResolver.insert(downloadsCollection(), values)
            ?: throw IllegalStateException("在公共下载目录里建不了文件")
    }
}
