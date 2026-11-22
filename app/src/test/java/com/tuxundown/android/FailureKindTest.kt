package com.tuxundown.android

import com.tuxundown.android.core.Errors
import com.tuxundown.android.core.FailureAdvice
import com.tuxundown.android.core.FailureClassifier
import com.tuxundown.android.core.FailureKind
import com.tuxundown.android.core.HttpFailureException
import com.tuxundown.android.core.Notes
import com.tuxundown.android.core.PublicStorage
import com.tuxundown.android.core.RetryPolicy
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.5 (audit round 30) pinning tests: for each failure
 * category, the verdict must be IDENTICAL in three forms —
 * (a) judged from the RAW failure at the failure site,
 * (b) judged from the HUMANIZED card text (the legacy
 * fallback, for verdict-less records), and (c) after a
 * serialize -> deserialize round trip of the record (the
 * form FailureAdvice actually sees after a restart).
 * Plus the drift pin: once a verdict is recorded, ANY
 * wording in task.error must be unable to move it.
 */
class FailureKindTest {

    /** One category's full pipeline, in all three forms. */
    private fun assertCategory(
        raw: Throwable,
        humanizedText: String,
        kind: FailureKind,
        adviceAction: FailureAdvice.Action?,
        needsUserAction: Boolean,
        hasSources: Boolean = false,
    ) {
        // (a) raw failure, judged where it was thrown.
        assertEquals(kind, FailureClassifier.ofThrowable(raw))
        // (b) humanized text only (a pre-v4.5 record).
        assertEquals(kind, FailureClassifier.fromErrorText(humanizedText))
        assertEquals(adviceAction, FailureAdvice.forError(humanizedText, hasSources)?.action)
        assertEquals(needsUserAction, RetryPolicy.needsUserAction(humanizedText))
        // (c) recorded verdict + humanized text, round-tripped.
        val task = TaskRecord(url = "https://example.com/f.bin")
        task.status = TaskStatus.FAILED
        task.error = humanizedText
        task.failureKind = FailureClassifier.ofThrowable(raw)
        if (hasSources) task.sources.add("https://mirror.example.com/f.bin")
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(task))).single()
        assertEquals(kind, back.failureKind)
        assertEquals(kind, FailureClassifier.verdictFor(back))
        assertEquals(adviceAction, FailureAdvice.forTask(back)?.action)
        assertEquals(needsUserAction, RetryPolicy.needsUserAction(back))
    }

    @Test
    fun storageFullAgreesInAllForms() {
        assertCategory(
            raw = java.io.IOException("write failed: ENOSPC (No space left on device)"),
            humanizedText = Notes.storageFull(),
            kind = FailureKind.STORAGE_FULL,
            adviceAction = FailureAdvice.Action.OPEN_FILES,
            needsUserAction = false,
        )
    }

    @Test
    fun storageGoneAgreesInAllForms() {
        assertCategory(
            raw = java.io.IOException("pwrite failed: EROFS (Read-only file system)"),
            humanizedText = Notes.storageDeviceGone(),
            kind = FailureKind.STORAGE_GONE,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = true,
        )
    }

    @Test
    fun folderGrantAgreesInAllForms() {
        val raw = PublicStorage.FolderGrantException(
            "自定义文件夹不可写（授权可能已失效，请在设置里重选）"
        )
        assertCategory(
            raw = raw,
            humanizedText = Errors.human(raw),
            kind = FailureKind.FOLDER_GRANT,
            adviceAction = FailureAdvice.Action.NONE,
            needsUserAction = true,
        )
    }

    @Test
    fun fileChangedAgreesInAllForms() {
        assertCategory(
            raw = IllegalStateException(Notes.fileChangedValidator()),
            humanizedText = Notes.fileChangedValidator(),
            kind = FailureKind.FILE_CHANGED,
            adviceAction = FailureAdvice.Action.MANAGE_SOURCES,
            needsUserAction = false,
            hasSources = true,
        )
        assertCategory(
            raw = IllegalStateException("服务器上的文件在下载途中变了（大小对不上），已停下重试"),
            humanizedText = "服务器上的文件在下载途中变了（大小对不上），已停下重试",
            kind = FailureKind.FILE_CHANGED,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
    }

    @Test
    fun targetGoneAgreesInAllForms() {
        val raw = PublicStorage.TargetGoneException(
            "文件在下载途中被删掉了，下载结果已经不在了，请重新下载。"
        )
        assertCategory(
            raw = raw,
            humanizedText = Errors.human(raw),
            kind = FailureKind.TARGET_GONE,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
    }

    @Test
    fun httpCategoriesAgreeInAllForms() {
        assertCategory(
            raw = HttpFailureException(403),
            humanizedText = Errors.httpMessage(403),
            kind = FailureKind.HTTP_REFUSED,
            adviceAction = FailureAdvice.Action.OPEN_BROWSER,
            needsUserAction = false,
        )
        assertCategory(
            raw = HttpFailureException(429),
            humanizedText = Errors.httpMessage(429),
            kind = FailureKind.HTTP_RATE_LIMITED,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
        assertCategory(
            raw = HttpFailureException(404),
            humanizedText = Errors.httpMessage(404),
            kind = FailureKind.HTTP_GONE,
            adviceAction = FailureAdvice.Action.NONE,
            needsUserAction = false,
        )
        assertCategory(
            raw = HttpFailureException(416),
            humanizedText = Errors.httpMessage(416),
            kind = FailureKind.HTTP_RANGE_INVALID,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
        assertCategory(
            raw = HttpFailureException(503),
            humanizedText = Errors.httpMessage(503),
            kind = FailureKind.HTTP_SERVER_ERROR,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
    }

    @Test
    fun noRangeAgreesInAllForms() {
        assertCategory(
            raw = IllegalStateException("服务器不支持 Range"),
            humanizedText = "服务器不支持 Range",
            kind = FailureKind.NO_RANGE,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
    }

    @Test
    fun networkAgreesInAllForms() {
        val raw = java.net.UnknownHostException("Unable to resolve host \"x.com\"")
        assertCategory(
            raw = raw,
            humanizedText = Errors.human(raw),
            kind = FailureKind.NETWORK,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
        val rawConnect = java.net.ConnectException("Failed to connect to /1.2.3.4:443")
        assertCategory(
            raw = rawConnect,
            humanizedText = Errors.human(rawConnect),
            kind = FailureKind.NETWORK,
            adviceAction = FailureAdvice.Action.RETRY,
            needsUserAction = false,
        )
    }

    @Test
    fun httpCodeSurvivesTheRoundTrip() {
        val task = TaskRecord(url = "https://example.com/f.bin")
        task.failureKind = FailureClassifier.ofThrowable(HttpFailureException(429))
        task.failureHttpCode = FailureClassifier.httpCodeOf(HttpFailureException(429))
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(task))).single()
        assertEquals(FailureKind.HTTP_RATE_LIMITED, back.failureKind)
        assertEquals(429, back.failureHttpCode)
    }

    @Test
    fun recordedVerdictIsImmuneToWordingDrift() {
        // THE round-30 pin: a record WITH a verdict judges by
        // the verdict alone. Rewrite task.error into ANY other
        // category's wording — or gibberish — and neither
        // consumer may move.
        val task = TaskRecord(url = "https://example.com/f.bin")
        task.status = TaskStatus.FAILED
        task.failureKind = FailureKind.FOLDER_GRANT
        task.error = "自定义文件夹不可写（授权可能已失效，请在设置里重选）"
        assertTrue(RetryPolicy.needsUserAction(task))
        assertEquals(FailureAdvice.Action.NONE, FailureAdvice.forTask(task)?.action)
        for (drifted in listOf(
            "服务器拒绝访问（HTTP 403）：可能需要登录",
            Notes.storageFull(),
            "完全没见过的新措辞",
            "",
        )) {
            task.error = drifted
            assertTrue("error=$drifted", RetryPolicy.needsUserAction(task))
            assertEquals(
                "error=$drifted",
                FailureAdvice.Action.NONE, FailureAdvice.forTask(task)?.action,
            )
        }
        // And the mirror image: a retryable verdict must not
        // be captured by user-action wording in the text.
        task.failureKind = FailureKind.HTTP_GONE
        task.error = "自定义文件夹不可写（授权可能已失效，请在设置里重选）"
        assertFalse(RetryPolicy.needsUserAction(task))
    }

    @Test
    fun verdictlessRecordsKeepTheLegacyTextBehaviour() {
        // A pre-v4.5 record: no verdict field at all in the
        // JSON (hand-built, like an old tasks.json line).
        val oldJson = """[{"id":"x1","url":"https://e.com/f","status":"FAILED",""" +
            """"error":"服务器拒绝访问（HTTP 403）：可能需要登录，或者链接已过期。"}]"""
        val back = TaskStore.deserialize(oldJson).single()
        assertEquals(FailureKind.UNKNOWN, back.failureKind)
        assertEquals(0, back.failureHttpCode)
        assertEquals(
            FailureAdvice.Action.OPEN_BROWSER, FailureAdvice.forTask(back)?.action
        )
        assertFalse(RetryPolicy.needsUserAction(back))
        // A corrupt verdict name also degrades to the fallback.
        val corrupt = TaskStore.deserialize(
            """[{"id":"x2","url":"https://e.com/f","status":"FAILED",""" +
                """"failureKind":"NOPE","error":"手机存储空间不足，下载已停下。"}]"""
        ).single()
        assertEquals(FailureKind.UNKNOWN, corrupt.failureKind)
        assertEquals(
            FailureAdvice.Action.OPEN_FILES, FailureAdvice.forTask(corrupt)?.action
        )
    }

    @Test
    fun unknownFailuresStayUnclassified() {
        assertEquals(
            FailureKind.UNKNOWN,
            FailureClassifier.ofThrowable(IllegalStateException("跳转次数太多，已停下")),
        )
        assertEquals(
            FailureKind.UNKNOWN, FailureClassifier.fromErrorText("部分分片下载失败")
        )
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.ofThrowable(null))
    }
}
