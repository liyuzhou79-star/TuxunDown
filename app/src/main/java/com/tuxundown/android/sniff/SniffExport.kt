package com.tuxundown.android.sniff

import com.tuxundown.android.core.ShareList

/**
 * v3.2: export one sniff session (the resources found on a
 * page) as a .txt link list in the download folder.
 *
 * The format is deliberately THE SAME as the queue share list
 * (ShareList): the two fixed header lines come from ShareList
 * itself, then per resource an optional name line and the bare
 * link line. Both import doors eat it back unchanged — the
 * .txt importer picks out the lines that start with a scheme
 * (the name and header lines are skipped), and batch paste
 * extracts the links from the whole text. A list exported here
 * can be re-imported on this phone or sent to another one and
 * rebuilt there; the round trip is pinned by SniffExportTest.
 *
 * Only names and links travel — no cookies, no headers, no
 * page credentials (the sniffer's results never held any).
 *
 * Pure — unit-tested.
 */
object SniffExport {

    /** Default file name for the export (English on purpose:
     *  the user's archive tools garble non-ASCII names). */
    const val FILE_NAME = "tuxundown-sniff-list.txt"

    fun build(resources: List<SniffedResource>): String {
        val sb = StringBuilder()
        sb.append(ShareList.headerLine(resources.size)).append("\n")
        sb.append(ShareList.HOW_TO_LINE).append("\n")
        // v7.2 (audit round 57): same share-time credential
        // reminder as the queue share list (ShareList) — one
        // extra line, only when a link in THIS list carries a
        // query string or userinfo; the format is otherwise
        // unchanged and both import doors skip the line.
        ShareList.credentialHint(resources.map { it.url })
            ?.let { sb.append(it).append("\n") }
        for (r in resources) {
            sb.append("\n")
            if (r.name.isNotBlank()) sb.append(r.name).append("\n")
            sb.append(r.url).append("\n")
        }
        return sb.toString()
    }
}
