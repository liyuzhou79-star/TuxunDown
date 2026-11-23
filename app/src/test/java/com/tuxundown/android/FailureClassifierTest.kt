package com.tuxundown.android

import com.tuxundown.android.core.FailureClassifier
import com.tuxundown.android.core.FailureKind
import com.tuxundown.android.core.HttpFailureException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit round 40: FailureClassifier.ofHttp had no DIRECT
 * test — it was only reached through ofThrowable for a 429
 * fixture. The table is a verdict the retry advice and the
 * failure card both render from, so every row is pinned
 * here, including the deliberate 408 -> NETWORK ruling and
 * the UNKNOWN fall-through for codes with no verdict.
 */
class FailureClassifierTest {

    @Test
    fun ofHttpTable() {
        assertEquals(FailureKind.HTTP_REFUSED, FailureClassifier.ofHttp(401))
        assertEquals(FailureKind.HTTP_REFUSED, FailureClassifier.ofHttp(403))
        assertEquals(FailureKind.HTTP_RATE_LIMITED, FailureClassifier.ofHttp(429))
        assertEquals(FailureKind.HTTP_GONE, FailureClassifier.ofHttp(404))
        assertEquals(FailureKind.HTTP_GONE, FailureClassifier.ofHttp(410))
        assertEquals(FailureKind.HTTP_RANGE_INVALID, FailureClassifier.ofHttp(416))
        assertEquals(FailureKind.NETWORK, FailureClassifier.ofHttp(408))
        assertEquals(FailureKind.HTTP_SERVER_ERROR, FailureClassifier.ofHttp(500))
        assertEquals(FailureKind.HTTP_SERVER_ERROR, FailureClassifier.ofHttp(503))
        assertEquals(FailureKind.HTTP_SERVER_ERROR, FailureClassifier.ofHttp(599))
    }

    @Test
    fun ofHttpFallsThroughToUnknown() {
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(200))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(301))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(400))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(402))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(418))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(600))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(0))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofHttp(-1))
    }

    @Test
    fun httpCodeOfWalksTheCauseChain() {
        assertEquals(0, FailureClassifier.httpCodeOf(null))
        assertEquals(0, FailureClassifier.httpCodeOf(IllegalStateException("x")))
        assertEquals(
            503,
            FailureClassifier.httpCodeOf(HttpFailureException(503)),
        )
        val wrapped = RuntimeException("wrap", HttpFailureException(404))
        assertEquals(404, FailureClassifier.httpCodeOf(wrapped))
        // An UNKNOWN-verdict HTTP failure still yields its code
        // through ofThrowable's fall-through (no verdict, code kept).
        assertEquals(
            FailureKind.UNKNOWN,
            FailureClassifier.ofThrowable(HttpFailureException(400)),
        )
    }
}
