package com.zillit.desktop.feature.assetreport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetCurrencies
import com.zillit.desktop.feature.assetreport.domain.AssetCurrency
import com.zillit.desktop.feature.assetreport.domain.AssetDepartment
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetPerson
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import com.zillit.desktop.feature.assetreport.ui.AssetDetail
import com.zillit.desktop.feature.assetreport.ui.AssetEvent
import com.zillit.desktop.feature.assetreport.ui.AssetScreen
import com.zillit.desktop.feature.assetreport.ui.AssetUiState
import com.zillit.desktop.feature.assetreport.ui.DraftFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the register and the detail in each of their states.
 *
 * Both filter pickers hold a lazy list inside a popup — the arrangement that
 * throws rather than degrades when its height is wrong — so each is *opened*,
 * not just composed.
 */
@OptIn(ExperimentalTestApi::class)
class AssetScreenRenderTest {

    private val dolly = AssetLine(
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
        rentalStartMillis = 1_788_264_000_000L,
        rentalEndMillis = 1_790_769_600_000L,
        poNumber = "PO-7",
        poId = "p1",
        category = AssetCategory.Keep,
    )
    private val gels = dolly.copy(
        lineItemId = "l2",
        description = "Lighting gels",
        expenditureType = ExpenditureType.Consumption,
        category = AssetCategory.None,
        total = 99.0,
        vendorId = "v2",
        departmentId = "d2",
        poNumber = "PO-9",
    )

    private val poster = AssetViewer(canView = true, canPost = true, ready = true)

    private val state = AssetUiState(
        lines = listOf(dolly, gels),
        vendors = mapOf("v1" to "Grip Hire Ltd", "v2" to "Lee Filters"),
        departments = listOf(AssetDepartment("d1", "Grip"), AssetDepartment("d2", "Electrical")),
        departmentsLoading = false,
        people = mapOf("u7" to AssetPerson("u7", "Alex Reed", "Key Grip")),
        currencies = AssetCurrencies(
            options = listOf(
                AssetCurrency("GBP", "Pound Sterling", "£", 1.0),
                AssetCurrency("USD", "US Dollar", "$", 1.25),
            ),
            defaultCode = "GBP",
        ),
        isLoading = false,
        viewer = poster,
    )

    private val receipt = AssetAttachment(
        media = "p/actual/receipt.pdf",
        bucket = "zillit-eu",
        region = "eu-west-2",
        name = "receipt.pdf",
        contentType = "document",
        contentSubtype = "pdf",
    )

    private val savedRecord = AssetRecord(
        id = "a1",
        lineItemId = "l1",
        category = AssetCategory.Keep,
        comments = "In store B",
        commentBy = "u7",
        commentAtMillis = 1_787_140_800_000L,
        attachments = listOf(receipt),
    )

    private val openDetail = AssetDetail(
        line = dolly.copy(assetId = "a1"),
        isHydrating = false,
        record = savedRecord,
        categoryDraft = AssetCategory.Keep,
        noteDraft = "In store B",
        files = listOf(DraftFile.Saved(receipt)),
    )

