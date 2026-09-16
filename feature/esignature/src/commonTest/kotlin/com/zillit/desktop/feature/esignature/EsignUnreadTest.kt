package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.EsignBadgeLeaf
import com.zillit.desktop.feature.esignature.domain.EsignBadges
import com.zillit.desktop.feature.esignature.domain.EsignUnread
import kotlin.test.Test
import kotlin.test.assertEquals

class EsignUnreadTest {

    private val unread = EsignUnread(
        listOf(
            EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "sent", "env-1", 1),
            EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "completed", "env-1", 1),
            EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "draft", "env-2", 2),
            EsignBadgeLeaf(EsignBadges.UNIT_SIGN, "action_required", "env-3", 1),
        ),
    )

    @Test
    fun `surfaces and buckets sum their own rows`() {
        assertEquals(4, unread.manage)
        assertEquals(1, unread.sign)
        assertEquals(3, unread.manageBucket("sent", "draft"))
        assertEquals(1, unread.manageBucket("completed"))
    }

    @Test
    fun `an envelope on a tab counts only that bucket's rows, never the stage it left`() {
        assertEquals(1, unread.manageEnvelope("env-1", "sent"))
        assertEquals(1, unread.manageEnvelope("env-1", "completed"))
        assertEquals(0, unread.manageEnvelope("env-1", "rejected"))
        assertEquals(1, unread.signEnvelope("env-3"))
        assertEquals(2, unread.envelope("env-1"))
    }
}
