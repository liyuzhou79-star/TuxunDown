package com.tuxundown.android.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest protection for the one real secret the app holds: the
 * download proxy's password (v2.3, audit round 9).
 *
 * Up to v2.2 the password sat in SharedPreferences as PLAIN TEXT.
 * MODE_PRIVATE keeps other apps out on a healthy device, but the
 * value was readable in any backup extraction or on a rooted
 * phone, and it is a reusable credential for the user's proxy
 * account. It now lives in the prefs file only as an AES-256/GCM
 * ciphertext whose key is generated inside the Android Keystore
 * and never leaves it. The stored form is:
 *
 *   "v1:" + base64( IV (12 B) || ciphertext+tag )
 *
 * Failure policy, deliberately conservative:
 * - encrypt() returns null when the Keystore is unusable; the
 *   caller then keeps the legacy plain form (no worse than
 *   v2.2 — a broken-Keystore device is not made worse, just
 *   not better) and the migration retries on the next read.
 * - decrypt() returns "" on ANY failure (key invalidated by a
 *   lock-screen change, corrupted value): the proxy simply
 *   asks for the password again. It never throws and never
 *   falls back to treating a ciphertext as the password.
 * - a stored value WITHOUT the "v1:" prefix is a legacy plain
 *   password from <= v2.2; [DownloadCenter] migrates it on
 *   first read.
 *
 * Platform crypto only (minSdk 29 >> the API 23 these classes
 * need) — no new dependency, per the size red line.
 */
object SecretStore {

    private const val KEY_ALIAS = "tuxundown_proxy_secret"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Encrypt [plain]; null when the device Keystore refuses. */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }.getOrNull()

    /** Decrypt a value produced by [encrypt]; "" on any failure. */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return ""
        return runCatching {
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_BYTES),
            )
            String(
                cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES),
                Charsets.UTF_8,
            )
        }.getOrDefault("")
    }
}
