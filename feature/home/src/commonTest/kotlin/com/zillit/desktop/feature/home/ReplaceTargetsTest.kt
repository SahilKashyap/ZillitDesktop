package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
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
    ) = Notice(
        id = id, body = "", authorName = "a", kind = kind, createdAtMillis = at,
        attachment = if (media.isBlank()) null else NoticeAttachment(media = media, fileName = "$id.pdf"),
        localId = localId,
    )

    @Test
    fun `documents with a server id and a file, newest first, nothing else`() {
        val rows = listOf(
            notice("old", NoticeKind.Document, at = 1),
            notice("img", NoticeKind.Image, at = 5),
            notice("pending", NoticeKind.Document, localId = "local-1", at = 6),
            notice("nofile", NoticeKind.Document, media = "", at = 7),
            notice("new", NoticeKind.Document, at = 9),
            notice("text", NoticeKind.Text, at = 10),
        )
        assertEquals(listOf("new", "old"), replaceTargets(rows).map { it.id })
    }
}
