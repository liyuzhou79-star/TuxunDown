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

    /**
     * Audit round 28: finalize found the .part entry GONE — it
     * was deleted mid-download (file manager, cleaner app, the
     * volume went away), so the downloaded bytes went into an
     * unlinked entry and there is nothing left to rename. A
     * distinct type so the service can fail the task honestly
     * instead of reporting DONE with a savedUri pointing at a
     * dead entry, and instead of the generic "rename failed"
     * note (which promises a .part file that no longer exists).
     */
    class TargetGoneException(message: String) : IllegalStateException(message)

    /**
     * v4.5 (audit round 30): the custom (SAF) folder's grant
     * is dead or unwritable — only re-picking the folder in
     * settings can fix it. A marker TYPE, like
     * [TargetGoneException], so the failure classification
     * (see FailureClassifier) judges the type, never the
     * sentence: the message wording stays free to change.
     */
    class FolderGrantException(message: String) : IllegalStateException(message)

    private const val TARGET_GONE_MESSAGE =
        "文件在下载途中被删掉了，下载结果已经不在了，请重新下载。"

    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun partName(finalName: String): String = "$finalName.part"

    /**
     * v2.3: free bytes on the volume downloads land on, or -1
     * when it cannot be measured honestly. Only the DEFAULT
     * location (the public Download folder on the primary shared
     * volume) has a real path to stat; a custom SAF folder is an
     * arbitrary tree Uri whose volume Android will not identify,
     * so the space pre-check skips there instead of guessing a
     * number that could block a legitimate download.
     */
    fun availableBytes(context: Context): Long {
        if (DownloadCenter.downloadDirUri.isNotBlank()) return -1
        return runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            android.os.StatFs(dir.absolutePath).availableBytes
        }.getOrDefault(-1)
    }

    // ---------- v2.6: category subfolders (setting-gated) ----------

    /** MediaStore root path downloads have always used. */
    private fun rootPath(): String = Environment.DIRECTORY_DOWNLOADS + "/"

    /**
     * The RELATIVE_PATH a NEW file called [fileName] belongs in:
     * the Download root itself, unless the 按类型分文件夹 switch
     * is on AND the name maps to a known category — then that
     * category's subfolder. Decided once, when the target is
     * created; the .part and the finished file share the folder,
     * and a resume reuses the persisted target Uri, so toggling
     * the switch mid-download never splits one file across two
     * folders.
     */
    fun relativePathFor(fileName: String): String {
        if (!DownloadCenter.sortSubfolders) return rootPath()
        val cat = FileCategory.folderFor(fileName) ?: return rootPath()
        return rootPath() + cat + "/"
    }

    /** The RELATIVE_PATH an existing MediaStore entry lives in. */
    fun relativePathOf(context: Context, uri: Uri): String {
        val cursor = runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null
            )
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val v = runCatching { it.getString(0) }.getOrNull()
                if (!v.isNullOrBlank()) return v
            }
        }
        return rootPath()
    }

    /** The category subfolder a finished task landed in (for the
     *  completion note), or null for the Download root / a custom
     *  SAF folder. */
    fun categoryFolderOf(context: Context, task: TaskRecord): String? {
        if (DownloadCenter.downloadDirUri.isNotBlank()) return null
        val uriText = task.savedUri.ifBlank { task.targetUri }
        if (uriText.isBlank()) return null
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        return FileCategory.categoryOfPath(relativePathOf(context, uri))
    }

    /** Every RELATIVE_PATH the file scans must look in: the root,
     *  plus the category subfolders while the switch is on (files
     *  sorted there must stay visible to the files page and the
     *  orphan cleanup — a sorted file is not a lost file). */
    private fun scanPaths(): List<String> {
        if (!DownloadCenter.sortSubfolders) return listOf(rootPath())
        return listOf(rootPath()) + FileCategory.ALL.map { rootPath() + it + "/" }
    }

    /** SAF equivalent of [scanPaths]: the tree root's files plus,
     *  while the switch is on, the files inside any category
     *  subfolder the tree happens to contain. */
    private fun listScanFiles(tree: DocumentFile): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()
        for (f in tree.listFiles()) {
            if (f.isFile) {
                out.add(f)
            } else if (DownloadCenter.sortSubfolders && f.isDirectory &&
                f.name in FileCategory.ALL
            ) {
                for (inner in f.listFiles()) {
                    if (inner.isFile) out.add(inner)
                }
            }
        }
        return out
    }

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
            // v4.2: the fine zone belongs to the discarded grid.
            task.fineChunkSize = 0
            task.fineChunkCount = 0
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
                ?: throw FolderGrantException("自定义文件夹打不开，请在设置里重选下载目录")
            if (!tree.canWrite()) {
                throw FolderGrantException("自定义文件夹不可写（授权可能已失效，请在设置里重选）")
            }
            val doc = tree.findFile(part)
                ?: tree.createFile("application/octet-stream", part)
                ?: throw FolderGrantException("在自定义文件夹里建不了文件")
            doc.uri
        } else {
            // v2.6: with 按类型分文件夹 on, the .part is born in
            // the category subfolder and never moves afterwards.
            val relPath = relativePathFor(name)
            findDownloadsEntry(context, part, relPath)
                ?: insertDownloadsEntry(context, part, relPath)
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
            // Audit round 28: the old code swallowed the rename
            // result and fell back to the (possibly dead) part
            // Uri, so a .part deleted mid-download — or a rename
            // that silently did not happen — still reported DONE.
            // Now: a missing document is TargetGone; a rename
            // that failed while the bytes still sit there under
            // the .part name throws, so the caller's catch keeps
            // the task DONE with the honest ".part suffix" note
            // instead of a success note naming a file that is
            // not there under that name.
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: throw TargetGoneException(TARGET_GONE_MESSAGE)
            if (!doc.exists()) throw TargetGoneException(TARGET_GONE_MESSAGE)
            var renamed = doc.name == finalName
            if (!renamed) {
                renamed = runCatching { doc.renameTo(finalName) }
                    .getOrDefault(false)
            }
            val found = tree?.findFile(finalName)
            if (found != null) {
                task.savedUri = found.uri.toString()
                displayName = finalName
            } else if (renamed) {
                task.savedUri = uri.toString()
                displayName = finalName
            } else if (doc.exists()) {
                throw IllegalStateException("改名没成功")
            } else {
                throw TargetGoneException(TARGET_GONE_MESSAGE)
            }
        } else {
            // MediaStore, audit A1: v1.2 DELETED any same-name entry
            // already sitting in the Download folder — including the
            // user's own pre-existing files. Never again: if a
            // different entry already owns the final name, ours is
            // renamed to the first free "name (n).ext" instead (the
            // Uri/id stays stable either way).
            // v2.6: the collision search stays inside the folder
            // THIS file actually lives in (root or a category
            // subfolder) — same names in different folders are
            // different files and never collide.
            val relPath = relativePathOf(context, uri)
            val finalName = freeName(name) { candidate ->
                findDownloadsEntry(context, candidate, relPath) != null &&
                    findDownloadsEntry(context, candidate, relPath) != uri
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(finalName))
            }
            // Audit round 28: the update's row count used to be
            // ignored — when the .part entry was deleted
            // mid-download, update() updates 0 rows and the task
            // still reported DONE with a savedUri pointing at a
            // dead entry. 0 rows is the honest signal the entry
            // is gone (mid-download detection on an open pfd is
            // not possible: writes keep landing in the unlinked
            // entry), so fail loudly instead.
            val rows = resolver.update(uri, values, null, null)
            if (rows <= 0) throw TargetGoneException(TARGET_GONE_MESSAGE)
            task.savedUri = uri.toString()
            displayName = finalName
        }
        task.savedName = displayName
        return displayName
    }

    /**
     * v3.2: write a small FINISHED text file (the sniff-list
     * export) into the download location — the SAF folder when
     * one is chosen, otherwise the public Download root (never
     * a category subfolder: this is a document the user goes
     * looking for, not a download). Name collisions take the
     * first free "name (n).ext", like finalize (audit A1).
     * Returns the display name actually used.
     */
    fun saveTextFile(context: Context, name: String, text: String): String {
        val resolver = context.contentResolver
        val bytes = text.toByteArray(Charsets.UTF_8)
        val custom = DownloadCenter.downloadDirUri
        if (custom.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(custom))
                ?: throw IllegalStateException("自定义文件夹打不开，请在设置里重选下载目录")
            val finalName = freeName(name) { candidate -> tree.findFile(candidate) != null }
            val doc = tree.createFile("text/plain", finalName)
                ?: throw IllegalStateException("在自定义文件夹里建不了文件")
            val out = resolver.openOutputStream(doc.uri, "w")
                ?: throw IllegalStateException("写不进自定义文件夹")
            out.use { it.write(bytes) }
            return finalName
        }
        val finalName = freeName(name) { candidate ->
            findDownloadsEntry(context, candidate, rootPath()) != null
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, rootPath())
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        val uri = resolver.insert(downloadsCollection(), values)
            ?: throw IllegalStateException("在公共下载目录里建不了文件")
        val out = resolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("写不进公共下载目录")
        out.use { it.write(bytes) }
        return finalName
    }

    /** First free variant of [name]: name, "name (1).ext", …
     *  The search itself lives in [FreeName] (audit round 28:
     *  bounded, with a timestamp fallback — the old unbounded
     *  loop here spun one storage query per candidate for as
     *  deep as a pile of numbered copies happened to be). */
    private fun freeName(name: String, taken: (String) -> Boolean): String =
        FreeName.pick(name, System.currentTimeMillis(), taken)

    // ---------- v1.7: orphan .part cleanup ----------

    /** A ".part" entry in the download location that NO task references. */
    data class OrphanPart(val uri: Uri, val name: String, val size: Long)

    /**
     * Scan the CURRENT download location (public Download root, or
     * the user's SAF folder) for ".part" files that no task in the
     * store points at — leftovers from deleted tasks, crashed runs
     * or manual file-manager shuffles. Referenced .part files (any
     * live or paused task's target) are never reported.
     *
     * Audit round 28, two deliberate judgements:
     * - The referenced set covers tasks of EVERY status, DONE and
     *   FAILED included. A FAILED task's .part is its resume data
     *   and a DONE task's leftover .part (finalize failed) is
     *   still claimed by a visible card that can open/delete it —
     *   neither is an orphan, and reporting them would let the
     *   cleanup delete files a record still points at.
     * - A .part modified within [OrphanGrace.GRACE_MS] is never
     *   reported either (see OrphanGrace for the birth race).
     */
    fun scanOrphanParts(context: Context): List<OrphanPart> {
        val referenced = HashSet<String>()
        for (t in DownloadCenter.store.tasks) {
            if (t.targetUri.isNotBlank()) referenced.add(t.targetUri)
            if (t.savedUri.isNotBlank()) referenced.add(t.savedUri)
        }
        val out = mutableListOf<OrphanPart>()
        // Audit round 28: modification-time grace — a .part
        // touched within OrphanGrace.GRACE_MS may belong to a
        // task whose targetUri assignment is still in flight
        // (obtainTarget inserts the entry before the assignment
        // lands), so it is never reported (see OrphanGrace).
        val nowMs = System.currentTimeMillis()
        val custom = DownloadCenter.downloadDirUri
        if (custom.isNotBlank()) {
            val tree = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(custom))
            }.getOrNull()
            if (tree != null && tree.canRead()) {
                for (f in listScanFiles(tree)) {
                    val name = f.name ?: continue
                    if (!name.endsWith(".part", ignoreCase = true)) continue
                    if (f.uri.toString() in referenced) continue
                    if (OrphanGrace.isFresh(
                            runCatching { f.lastModified() }.getOrDefault(0),
                            nowMs,
                        )
                    ) continue
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
                        // Audit round 28: the grace check needs
                        // the modification time (seconds here).
                        MediaStore.Downloads.DATE_MODIFIED,
                    ),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                        "${MediaStore.Downloads.RELATIVE_PATH} IN " +
                        "(${scanPaths().joinToString(",") { "?" }})",
                    arrayOf("%.part", *scanPaths().toTypedArray()),
                    "${MediaStore.Downloads.DISPLAY_NAME} ASC",
                )
            }.getOrNull()
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val modIdx = it.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (it.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        downloadsCollection(), it.getLong(idIdx).toString()
                    )
                    if (uri.toString() in referenced) continue
                    // MediaStore DATE_MODIFIED is SECONDS.
                    val modifiedMs = runCatching { it.getLong(modIdx) * 1000 }
                        .getOrDefault(0)
                    if (OrphanGrace.isFresh(modifiedMs, nowMs)) continue
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
                for (f in listScanFiles(tree)) {
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
                    "${MediaStore.Downloads.RELATIVE_PATH} IN " +
                        "(${scanPaths().joinToString(",") { "?" }})",
                    scanPaths().toTypedArray(),
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

    // ---------- v6.7.0 Step B: reconciliation probes ----------

    /**
     * Three-state existence probe for DONE records' savedUris
     * (audit round 52 calibers). Runs ONLY when the caller asks
     * (reconciliation, page load) on a background thread — never
     * from any periodic tick.
     *
     * MediaStore path: one resolver query per Uri (_ID +
     * DISPLAY_NAME + SIZE) — 0 rows = MISSING, a row = EXISTS
     * with the live name/size, a throwing/null query = UNKNOWN.
     * SAF path: scan membership is positive proof of life; else
     * DocumentFile.exists(); when the tree itself is unreadable
     * EVERY verdict is UNKNOWN (a dead grant must not turn the
     * whole library into ghosts at once).
     */
    fun probeRecords(
        context: Context,
        records: List<Reconcile.RecordSnap>,
        scan: List<ScannedFile>,
    ): Map<String, Reconcile.Probe> {
        val out = HashMap<String, Reconcile.Probe>()
        val custom = DownloadCenter.downloadDirUri
        if (custom.isNotBlank()) {
            val tree = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(custom))
            }.getOrNull()
            if (tree == null || !tree.canRead()) {
                for (r in records) out[r.savedUri] = Reconcile.Probe(Reconcile.Verdict.UNKNOWN)
                return out
            }
            val scanByUri = scan.associateBy { it.uri.toString() }
            for (r in records) {
                val inScan = scanByUri[r.savedUri]
                if (inScan != null) {
                    out[r.savedUri] = Reconcile.Probe(
                        Reconcile.Verdict.EXISTS, inScan.name, inScan.size,
                    )
                    continue
                }
                out[r.savedUri] = runCatching {
                    val doc = DocumentFile.fromSingleUri(context, Uri.parse(r.savedUri))
                    when {
                        doc == null -> Reconcile.Probe(Reconcile.Verdict.UNKNOWN)
                        doc.exists() -> Reconcile.Probe(
                            Reconcile.Verdict.EXISTS, doc.name, runCatching { doc.length() }.getOrNull(),
                        )
                        else -> Reconcile.Probe(Reconcile.Verdict.MISSING)
                    }
                }.getOrDefault(Reconcile.Probe(Reconcile.Verdict.UNKNOWN))
            }
            return out
        }
        for (r in records) {
            out[r.savedUri] = probeMediaStoreUri(context, r.savedUri)
        }
        return out
    }

    /**
     * v7.4 (audit round 59, C-2a): the live display name of the
     * entry at [uriString], or null when no entry provably
     * exists there. Same two mechanisms the reconcile probes
     * above use: a MediaStore row query first (0 rows = gone),
     * then DocumentFile.exists() for providers the query cannot
     * speak for (SAF). A name is returned ONLY for an entry that
     * exists — null is the "cannot prove it" answer, never a
     * guess. Used by the service's orphaned-finalize rescue to
     * prove a DONE record's ".part" is really still on disk.
     */
    fun entryDisplayName(context: Context, uriString: String): String? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val cursor = runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null,
            )
        }.getOrNull()
        if (cursor != null) {
            cursor.use {
                return runCatching {
                    if (!it.moveToFirst()) null
                    else {
                        val idx = it.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else null
                    }
                }.getOrNull()
            }
        }
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            ?: return null
        return if (runCatching { doc.exists() }.getOrDefault(false)) {
            runCatching { doc.name }.getOrNull()
        } else {
            null
        }
    }

    private fun probeMediaStoreUri(context: Context, uriString: String): Reconcile.Probe {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return Reconcile.Probe(Reconcile.Verdict.UNKNOWN)
        val cursor = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                ),
                null, null, null,
            )
        }.getOrNull() ?: return Reconcile.Probe(Reconcile.Verdict.UNKNOWN)
        cursor.use {
            return runCatching {
                if (!it.moveToFirst()) {
                    Reconcile.Probe(Reconcile.Verdict.MISSING)
                } else {
                    val nameIdx = it.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(MediaStore.Downloads.SIZE)
                    Reconcile.Probe(
                        Reconcile.Verdict.EXISTS,
                        if (nameIdx >= 0) it.getString(nameIdx) else null,
                        if (sizeIdx >= 0) it.getLong(sizeIdx) else null,
                    )
                }
            }.getOrDefault(Reconcile.Probe(Reconcile.Verdict.UNKNOWN))
        }
    }

    /**
     * v7.7.0 Step C2: delete a DONE task's saved file through
     * its savedUri, honestly. Probe (the reconcile three-state
     * probe, verbatim) -> delete ([deleteByUri]: DocumentFile
     * for SAF documents, ContentResolver.delete for
     * MediaStore entries — the existing per-provider paths) ->
     * probe again, and [DeleteFile.outcome] maps the three
     * facts to DELETED / ALREADY_GONE / FAILED. The task
     * record is NOT touched here. Background thread only.
     */
    fun deleteSavedFile(context: Context, task: TaskRecord): DeleteFile.Outcome {
        val snap = Reconcile.RecordSnap(
            id = task.id,
            savedUri = task.savedUri,
            savedName = task.savedName.ifBlank { task.fileName },
            size = if (task.totalSize > 0) task.totalSize else task.downloaded,
        )
        fun probe(): Reconcile.Verdict? =
            probeRecords(context, listOf(snap), emptyList())[task.savedUri]?.verdict
        val before = probe()
        if (before == Reconcile.Verdict.MISSING) {
            return DeleteFile.Outcome.ALREADY_GONE
        }
        val deleted = deleteByUri(context, task.savedUri)
        return DeleteFile.outcome(before, deleted, probe())
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

    private fun findDownloadsEntry(
        context: Context,
        displayName: String,
        relPath: String = rootPath(),
    ): Uri? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            downloadsCollection(),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(displayName, relPath),
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return Uri.withAppendedPath(downloadsCollection(), it.getLong(0).toString())
            }
        }
        return null
    }

    private fun insertDownloadsEntry(
        context: Context,
        displayName: String,
        relPath: String = rootPath(),
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            // The public Download folder ROOT by default; a
            // category subfolder only when the v2.6 switch is on
            // (see relativePathFor).
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        return context.contentResolver.insert(downloadsCollection(), values)
            ?: throw IllegalStateException("在公共下载目录里建不了文件")
    }
}
