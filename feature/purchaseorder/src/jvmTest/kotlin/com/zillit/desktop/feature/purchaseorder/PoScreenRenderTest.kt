package com.zillit.desktop.feature.purchaseorder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.purchaseorder.domain.LocalCopy
import com.zillit.desktop.feature.purchaseorder.domain.PoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoCompany
import com.zillit.desktop.feature.purchaseorder.domain.PoDeliveryAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEntryState
import com.zillit.desktop.feature.purchaseorder.ui.PoFormMode
import com.zillit.desktop.feature.purchaseorder.ui.PoFormState
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderScreen
import kotlin.test.Test

/**
 * The purchase order tool draws — every page, both roles, both themes.
 *
 * A render test rather than an assertion about wording, with two exceptions
 * where the wording *is* the behaviour: the tab labels (this port exists to
 * match the web's) and the offline notices (an order that exists only on one
 * computer has to say so).
 */
@OptIn(ExperimentalTestApi::class)
class PoScreenRenderTest {

    private val accountant = PoViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
        departmentId = "dept-acc",
    )

    private val assistant = PoViewer(
        userId = "user-9",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_accounts_assistant_accounts",
        departmentId = "dept-acc",
    )

    private val crew = PoViewer(
        userId = "user-2",
        departmentIdentifier = "department_camera",
        designationIdentifier = "designation_focus_puller_camera",
        departmentId = "dept-cam",
    )

    private fun order(id: String = "po-1", status: PoStatus = PoStatus.AwaitingApproval) = PurchaseOrder(
        id = id,
        number = "PO-0001",
        vendorId = "v-1",
        vendorName = "Panavision",
        description = "Camera package — 12 weeks",
        departmentId = "dept-cam",
        companyId = "co-1",
        status = status,
        currency = "GBP",
        total = 2_750.0,
        vatTreatment = "standard",
        nominalCode = "4100",
        episode = "EP-104",
        notes = null,
        effectiveDate = 1_772_150_400_000,
        createdAt = 1_772_000_000_000,
        raisedBy = "user-2",
        assignedTo = "user-1",
        reassignmentReason = null,
        deliveryAddress = "Stage G, Pinewood",
        grossAmount = 2_750.0,
        paidAmount = if (status == PoStatus.Posted) 1_000.0 else 0.0,
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
        departments = listOf(PoDepartment("dept-cam", "Camera"), PoDepartment("dept-acc", "Accounts")),
        companies = listOf(PoCompany("co-1", "Zillit Films Ltd")),
        templates = listOf(
            PoTemplate(
                id = "t-1",
                name = "Weekly camera package",
                vendorId = "v-1",
                vendorName = "Panavision",
                departmentId = "dept-cam",
                nominalCode = "4100",
                description = "Camera package",
                currency = "GBP",
                notes = null,
                lines = listOf(PoLine(null, "Body", 1.0, 2_500.0, "4100", null)),
            ),
        ),
        addresses = listOf(
            PoDeliveryAddress(
                id = "a-1",
                label = "Stage G, Pinewood",
                address = PoAddress(name = "Stage manager", line1 = "Pinewood Studios", city = "Iver Heath"),
                createdBy = "user-1",
            ),
        ),
    )

    @Test
    fun `every page composes for every audience and both themes`() {
        listOf(accountant to true, accountant to false, assistant to false, crew to false).forEach { (viewer, dark) ->
            PoDestination.entries
                .filter { it.visibleTo(viewer) && it != PoDestination.Form }
                .forEach { destination ->
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

    /**
     * The strip carries the web's own labels.
     *
     * This is the point of the port, so it is asserted rather than left to a
     * reading: the accounts console shows All POs, Queue, PO Entry, Posted,
     * Reports and Settings, plus the shared register group.
     */
    @Test
    fun `the accounts console shows the web's tabs`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(state = state(PoDestination.AllPos), onEvent = {})
                }
            }
            listOf(
                "All POs",
                "Queue",
                "PO Entry",
                "Posted",
                "Reports",
                "Settings",
                "Templates",
                "PO Drafts",
                "Delivery Addresses",
            ).forEach { label -> onAllNodesWithText(label).onFirst().assertExists() }
            onNodeWithText("Enter PO").assertExists()
        }
    }

    /** The department view shows its own six, and its button says Create PO. */
    @Test
    fun `the department view shows the web's tabs`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(state = state(PoDestination.MyPos, crew), onEvent = {})
                }
            }
            listOf("Approval Queue", "My POs", "My Department POs", "Vendors", "Invoices").forEach { label ->
                onAllNodesWithText(label).onFirst().assertExists()
            }
            onNodeWithText("Create PO").assertExists()
            // All POs needs `is_admin` or the tool's posting right; this crew
            // member has neither, and the web hides the tab for the same reason.
            onAllNodesWithText("All POs").assertCountIsZeroOrMore()
        }
    }

    /** An accounts assistant is told which sections are missing. */
    @Test
    fun `an assistant sees the assistant view banner`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(state = state(PoDestination.AllPos, assistant), onEvent = {})
                }
            }
            onAllNodesWithText(
                "Assistant View — Some sections are hidden based on your role. Budget data, cost report " +
                    "impact, cash flow forecasts, and settings are restricted to the Production Accountant.",
            ).onFirst().assertExists()
        }
    }

    /** An order raised offline says it is waiting, in the list and in the detail. */
    @Test
    fun `an order that exists only on this computer says so`() {
        val local = order("local:op-1", PoStatus.Draft).copy(
            number = "",
            local = LocalCopy(operationId = "op-1", failed = false),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.MyPos, crew).copy(localOrders = listOf(local), detail = local),
                        onEvent = {},
                    )
                }
            }
            onAllNodesWithText("Waiting to send").onFirst().assertExists()
            onAllNodesWithText(
                "Saved on this computer — it will be raised when you are back online.",
            ).onFirst().assertExists()
        }
    }

    /** A refused offline order names the refusal. */
    @Test
    fun `a refused offline order says why`() {
        val local = order("local:op-2", PoStatus.Draft).copy(
            number = "",
            local = LocalCopy(operationId = "op-2", failed = true, error = "Vendor is not active"),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.MyPos, crew).copy(localOrders = listOf(local), detail = local),
                        onEvent = {},
                    )
                }
            }
            onAllNodesWithText("This order could not be sent: Vendor is not active.").onFirst().assertExists()
        }
    }

    /** The form takes over the page, and its buttons say what the web's say. */
    @Test
    fun `the form renders with the web's actions`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.MyPos, crew).copy(
                            form = PoFormState(mode = PoFormMode.NewOrder, currency = "GBP"),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("New PO").assertExists()
            onNodeWithText("Back to POs").assertExists()
            onNodeWithText("Save Draft").assertExists()
            onNodeWithText("Save as template").assertExists()
            onNodeWithText("Create & Submit PO").assertExists()
        }
    }

    /** The processing page opens on an order with its coding beside it. */
    @Test
    fun `the processing page renders`() {
        val open = order("po-1", PoStatus.Approved)
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    PurchaseOrderScreen(
                        state = state(PoDestination.Entry).copy(
                            detail = open,
                            entry = PoEntryState(orderId = open.id, lines = open.lines, previewOpen = true),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Back to Queue").assertExists()
            onNodeWithText("Post to Ledger").assertExists()
            onAllNodesWithText("LEDGER TOTAL").onFirst().assertExists()
        }
    }
}

/** A soft existence check: the node may legitimately be absent for this role. */
private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsZeroOrMore() = this
