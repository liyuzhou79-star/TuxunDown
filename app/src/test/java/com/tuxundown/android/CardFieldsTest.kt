package com.tuxundown.android

import com.tuxundown.android.core.CardFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.7: the card-field catalogue is pinned — five fields, and
 * the defaults reproduce the pre-3.7 card (existing rows on,
 * new rows off). A default flip here is a visible behaviour
 * change for every upgrading user, so it must be deliberate.
 */
class CardFieldsTest {

    @Test
    fun catalogueIsTheFiveFields() {
        assertEquals(
            listOf("avgSpeed", "retryCount", "createdAt", "host", "chunks"),
            CardFields.FIELDS.map { it.key },
        )
        // Labels are what the settings switches show.
        assertEquals(
            listOf("平均速度", "重试次数", "创建时间", "来源站点", "分片进度"),
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
    }

    @Test
    fun unknownKeyDefaultsHidden() {
        assertFalse(CardFields.defaultOf("nope"))
        assertEquals(null, CardFields.field("nope"))
    }
}
