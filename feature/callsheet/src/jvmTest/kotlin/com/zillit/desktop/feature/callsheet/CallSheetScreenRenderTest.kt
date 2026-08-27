package com.zillit.desktop.feature.callsheet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.ui.CallSheetDestination
import com.zillit.desktop.feature.callsheet.ui.CallSheetScreen
import com.zillit.desktop.feature.callsheet.ui.CallSheetUiState
import kotlin.test.Test

/**
 * Composes the real call-sheet screen at each of its destinations.
 *
 * The module had no render coverage: a screen that throws on open is exactly
 * what its view-model tests cannot see.
 */
@OptIn(ExperimentalTestApi::class)
class CallSheetScreenRenderTest {

    @Test
    fun `drafts draw in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        CallSheetScreen(state = loaded(), onEvent = {})
                    }
                }
                onNodeWithText("Day 12 — Studio floor").assertExists()
            }
        }
    }

    /** Every destination is its own layout, so every one must compose. */
    @Test
    fun `each destination composes`() {
        CallSheetDestination.entries.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        CallSheetScreen(
                            state = loaded().copy(destination = destination),
                            onEvent = {},
                        )
                    }
                }
            }
        }
    }

    /** Nothing made yet — the first day of a production. */
    @Test
    fun `an empty tool composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CallSheetScreen(
                        state = CallSheetUiState(viewer = CallSheetViewer(canView = true)),
                        onEvent = {},
                    )
                }
            }
        }
    }

    private fun sheet(id: String, status: CallSheetStatus) = CallSheetSummary(
        id = id,
        serialNo = "12",
        name = "Day 12 — Studio floor",
        status = status,
        createdBy = "Aisha Khan",
        createdById = "u1",
        createdAt = "26 Aug 2026",
        updatedAt = "26 Aug 2026",
        publishedAt = "",
    )

    private fun loaded() = CallSheetUiState(
        viewer = CallSheetViewer(
            userId = "u1",
            displayName = "Aisha Khan",
            designation = "1st AD",
            canView = true,
            canPost = true,
        ),
        destination = CallSheetDestination.Drafts,
        drafts = listOf(sheet("cs1", CallSheetStatus.Draft)),
        sent = listOf(sheet("cs2", CallSheetStatus.PendingApproval)),
        received = listOf(sheet("cs3", CallSheetStatus.PendingInternalApproval)),
        finalized = listOf(sheet("cs4", CallSheetStatus.ApprovedForPublish)),
    )
}
