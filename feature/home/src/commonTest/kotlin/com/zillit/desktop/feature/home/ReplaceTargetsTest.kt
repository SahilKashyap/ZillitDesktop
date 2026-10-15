package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.replaceTargets
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Replace one document" offers only what the server would accept as a target. */
class ReplaceTargetsTest {
    private fun notice(
        id: String,
        kind: NoticeKind,
        media: String = "m",
        localId: String? = null,
        at: Long = 0L,
        sendState: NoticeSendState = NoticeSendState.Sent,
    ) = Notice(
        id = id, body = "", authorName = "a", kind = kind, createdAtMillis = at,
        attachment = if (media.isBlank()) null else NoticeAttachment(media = media, fileName = "$id.pdf"),
        localId = localId,
        sendState = sendState,
    )

    @Test
    fun `documents with a server id and a file, newest first, nothing else`() {
        val rows = listOf(
            notice("old", NoticeKind.Document, at = 1),
            notice("img", NoticeKind.Image, at = 5),
            notice("going-up", NoticeKind.Document, localId = "local-1", at = 6, sendState = NoticeSendState.Sending),
            notice("nofile", NoticeKind.Document, media = "", at = 7),
            notice("new", NoticeKind.Document, at = 9),
            notice("text", NoticeKind.Text, at = 10),
            notice("", NoticeKind.Document, at = 11),
        )
        assertEquals(listOf("new", "old"), replaceTargets(rows).map { it.id })
    }

    @Test
    fun `a document uploaded in this session is offered, local id and all`() {
        // The server's answer keeps the post's local id so the optimistic card
        // can be matched to it — it is acknowledged all the same, and the web
        // offers it (`replaceableMessages` asks only for an `_id`). Keying off
        // the local id hid every upload from its own Replace picker until the
        // board was read again, which is what the live app showed.
        val justUploaded = notice("server-7", NoticeKind.Document, localId = "local-9", at = 20)
        val failed = notice("server-8", NoticeKind.Document, at = 21, sendState = NoticeSendState.Failed)
        assertEquals(listOf("server-7"), replaceTargets(listOf(justUploaded, failed)).map { it.id })
    }
}
