package com.tuxundown.android.core

/**
 * v7.2 (audit round 57, Step C): the diagnostics page's
 * 「安全现状」 section — a READ-ONLY snapshot of how this
 * install stands, security-wise, in plain words.
 *
 * Every number and name in the section comes from a RUNTIME
 * read on the device (the PackageManager's record of this
 * app's own declared permissions and components, and the
 * proxy password's actual stored form — see [SecretStore]).
 * Nothing is hard-coded: a figure the page cannot read is
 * simply ABSENT, never replaced by a made-up one. That is
 * the whole point of keeping the assembly here as pure
 * logic: the screen gathers, this object formats, and the
 * absence rules are pinned by unit tests.
 *
 * Absence rules (pinned):
 * - a null (unreadable) input drops exactly its own row;
 * - an empty / all-blank permission list drops the
 *   permission row too — "共 0 项" would be a claim the
 *   read did not make;
 * - a negative exported count is nonsense from a broken
 *   read and drops its row; a real 0 is shown as 0;
 * - when EVERY runtime read failed, the section is absent
 *   as a whole — the one fixed line (the backup redaction
 *   caliber below) rides WITH the section and never stands
 *   alone as a fake "all is well".
 *
 * The proxy password itself NEVER enters this object: the
 * only input is its storage MODE, classified from the
 * stored form's shape (blank / Keystore ciphertext /
 * legacy plain), never its value.
 */
object SecurityStatus {

    /** How the proxy password is stored on this device. */
    enum class ProxyPassStorage {
        /** No password stored at all. */
        UNSET,

        /** Stored as a [SecretStore] ciphertext: AES-256/GCM
         *  with the key held by the system Keystore. */
        KEYSTORE_ENCRYPTED,

        /** Stored, but NOT encrypted — the legacy <= v2.2
         *  plain form, kept only when the device Keystore
         *  refused to encrypt (see SecretStore's failure
         *  policy). Shown honestly, never dressed up as
         *  encrypted. */
        PLAIN_FALLBACK,
    }

    /**
     * Classify a RAW stored proxy-password value by shape
     * only. The value itself is never returned, logged or
     * displayed anywhere — this reads its prefix and its
     * blankness, nothing else.
     */
    fun proxyPassStorageOf(storedRaw: String?): ProxyPassStorage = when {
        storedRaw.isNullOrBlank() -> ProxyPassStorage.UNSET
        SecretStore.isEncrypted(storedRaw) -> ProxyPassStorage.KEYSTORE_ENCRYPTED
        else -> ProxyPassStorage.PLAIN_FALLBACK
    }

    /** The runtime reads the screen managed to make; a null
     *  field means that read failed on this device. */
    data class Snapshot(
        val permissionNames: List<String>?,
        val exportedComponentCount: Int?,
        val proxyPassStorage: ProxyPassStorage?,
    )

    /**
     * The one fixed line of the section: how exported
     * backups treat secrets, as the code actually behaves
     * today (TaskStore's export serializer +
     * DownloadCenter's settings snapshot). Download links
     * themselves are NOT redacted in a backup — they ride
     * verbatim so an import on another phone can keep
     * downloading — and the line says so, so nobody mistakes
     * a backup file for a safe thing to share around.
     */
    const val BACKUP_LINE: String =
        "导出备份会剔除 Cookie 和名字带凭据特征的请求头，也不会带上代理密码和本机专用的目标地址；" +
            "下载链接本身会原样保留，换手机导回来才能接着下载，所以备份文件请自己收好，别随手转发。"

    /** Assemble the section's display rows from a snapshot.
     *  An empty result means the section is absent. */
    fun lines(snapshot: Snapshot): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val names = snapshot.permissionNames
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (names.isNotEmpty()) {
            rows += "申请的权限" to
                "共 ${names.size} 项：${names.joinToString("、")}"
        }
        val exported = snapshot.exportedComponentCount
        if (exported != null && exported >= 0) {
            rows += "对外开放的组件" to
                "共 $exported 个在清单里标为对外开放（其他组件外部应用调不到）"
        }
        when (snapshot.proxyPassStorage) {
            ProxyPassStorage.UNSET ->
                rows += "代理密码" to "未设置"
            ProxyPassStorage.KEYSTORE_ENCRYPTED ->
                rows += "代理密码" to
                    "已设置，用系统钥匙串加密保存（AES-256/GCM）；这里只报存法，不显示密码本身"
            ProxyPassStorage.PLAIN_FALLBACK ->
                rows += "代理密码" to
                    "已设置；这台手机的系统钥匙串暂时用不了，密码先原样保存在本机，安全程度不如加密保存"
            null -> Unit // unreadable: the row is absent
        }
        if (rows.isEmpty()) return emptyList()
        rows += "备份脱敏" to BACKUP_LINE
        return rows
    }
}
