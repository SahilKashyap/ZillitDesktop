package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.domain.PoBadgeLeaf
import com.zillit.desktop.feature.purchaseorder.domain.PoBadges
import com.zillit.desktop.feature.purchaseorder.domain.PoUnread
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoUnreadTest {

    private val unread = PoUnread(
        listOf(
            PoBadgeLeaf(PoBadges.TOOL_HUB, PoBadges.UNIT_PO, PoBadges.LEVEL_ALL_POS, "purchase_order_label", "po-1", 2),
            PoBadgeLeaf(PoBadges.TOOL_HUB, PoBadges.UNIT_PO, PoBadges.LEVEL_QUEUE, "query_chat", "po-1", 1),
            PoBadgeLeaf(PoBadges.TOOL_PO, PoBadges.UNIT_PO, PoBadges.LEVEL_MY_POS, "purchase_order_label", "po-2", 1),
            PoBadgeLeaf(
                PoBadges.TOOL_PO, PoBadges.UNIT_INVOICE, "invoice_approval_queue", "invoice_created", "inv-1", 3,
            ),
        ),
    )

    @Test
    fun `each badged tab counts its own tool and level, invoices by unit`() {
        assertEquals(2, unread.tab(PoDestination.AllPos.badgeScope))
        assertEquals(1, unread.tab(PoDestination.Queue.badgeScope))
        assertEquals(1, unread.tab(PoDestination.MyPos.badgeScope))
        assertEquals(0, unread.tab(PoDestination.ApprovalQueue.badgeScope))
        assertEquals(3, unread.invoices)
        assertNull(PoDestination.Templates.badgeScope)
        assertEquals(0, unread.tab(null))
    }

    @Test
    fun `an order's badge is its rows on the open tab only`() {
        assertEquals(2, unread.order(PoDestination.AllPos.badgeScope, "po-1"))
        assertEquals(1, unread.order(PoDestination.Queue.badgeScope, "po-1"))
        assertEquals(0, unread.order(PoDestination.MyPos.badgeScope, "po-1"))
    }
}
