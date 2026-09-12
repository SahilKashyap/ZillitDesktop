package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which page a tool route opens.
 *
 * The Account Hub embeds this tool and hands off `/new` for "Create PO"; its
 * bare path must leave the landing to the viewer's role, as the web does. The
 * segments are the web's own, which means two of them are ambiguous until the
 * role is known — see [PoDestination.forRoute].
 */
class PoRouteTest {

    private val accountant = PoViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val crew = PoViewer(
        userId = "user-2",
        departmentIdentifier = "department_camera",
        designationIdentifier = "designation_focus_puller_camera",
    )

    @Test
    fun `the bare tool path names no page`() {
        assertNull(PoDestination.forRoute("/film-tools/purchase-order"))
        assertNull(PoDestination.forRoute("/film-tools/purchase-order/"))
    }

    @Test
    fun `new is the web's spelling of the create form`() {
        assertEquals(PoDestination.Form, PoDestination.forRoute("/film-tools/purchase-order/new"))
    }

    @Test
    fun `a page segment opens that page and a deeper route keeps its first segment`() {
        assertEquals(PoDestination.Queue, PoDestination.forRoute("/film-tools/purchase-order/queue", accountant))
        assertEquals(PoDestination.MyPos, PoDestination.forRoute("/film-tools/purchase-order/my/123", crew))
        assertEquals(
            PoDestination.Templates,
            PoDestination.forRoute("/film-tools/purchase-order/templates", crew),
        )
    }

    /**
     * `/all` is two different tabs, and the role decides which.
     *
     * The web reuses the URL because it mounts two different modules there.
     * Keeping the address means a link written on the web lands on the page its
     * author meant, whichever of them opens it.
     */
    @Test
    fun `the segment the web reuses resolves by role`() {
        assertEquals(PoDestination.AllPos, PoDestination.forRoute("/film-tools/purchase-order/all", accountant))
        assertEquals(
            PoDestination.ApprovalQueue,
            PoDestination.forRoute("/film-tools/purchase-order/all", crew),
        )
    }

    /**
     * A page this viewer may not open still resolves.
     *
     * Resolution and permission are separate questions: the caller checks
     * `visibleTo` before opening, and answering null here would make a route
     * that names a real page indistinguishable from a typo.
     */
    @Test
    fun `a page the viewer cannot see still resolves to that page`() {
        assertEquals(PoDestination.Settings, PoDestination.forRoute("/film-tools/purchase-order/settings", crew))
    }

    @Test
    fun `an unknown segment opens nothing rather than guessing`() {
        assertNull(PoDestination.forRoute("/film-tools/purchase-order/overview"))
        assertNull(PoDestination.forRoute("/film-tools/purchase-order/raise"))
    }
}
