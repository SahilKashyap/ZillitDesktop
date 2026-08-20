package com.zillit.desktop.feature.assetreport

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import com.zillit.desktop.feature.assetreport.ui.AssetDetail
import com.zillit.desktop.feature.assetreport.ui.AssetEvent
import com.zillit.desktop.feature.assetreport.ui.AssetScreen
import com.zillit.desktop.feature.assetreport.ui.AssetUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Composes the register table and the detail overlay. */
@OptIn(ExperimentalTestApi::class)
class AssetScreenRenderTest {

    private val line = AssetLine(
        lineItemId = "l1",
        description = "Camera dolly",
        quantity = 2.0,
        unitPrice = 100.5,
        total = 201.0,
        account = "4200",
        vendorId = "v1",
        departmentId = "d1",
        currency = "GBP",
        expenditureType = ExpenditureType.Rent,
        poNumber = "PO-7",
        poId = "p1",
        category = AssetCategory.Keep,
    )

    private val state = AssetUiState(
        lines = listOf(line),
        vendors = mapOf("v1" to "Grip Hire Ltd"),
        departments = mapOf("d1" to "Camera"),
        viewer = AssetViewer(canView = true, canPost = true, canDownload = true, ready = true),
    )

    @Test
    fun `the table shows the row across its columns and opens the detail`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        setContent { ZillitTheme { AssetScreen(state = state, onEvent = { events += it }) } }

        onNodeWithText("Asset Register").assertExists()
        onNodeWithText("Camera dolly").assertExists()
        onNodeWithText("Grip Hire Ltd").assertExists()
        onNodeWithText("Camera").assertExists()
        onNodeWithText("PO-7").assertExists()
        onNodeWithText("Rental").assertExists()
        onNodeWithText("201.00").assertExists()
        onNodeWithText("Export PDF").assertExists()
        onNodeWithText("Camera dolly").performClick()

        assertTrue(events.contains(AssetEvent.Open("l1")))
    }

    @Test
    fun `the detail shows the category cards and the note`() = runComposeUiTest {
        val detail = AssetDetail(
            line = line,
            isHydrating = false,
            record = AssetRecord(id = "a1", category = AssetCategory.Keep, comments = "In store B"),
            categoryDraft = AssetCategory.Keep,
            noteDraft = "In store B",
        )
        setContent {
            ZillitTheme { AssetScreen(state = state.copy(detail = detail), onEvent = {}) }
        }

        onNodeWithText("Keep").assertExists()
        onNodeWithText("Retain in inventory").assertExists()
        onNodeWithText("List on wrap sale").assertExists()
        onNodeWithText("In store B").assertExists()
        onNodeWithText("Saved").assertExists()
    }

    @Test
    fun `hydration keeps the editor shut`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                AssetScreen(
                    state = state.copy(detail = AssetDetail(line = line, isHydrating = true)),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("Loading the register record…").assertExists()
    }
}