    private fun content(state: AssetUiState, dark: Boolean = false, onEvent: (AssetEvent) -> Unit = {}) =
        @Composable {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                Box(Modifier.size(WIDTH, HEIGHT)) { AssetScreen(state = state, onEvent = onEvent) }
            }
        }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.exists(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // -- the register ----------------------------------------------------------------------

    @Test
    fun `the register shows each line across the web's columns`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        setContent(content(state) { events += it })

        listOf("CODE", "ASSET", "VENDOR", "DEPARTMENT", "REF", "EXP. TYPE", "QTY", "UNIT COST").forEach {
            assertTrue(exists(it), "column $it")
        }
        onNodeWithText("Camera dolly").assertExists()
        onNodeWithText("KEEP").assertExists()
        onNodeWithText("Grip Hire Ltd").assertExists()
        onNodeWithText("Electrical").assertExists()
        onNodeWithText("PO-7").assertExists()
        onNodeWithText("RENTAL").assertExists()
        onNodeWithText("CONSUMABLES").assertExists()
        onNodeWithText("1 Sep 2026 – 30 Sep 2026").assertExists()
        assertTrue(exists("£100.50"), "unit cost, with the currency's symbol")
        onNodeWithText("£201.00").assertExists()
        onNodeWithText("2 assets · £300.00").assertExists()
        assertEquals(2, onAllNodesWithText("TOTAL").fetchSemanticsNodes().size, "the column and the pinned bar")

        onNodeWithText("Camera dolly").performClick()
        assertTrue(AssetEvent.Open("l1") in events)
    }

    @Test
    fun `loading lays skeleton rows on the grid and holds the total back`() = runComposeUiTest {
        setContent(content(state.copy(isLoading = true, lines = emptyList())))
        onNodeWithText("CODE").assertExists()
        assertEquals(1, onAllNodesWithText("TOTAL").fetchSemanticsNodes().size, "the column only, no pinned bar")
        assertTrue(!exists("assets ·"), "no count while loading")
        assertTrue(!exists("No assets match your filters."))
    }

    @Test
    fun `a filter that matches nothing says so`() = runComposeUiTest {
        setContent(content(state.copy(query = "no such asset")))
        onNodeWithText("No assets match your filters.").assertExists()
        onNodeWithText("0 assets · £0.00").assertExists()
    }

    @Test
    fun `only the privileged see the department picker`() = runComposeUiTest {
        setContent(content(state.copy(viewer = AssetViewer(canView = true, ready = true))))
        assertTrue(!exists("All departments"))
        onNodeWithText("CURRENCY").assertExists()
    }

    /** Opened, not just composed: a lazy list measured for its intrinsic height inside a popup throws. */
    @Test
    fun `the department picker opens, counts and toggles`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        // No lines, so every name on screen is the picker's own.
        setContent(content(state.copy(lines = emptyList(), departmentFilter = listOf("d1"))) { events += it })

        onNodeWithText("Grip").performClick()
        onNodeWithText("2 options · 1 selected").assertExists()
        onNodeWithText("Electrical").performClick()
        assertTrue(AssetEvent.FilterDepartments(listOf("d1", "d2")) in events)
    }

    @Test
    fun `the currency picker lists codes over names`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        setContent(content(state) { events += it })

        onNodeWithText("GBP").performClick()
        onNodeWithText("US Dollar").assertExists()
        onNodeWithText("USD").performClick()
        assertEquals(AssetEvent.PickCurrency("USD"), events.last())
    }

    @Test
    fun `the export menu offers both formats`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        setContent(content(state) { events += it })

        onNodeWithText("Export").performClick()
        onNodeWithText("DOWNLOAD AS").assertExists()
        onNodeWithText("Editable spreadsheet with live data").assertExists()
        onNodeWithText("Export PDF").performClick()
        assertEquals(AssetEvent.Export(AssetExportFormat.Pdf), events.last())
    }

    @Test
    fun `an export in flight says so`() = runComposeUiTest {
        setContent(content(state.copy(exporting = AssetExportFormat.Excel)))
        onNodeWithText("Exporting…").assertExists()
    }

    // -- one asset -------------------------------------------------------------------------------

    @Test
    fun `the detail lays out the facts, the category, the note and the files`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        setContent(content(state.copy(detail = openDetail)) { events += it })

        onNodeWithText("ASSET REGISTER").assertExists()
        listOf("VENDOR", "EXPENSE TYPE", "UNIT COST", "Retain in inventory", "List on wrap sale").forEach {
            assertTrue(exists(it), it)
        }
        onNodeWithText("4200 · PO-7").assertExists()
        onNodeWithText("In store B").assertExists()
        onNodeWithText("Last saved by Alex Reed · Key Grip · 19 Aug 2026").assertExists()
        onNodeWithText("1 file").assertExists()
        assertTrue(exists("PDF"), "a document tile wears its type")
        // Nothing pending: the header's Save rests as "Saved".
        onNode(hasText("Saved") and hasClickAction()).assertIsNotEnabled()

        onNodeWithText("List on wrap sale").performClick()
        assertTrue(AssetEvent.PickCategory(AssetCategory.Sell) in events)
        onNodeWithContentDescription("Remove receipt.pdf", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `unsaved edits wake both saves`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        val dirty = openDetail.copy(categoryDraft = AssetCategory.Sell, noteDraft = "Moved to store C")
        setContent(content(state.copy(detail = dirty)) { events += it })

        onNodeWithText("Unsaved changes").assertExists()
        onAllNodesWithText("Save").onFirst().assertIsEnabled().performClick()
        assertTrue(events.any { it == AssetEvent.SaveDetails || it == AssetEvent.SaveNote })
    }

    @Test
    fun `a record still loading keeps the editors shut`() = runComposeUiTest {
        setContent(content(state.copy(detail = AssetDetail(line = dolly, isHydrating = true))))
        assertEquals(2, onAllNodesWithText("Loading…").fetchSemanticsNodes().size, "the note and the files")
    }

    @Test
    fun `a record that failed to load offers a retry`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        setContent(
            content(state.copy(detail = AssetDetail(line = dolly, isHydrating = false, hydrationFailed = true))) {
                events += it
            },
        )
        // "Try Again": the Android key's capitalisation, kept for its 21 translations.
        onNodeWithText("Try Again").performClick()
        assertTrue(AssetEvent.RetryRecord in events)
    }

    @Test
    fun `no files yet is one drop zone`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        val empty = openDetail.copy(record = savedRecord.copy(attachments = emptyList()), files = emptyList())
        setContent(content(state.copy(detail = empty)) { events += it })

        onNodeWithText("0 files").assertExists()
        onNodeWithText("Images or PDF · up to 10MB").assertExists()
        onNodeWithText("Click to upload or drag & drop").performClick()
        assertTrue(AssetEvent.AddFiles in events)
    }

    @Test
    fun `picks waiting for save are counted`() = runComposeUiTest {
        val pending = openDetail.copy(files = openDetail.files + DraftFile.Pending("pick-1", "shelf.png", 2_000))
        setContent(content(state.copy(detail = pending)))
        onNodeWithText("2 files · 1 pending").assertExists()
    }

    @Test
    fun `leaving with unsaved changes asks first`() = runComposeUiTest {
        val events = mutableListOf<AssetEvent>()
        val asking = openDetail.copy(noteDraft = "Changed", confirmLeave = true)
        setContent(content(state.copy(detail = asking)) { events += it })

        onNodeWithText("This asset has changes that haven't been saved. Save them or discard before leaving.")
            .assertExists()
        onNodeWithText("Discard").assertExists()
        // "Save & Leave": the Android key's capitalisation, kept for its 21 translations.
        onNodeWithText("Save & Leave").performClick()
        assertEquals(AssetEvent.SaveAndClose, events.last())
    }

    @Test
    fun `the viewer opens on a file and says when it cannot show it`() = runComposeUiTest {
        val viewing = openDetail.copy(viewing = DraftFile.Saved(receipt).key)
        setContent(content(state.copy(detail = viewing)))
        onNodeWithText("Failed to load attachment").assertExists()
    }

    @Test
    fun `both screens compose in dark mode`() = runComposeUiTest {
        setContent(content(state.copy(detail = openDetail), dark = true))
        onNodeWithText("ASSET REGISTER").assertExists()
    }

    @Test
    fun `a blocked viewer is told, not shown the register`() = runComposeUiTest {
        setContent(content(state.copy(viewer = AssetViewer(canView = false, ready = true))))
        onNodeWithText("You don't have access to the Asset Register.").assertExists()
    }

    private companion object {
        val WIDTH = 1440.dp
        val HEIGHT = 900.dp
    }
}
