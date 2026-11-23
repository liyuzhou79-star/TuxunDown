package com.tuxundown.android

import com.tuxundown.android.core.SecretStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round 40: SecretStore had NO test. encrypt / decrypt
 * are Android-Keystore-bound and cannot run on the JVM, but
 * isEncrypted is the pure gate the whole proxy-password
 * migration stands on (DownloadCenter migrates a stored value
 * on read IFF this says it is not yet encrypted, and decrypt
 * refuses to run without the prefix): a wrong verdict either
 * re-encrypts a ciphertext as if it were a password, or
 * treats a plain password as a ciphertext and loses it.
 */
class SecretStoreTest {

    @Test
    fun prefixedValuesAreEncrypted() {
        assertTrue(SecretStore.isEncrypted("v1:QUJDZA=="))
        assertTrue(SecretStore.isEncrypted("v1:"))
    }

    @Test
    fun plainPasswordsAreNotEncrypted() {
        assertFalse(SecretStore.isEncrypted("hunter2"))
        assertFalse(SecretStore.isEncrypted(""))
        // Near-misses must not pass: the prefix is exact,
        // case-sensitive, colon included.
        assertFalse(SecretStore.isEncrypted("v1"))
        assertFalse(SecretStore.isEncrypted("V1:QUJD"))
        assertFalse(SecretStore.isEncrypted(" v1:QUJD"))
        assertFalse(SecretStore.isEncrypted("xv1:QUJD"))
    }
}
