package com.tuxundown.android.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.zip.ZipInputStream

/**
 * v3.0: the platform half of auto-extraction (the pure safety
 * rules live in [ZipExtract]). Extracts a finished .zip task
 * into a folder named after the archive, in the SAME place
 * the archive itself landed: the public Download root (or
 * its category subfolder), or the user's custom SAF folder.
 *
 * Failure contract, all of it user-visible in the task note:
 * - a hostile path or a blown bomb cap aborts the WHOLE run;
 * - an aborted/failed run deletes the files it had already
 *   created (best effort) — a half-extracted tree must not
 *   pose as a successful one;
 * - the original .zip is NEVER deleted, whatever happens.
 *
 * Runs on the service's finalize thread (the same thread that
 * already streams the whole file for hash verification).
 */
object ZipExtractor {

    data class Result(val ok: Boolean, val text: String)

    /** Carries a finished, plain-language failure out of the loop. */
    private class Abort(val result: Result) : Exception()

    fun extract(
        context: Context,
        task: TaskRecord,
        onProgress: (files: Int) -> Unit,
    ): Result {
        val uriStr = task.savedUri.ifBlank { task.targetUri }
        if (uriStr.isBlank()) {
            return Result(false, "自动解压没做成：找不到下载好的文件，原文件还在。")
        }
        val zipUri = runCatching { Uri.parse(uriStr) }.getOrNull()
            ?: return Result(false, "自动解压没做成：找不到下载好的文件，原文件还在。")
        val zipName = task.savedName.ifBlank { task.fileName }
        val input = runCatching {
            context.contentResolver.openInputStream(zipUri)
        }.getOrNull()
            ?: return Result(false, "自动解压没做成：读不到这个 zip 文件，原文件还在。")

        val created = mutableListOf<Uri>()
        var files = 0
        var sawEntry = false
        try {
            val sink = Sink.open(context, zipUri, ZipExtract.folderNameFor(zipName))
            val guard = ZipExtract.Guard()
            val buf = ByteArray(256 * 1024)
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    sawEntry = true
                    if (!guard.acceptEntry(entry.size)) {
                        throw Abort(
                            Result(
                                false,
                                "自动解压停下了：压缩包里的文件太多或解开后总大小太大" +
                                    "（防压缩包炸弹的上限），已清理解出一半的文件，原 zip 还在。",
                            )
                        )
                    }
                    val rel = ZipExtract.safeRelativePath(entry.name)
                    if (rel == null) {
                        throw Abort(
                            Result(
                                false,
                                "自动解压停下了：压缩包里有不安全的文件路径" +
                                    "（${entry.name.take(60)}），为安全起见整包不解，" +
                                    "已清理解出一半的文件，原 zip 还在。",
                            )
                        )
                    }
                    if (entry.isDirectory || rel.isEmpty()) continue
                    val outUri = sink.create(rel, created)
                    val written = runCatching {
                        context.contentResolver.openOutputStream(outUri, "w")?.use { out ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                if (!guard.acceptWritten(n)) {
                                    throw Abort(
                                        Result(
                                            false,
                                            "自动解压停下了：解开后的总大小超过上限" +
                                                "（防压缩包炸弹），已清理解出一半的文件，原 zip 还在。",
                                        )
                                    )
                                }
                                out.write(buf, 0, n)
                            }
                        } ?: throw IllegalStateException("解压时建不了文件")
                    }
                    if (written.isFailure) {
                        val e = written.exceptionOrNull()
                        if (e is Abort) throw e
                        throw e ?: IllegalStateException("解压失败")
                    }
                    sink.finishFile(outUri)
                    files++
                    if (files % 25 == 0) onProgress(files)
                }
            }
            if (!sawEntry) {
                return Result(
                    false,
                    "自动解压没做成：这个文件里读不到任何条目，可能不是真正的 zip 压缩包。原文件还在。",
                )
            }
            if (files == 0) {
                return Result(
                    false,
                    "自动解压没做成：这个压缩包里只有文件夹、没有文件。原文件还在。",
                )
            }
            return Result(
                true,
                "已自动解压到「${sink.folderName}」文件夹（$files 个文件），原 zip 保留。",
            )
        } catch (a: Abort) {
            rollback(context, created)
            return a.result
        } catch (e: Exception) {
            rollback(context, created)
            return Result(
                false,
                "自动解压没做成（${Errors.human(e)}），已清理解出一半的文件，原 zip 还在。",
            )
        } finally {
            runCatching { input.close() }
        }
    }

    /** Best-effort removal of everything one run created. */
    private fun rollback(context: Context, created: List<Uri>) {
        for (uri in created.asReversed()) {
            runCatching { PublicStorage.deleteByUri(context, uri.toString()) }
        }
    }

    /**
     * Where extracted files go: one implementation per storage
     * backend, chosen by how the archive itself was stored.
     * The target folder's name is settled once, up front, with
     * the same "name (1)" collision rule finalize uses — a
     * fresh folder means extracted entries can only ever
     * collide with each other (duplicate paths inside one
     * archive overwrite, like a desktop unzip).
     */
    private sealed interface Sink {
        val folderName: String
        fun create(relPath: String, created: MutableList<Uri>): Uri
        fun finishFile(uri: Uri)

        companion object {
            fun open(
                context: Context,
                zipUri: Uri,
                baseName: String,
            ): Sink {
                val custom = DownloadCenter.downloadDirUri
                return if (custom.isNotBlank()) {
                    SafSink.open(context, custom, baseName)
                } else {
                    MediaStoreSink.open(context, zipUri, baseName)
                }
            }
        }
    }

    private class MediaStoreSink(
        private val context: Context,
        private val baseRelPath: String,
        override val folderName: String,
    ) : Sink {

        override fun create(relPath: String, created: MutableList<Uri>): Uri {
            val leaf = relPath.substringAfterLast('/')
            val dirPart = relPath.substringBeforeLast('/', "")
            val rel = baseRelPath + folderName + "/" +
                if (dirPart.isEmpty()) "" else "$dirPart/"
            val existing = findEntry(leaf, rel)
            if (existing != null) {
                // Duplicate path inside the same archive: the
                // later copy wins (desktop-unzip behaviour);
                // the Uri is already on the rollback list.
                return existing
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, leaf)
                put(MediaStore.Downloads.MIME_TYPE, PublicStorage.mimeOf(leaf))
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection(), values)
                ?: throw IllegalStateException("在下载目录里建不了文件")
            created.add(uri)
            return uri
        }

        override fun finishFile(uri: Uri) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, values, null, null) }
        }

        private fun findEntry(displayName: String, relPath: String): Uri? {
            val cursor = runCatching {
                context.contentResolver.query(
                    collection(),
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(displayName, relPath),
                    null,
                )
            }.getOrNull()
            cursor?.use {
                if (it.moveToFirst()) {
                    return Uri.withAppendedPath(collection(), it.getLong(0).toString())
                }
            }
            return null
        }

        /** True when anything already lives under [relPrefix]. */
        private fun folderTaken(relPrefix: String): Boolean {
            val cursor = runCatching {
                context.contentResolver.query(
                    collection(),
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("$relPrefix%"),
                    null,
                )
            }.getOrNull()
            cursor?.use { return it.count > 0 }
            return false
        }

        companion object {
            private fun collection(): Uri =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            fun open(context: Context, zipUri: Uri, baseName: String): MediaStoreSink {
                // The folder sits NEXT TO the archive: its own
                // RELATIVE_PATH (root or a category subfolder).
                val base = PublicStorage.relativePathOf(context, zipUri)
                val probe = MediaStoreSink(context, base, baseName)
                var candidate = baseName
                var n = 1
                while (probe.folderTaken(base + candidate + "/")) {
                    candidate = "$baseName ($n)"
                    n++
                }
                return MediaStoreSink(context, base, candidate)
            }
        }
    }

    private class SafSink(
        private val context: Context,
        private val folder: DocumentFile,
        override val folderName: String,
    ) : Sink {

        override fun create(relPath: String, created: MutableList<Uri>): Uri {
            val segments = relPath.split('/')
            var dir = folder
            for (seg in segments.dropLast(1)) {
                val next = dir.findFile(seg)
                dir = when {
                    next != null && next.isDirectory -> next
                    next != null -> throw IllegalStateException(
                        "解压路径被同名文件挡住了：$seg"
                    )
                    else -> dir.createDirectory(seg)
                        ?: throw IllegalStateException("解压时建不了文件夹")
                }
            }
            val leaf = segments.last()
            val existing = dir.findFile(leaf)
            if (existing != null && existing.isFile) {
                // Duplicate path inside the same archive: the
                // later copy wins (see MediaStoreSink).
                return existing.uri
            }
            val doc = dir.createFile(PublicStorage.mimeOf(leaf), leaf)
                ?: throw IllegalStateException("解压时建不了文件")
            created.add(doc.uri)
            return doc.uri
        }

        override fun finishFile(uri: Uri) = Unit

        companion object {
            fun open(context: Context, treeUriText: String, baseName: String): SafSink {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriText))
                    ?: throw IllegalStateException("自定义文件夹打不开，没法解压")
                var candidate = baseName
                var n = 1
                while (tree.findFile(candidate) != null) {
                    candidate = "$baseName ($n)"
                    n++
                }
                val folder = tree.createDirectory(candidate)
                    ?: throw IllegalStateException("在自定义文件夹里建不了解压文件夹")
                return SafSink(context, folder, candidate)
            }
        }
    }
}
