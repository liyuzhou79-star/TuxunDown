package com.tuxundown.android

import com.tuxundown.android.core.HelpTopics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The help center is pure data now: pin the topic count, keep
 * every title unique and non-blank and every body non-blank,
 * and make sure the key newer topics stay on the list — a
 * topic dropped by accident is a help page the user loses.
 */
class HelpTopicsTest {

    @Test
    fun has41Topics() {
        assertEquals(41, HelpTopics.topics.size)
    }

    @Test
    fun titlesAreUniqueAndNonBlank() {
        val titles = HelpTopics.topics.map { it.title }
        assertTrue(titles.all { it.isNotBlank() })
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun bodiesAreNonBlank() {
        for (topic in HelpTopics.topics) {
            assertTrue("blank body for ${topic.title}", topic.body.isNotBlank())
        }
    }

    @Test
    fun keyNewTopicsArePresent() {
        val titles = HelpTopics.topics.map { it.title }.toSet()
        val expected = listOf(
            "长按任务卡能做什么？",
            "多选任务怎么进？",
            "统计页的『最快纪录』是什么？",
            "短链接要先展开吗？",
            "复制链接后打开 App 时的提示是怎么回事？",
            "下载失败了，会通知我吗？",
            "文件页的『一键对账』是什么？",
            "下完的文件能直接删吗？「删除文件」和删记录有什么区别？",
            "「尾巴双下（实验）」是什么？要不要开？",
            "任务详情里的「全程速度曲线」是什么？",
            "连接数对比的结果能复制吗？",
            "连接为什么是「渐进放行」？诊断页的「资源」和「连接放行形态」是什么？",
            "有哪些已知的限制？",
        )
        for (title in expected) {
            assertTrue("missing help topic: $title", title in titles)
        }
        // The condensed limits topic stays last.
        assertEquals("有哪些已知的限制？", HelpTopics.topics.last().title)
    }
}
