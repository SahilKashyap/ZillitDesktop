package com.zillit.desktop.feature.location

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.location.domain.LocationBadgeLeaf
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationUnread
import com.zillit.desktop.feature.location.ui.LocationScreen
import com.zillit.desktop.feature.location.ui.LocationUiState
import kotlin.test.Test

/**
 * The badges inside the tool draw from the ledger's leaves: the status tab
 * wears its total, the folder tile its own. Before this, a tool's inner
 * screens had nothing to draw — the whole tool was read the moment its
 * window fronted.
 */
@OptIn(ExperimentalTestApi::class)
class LocationBadgeRenderTest {

    @Test
    fun `the status tab and the folder tile wear their unread`() = runComposeUiTest {
        val unread = LocationUnread(
            listOf(
                LocationBadgeLeaf(LocationStatus.Selected, "Lucknow", "12", "", "", 3),
                LocationBadgeLeaf(LocationStatus.Selected, "Delhi", "4", "", "", 1),
            ),
        )
        setContent {
            ZillitTheme {
                LocationScreen(
                    state = LocationUiState(
                        info = listOf(
                            LocationInfo("Lucknow", listOf("12"), emptyList(), emptyList(), 0L, false),
                            LocationInfo("Delhi", listOf("4"), emptyList(), emptyList(), 0L, false),
                        ),
                        unread = unread,
                    ),
                    onEvent = {},
                    loadImage = { _, _ -> null },
                    resolveUser = { null },
                )
            }
        }
        // The Selected tab: both folders' rows; the Lucknow tile: its own three.
        onNodeWithText("4").assertExists()
        onNodeWithText("3").assertExists()
        onNodeWithText("1").assertExists()
    }
}
