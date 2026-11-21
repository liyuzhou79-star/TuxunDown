package com.tuxundown.android

import com.tuxundown.android.sniff.LinkRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkRouterTest {

    private val kataDirect =
        "https://media.katagotraining.org/uploaded/networks/models/kata1/" +
            "kata1-b28c512nbt-s13255194368-d5935380940.bin.gz"

    @Test
    fun kataDirectLinkIsAFileByExtension() {
        // The exact link the user pasted: a 271 MB .bin.gz direct file.
        assertEquals(true, LinkRouter.looksLikeFileUrl(kataDirect))
    }

    @Test
    fun binaryContentTypeRoutesToDirectFile() {
        // Real headers measured for the KataGo link: application/octet-stream.
        assertEquals(
            LinkRouter.Route.DIRECT_FILE,
            LinkRouter.decide("application/octet-stream", null, kataDirect),
        )
        assertEquals(
            LinkRouter.Route.DIRECT_FILE,
            LinkRouter.decide("application/octet-stream", null, "https://x.com/get?id=7"),
        )
    }

    @Test
    fun htmlRoutesToWebPage() {
        assertEquals(
            LinkRouter.Route.WEB_PAGE,
            LinkRouter.decide("text/html; charset=utf-8", null, "https://katagotraining.org/networks/kata1/"),
        )
    }

    @Test
    fun contentDispositionAlwaysMeansFile() {
        assertEquals(
            LinkRouter.Route.DIRECT_FILE,
            LinkRouter.decide("text/html", "attachment; filename=\"a.zip\"", "https://x.com/dl"),
        )
    }

    @Test
    fun knownExtensionWinsOverTextType() {
        assertEquals(
            LinkRouter.Route.DIRECT_FILE,
            LinkRouter.decide("text/plain", null, "https://x.com/files/a.zip"),
        )
    }

    @Test
    fun plainPageWithoutHeadersGoesToSniffer() {
        assertEquals(
            LinkRouter.Route.WEB_PAGE,
            LinkRouter.decide("text/plain", null, "https://x.com/some/page"),
        )
    }
}
