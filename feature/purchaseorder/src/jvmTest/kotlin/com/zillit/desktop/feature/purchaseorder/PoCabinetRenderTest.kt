package com.zillit.desktop.feature.purchaseorder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.purchaseorder.cabinet.PoCabinetPage
import com.zillit.desktop.feature.purchaseorder.cabinet.PurchaseOrderCabinetViewer
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import kotlin.test.Test

/**
 * The File Cabinet's purchase-order window draws: the list with the cabinet's
 * chips, an order opened in display mode, and the viewer itself end to end.
 * Assertions are existence only, as in the other render tests.
 */
@OptIn(ExperimentalTestApi::class)
class PoCabinetRenderTest {

    private val viewer = PoViewer(userId = "", departmentIdentifier = null, designationIdentifier = null)

    private fun state(vararg orders: PurchaseOrder) = PoUiState(
        viewer = viewer,
        destination = PoDestination.AllPos,
        readOnly = true,
        orders = orders.toList(),
        departments = listOf(PoDepartment("dept-cam", "Camera")),
    )

    private val posted = parityOrder("po-1", PoStatus.Posted)

    @Test
    fun `the list shows the cabinet's chips and no Pending one`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        Box(Modifier.width(1100.dp).height(700.dp)) { PoCabinetPage(state(posted), onEvent = {}) }
                    }
                }
                listOf("All", "Approved", "Rejected", "Posted", "Closed").forEach { chip ->
                    // "All" and "Posted" also title the department picker and the row's pill.
                    onAllNodesWithText(chip).onFirst().assertExists()
                }
                onNodeWithText("Pending").assertDoesNotExist()
                onAllNodesWithText("Panavision").onFirst().assertExists()
            }
        }
    }

    @Test
    fun `an empty archive says so`() {
        runComposeUiTest {
            setContent { ZillitTheme { Box(Modifier.fillMaxSize()) { PoCabinetPage(state(), onEvent = {}) } } }
            onNodeWithText("No purchase orders").assertExists()
        }
    }

    /** An opened order offers the PDF and nothing that writes — whoever the viewer is. */
    @Test
    fun `an opened order is read-only`() {
        val rejected = parityOrder("po-2", PoStatus.Rejected, raisedBy = "")
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    Box(Modifier.width(1100.dp).height(700.dp)) {
                        PoCabinetPage(state(rejected).copy(detail = rejected), onEvent = {})
                    }
                }
            }
            onNodeWithText("View PDF").assertExists()
            onNodeWithText("Back").assertExists()
            listOf("Approve", "Reject", "Edit", "Process", "Delete PO", "Reassign").forEach { label ->
                onNodeWithText(label).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `the viewer loads the archive and draws it`() {
        val fake = object : PurchaseOrderRepository by FlowFake(emptyList()) {
            override suspend fun cabinetOrders() = ZillitResult.Success(listOf(posted))
        }
        val viewer = PurchaseOrderCabinetViewer(fake)
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    Box(Modifier.width(1100.dp).height(700.dp)) { viewer.Content(null, Modifier.fillMaxSize()) }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PO-0001").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PO-0001").assertExists()
        }
    }
}
