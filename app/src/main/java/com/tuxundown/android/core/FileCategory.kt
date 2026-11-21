package com.tuxundown.android.core

/**
 * v2.6: file-type → subfolder mapping for the (default-OFF)
 * 「按类型分文件夹」setting. When the switch is on, a NEW
 * download's finished file lands in a category subfolder of
 * the public Download folder ("Download/视频/…" etc.) instead
 * of the Download root. Anything unrecognised stays at the
 * root — the root is the user's standing rule, and a category
 * is only ever a place for types we actually know.
 *
 * Pure: the folder decision is made from the file name alone,
 * at target-creation time, and the subfolder names double as
 * the MediaStore RELATIVE_PATH segments.
 */
object FileCategory {

    const val VIDEO = "视频"
    const val AUDIO = "音频"
    const val IMAGE = "图片"
    const val DOCUMENT = "文档"
    const val ARCHIVE = "压缩包"
    const val APK = "APK"

    /** Every category folder, for scans that must look inside them. */
    val ALL: List<String> = listOf(VIDEO, AUDIO, IMAGE, DOCUMENT, ARCHIVE, APK)

    private val byExtension: Map<String, String> = buildMap {
        for (e in listOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
            "mpg", "mpeg", "3gp", "ts", "m2ts", "rmvb",
        )) put(e, VIDEO)
        for (e in listOf(
            "mp3", "flac", "wav", "ogg", "oga", "m4a", "aac", "opus",
            "wma", "ape", "mid", "midi",
        )) put(e, AUDIO)
        for (e in listOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico",
            "avif", "heic", "heif", "tif", "tiff",
        )) put(e, IMAGE)
        for (e in listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "epub", "mobi", "azw3", "odt", "ods", "odp",
            "rtf", "csv",
        )) put(e, DOCUMENT)
        for (e in listOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz",
            "zst", "lz4", "iso", "dmg",
        )) put(e, ARCHIVE)
        for (e in listOf("apk", "xapk", "apks", "apkm")) put(e, APK)
    }

    /** Category folder for [fileName], or null = the Download root. */
    fun folderFor(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() || ext == fileName.lowercase()) return null
        return byExtension[ext]
    }

    /** True when [relativePath] (a MediaStore RELATIVE_PATH like
     *  "Download/视频/") names one of the category subfolders. */
    fun isCategoryPath(relativePath: String): Boolean {
        val seg = relativePath.trim('/').substringAfterLast('/', "")
        return seg in ALL
    }

    /** The category named by a RELATIVE_PATH, or null for the root. */
    fun categoryOfPath(relativePath: String): String? {
        val seg = relativePath.trim('/').substringAfterLast('/', "")
        return if (seg in ALL) seg else null
    }
}
