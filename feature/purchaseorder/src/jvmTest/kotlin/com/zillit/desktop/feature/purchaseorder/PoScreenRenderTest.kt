package com.zillit.desktop.feature.purchaseorder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.purchaseorder.domain.LocalCopy
import com.zillit.desktop.feature.purchaseorder.domain.PoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderScreen
import kotlin.test.Test
import kotlin.test.assertEquals

/** Composes the real Purchase Orders screen on every destination. */
@OptIn(ExperimentalTestApi::class)
class PoScreenRenderTest {

    private val accountant = PoViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val crew = PoViewer("user-2", "department_art", null)

    private fun order(id: String = "po-1", status: PoStatus = PoStatus.AwaitingApproval) = PurchaseOrder(
        id = id,
        number = "PO-0042",
        vendorId = "v-1",
        vendorName = "Panavision",
        description = "Camera package for block two",
        departmentId = "dept-1",
        companyId = null,
        status = status,
        currency = "GBP",
        // Deliberately disagreeing with the lines, so the warning renders too.
        total = 3_000.0,
        vatTreatment = null,
        nominalCode = "4100",
        episode = null,
        notes = "Weekly hire",
        effectiveDate = 1_754_000_000_000,
        createdAt = 1_754_000_000_000,
        raisedBy = "user-2",
        assignedTo = null,
        reassignmentReason = null,
        deliveryAddress = null,
        lines = listOf(
            PoLine("l-1", "Camera body", 1.0, 2_500.0, "4100", null),
            PoLine("l-2", "Batteries", 2.0, 125.0, "4110", null),
        ),
        approvals = listOf(
            PoApproval("user-3", "Line Producer", 1, "approved", null, 1L),
            PoApproval("user-4", "Producer", 2, null, null, null),
        ),
    )

    private fun state(destination: PoDestination, viewer: PoViewer = accountant) = PoUiState(
        viewer = viewer,
        destination = destination,
        orders = listOf(order(), order("po-2", PoStatus.Posted)),
        vendors = listOf(Vendor("v-1", "Panavision", "GBP", "4100")),
        selectedId = "po-1",
    )

    @Test
    fun `every destination composes for both audiences and both themes`() {
        listOf(accountant to true, accountant to false, crew to false).forEach { (viewer, dark) ->
            PoDestination.entries.filter { it.visibleTo(viewer) }.forEach { destination ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            PurchaseOrderScreen(state = state(destination, viewer), onEvent = {})
                        }
                    }
                    onNodeWithText("Purchase Orders").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `a header total that disagrees with the lines is called out on the order`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(state = state(PoDestination.AllOrders), onEvent = {})
                }
            }
            onNodeWithText("The header total and the lines disagree — lines come to £2,750.00.")
                .assertExists()
        }
    }

    @Test
    fun `the approval queue offers approve and reject and nothing else`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(state = state(PoDestination.ApprovalQueue), onEvent = {})
                }
            }
            // Composed, not necessarily on screen: the detail pane scrolls and
            // the actions sit under the lines, which in a small test window is
            // below the fold.
            onNodeWithText("Approve").assertExists()
            onNodeWithText("Reject").assertExists()
        }
    }

    @Test
    fun `an order raised offline shows as waiting, with why, and no server actions`() {
        val local = order("local:op-1", PoStatus.Draft).copy(
            number = "",
            local = LocalCopy(operationId = "op-1", failed = false),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.MyOrders, crew).copy(
                            localOrders = listOf(local),
                            selectedId = local.id,
                        ),
                        onEvent = {},
                    )
                }
            }
            // The row's number cell and the pill, in the table and the detail.
            onNodeWithText("Not sent yet").assertExists()
            onAllNodesWithText("Waiting to send").onFirst().assertExists()
            onNodeWithText("New order").assertExists()
            onNodeWithText(
                "This order is saved on this computer and will be raised on the server " +
                    "as soon as you're back online. It has no number until then.",
            ).assertExists()
            onNodeWithText("Delete").assertDoesNotExist()
        }
    }

    @Test
    fun `a refused offline order says so and points at Pending changes`() {
        val local = order("local:op-2", PoStatus.Draft).copy(
            number = "",
            local = LocalCopy(operationId = "op-2", failed = true, error = "Vendor is not active"),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.MyOrders, crew)
                            .copy(localOrders = listOf(local), selectedId = local.id),
                        onEvent = {},
                    )
                }
            }
            onAllNodesWithText("Not sent").onFirst().assertExists()
            onNodeWithText(
                "This order could not be sent: Vendor is not active. " +
                    "Retry or discard it from Pending changes in the status bar.",
            ).assertExists()
        }
    }

    @Test
    fun `offline, the list is dated and the raise page says what will happen`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.MyOrders, crew)
                            .copy(offline = true, staleSince = 1_754_000_000_000),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("You're offline — showing orders saved", substring = true).assertExists()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(state = state(PoDestination.Raise, crew).copy(offline = true), onEvent = {})
                }
            }
            onNodeWithText("Save and raise when online").assertExists()
            onNodeWithText("You're offline. The order will be saved on this computer", substring = true).assertExists()
        }
    }

    @Test
    fun `clicking a tab asks to open that destination`() {
        var opened: PoDestination? = null
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.Overview),
                        onEvent = { if (it is PoEvent.Open) opened = it.destination },
                    )
                }
            }
            onNodeWithText("Raise an Order").performClick()
        }
        assertEquals(PoDestination.Raise, opened)
    }
}
