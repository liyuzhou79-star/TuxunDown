package com.tuxundown.android

import com.tuxundown.android.core.CardFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.7: the card-field catalogue is pinned — and the defaults
 * reproduce the pre-3.7 card (existing rows on, new rows off).
 * A default flip here is a visible behaviour change for every
 * upgrading user, so it must be deliberate.
 * v4.6: a sixth field joins — the server-profile section
 * switch. It defaults ON by a different argument than the
 * v3.7 new rows: every row inside the section is already
 * data-gated (absent when nothing was recorded), so an
 * upgrading user with no profile data sees no change at all.
 */
class CardFieldsTest {

    @Test
    fun catalogueIsTheSixFields() {
        assertEquals(
            listOf("avgSpeed", "retryCount", "createdAt", "host", "chunks", "serverProfile"),
            CardFields.FIELDS.map { it.key },
        )
        // Labels are what the settings switches show.
        assertEquals(
            listOf("平均速度", "重试次数", "创建时间", "来源站点", "分片进度", "服务器画像"),
            CardFields.FIELDS.map { it.label },
        )
    }

    @Test
    fun defaultsMatchThePre37Card() {
        assertTrue(CardFields.defaultOf(CardFields.AVG_SPEED))
        assertTrue(CardFields.defaultOf(CardFields.RETRY_COUNT))
        assertTrue(CardFields.defaultOf(CardFields.CHUNKS))
        assertFalse(CardFields.defaultOf(CardFields.CREATED_AT))
        assertFalse(CardFields.defaultOf(CardFields.HOST))
        assertTrue(CardFields.defaultOf(CardFields.SERVER_PROFILE))
    }

    @Test
    fun unknownKeyDefaultsHidden() {
        assertFalse(CardFields.defaultOf("nope"))
        assertEquals(null, CardFields.field("nope"))
    }
}
