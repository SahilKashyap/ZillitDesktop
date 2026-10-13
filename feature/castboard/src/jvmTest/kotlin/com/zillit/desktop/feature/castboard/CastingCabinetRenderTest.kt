package com.zillit.desktop.feature.castboard

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.castboard.cabinet.CastingCabinetViewer
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingRepository
import com.zillit.desktop.feature.castboard.domain.CastingStatus
import kotlin.test.Test

/** Composes the cabinet's casting row: the board, read-only. */
@OptIn(ExperimentalTestApi::class)
class CastingCabinetRenderTest {

    private val repository = object : CastingRepository {
        override suspend fun entries(unitId: String, status: CastingStatus) = ZillitResult.Success(
            listOf(CastingEntry(id = "1", characterName = "Inspector Rao", talentNames = listOf("Ravi Menon"))),
        )
    }

    private val permissions = ProjectPermissions(
        listOf(ToolAccess(identifier = "casting_main_tool", unitId = "unit-main", canView = true, canPost = true)),
    )

    private fun viewer(rights: ProjectPermissions?) = CastingCabinetViewer(
        identifier = "casting_main",
        board = BoardTool.Casting,
        unit = BoardTool.Casting.units[0],
        repository = repository,
        permissions = { rights },
    )

    @Test
    fun `the row lists its characters and offers no way to change them`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { viewer(permissions).Content(null, Modifier) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Inspector Rao").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Ravi Menon").assertExists()
            // The live board's stage buttons, and its page header, are not in the cabinet.
            onAllNodesWithText("→ Shortlist").assertCountEquals(0)
            onAllNodesWithText("Film Tools").assertCountEquals(0)
        }
    }

    @Test
    fun `a reader without the row's tool is told so`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    viewer(ProjectPermissions(listOf(ToolAccess(identifier = "wardrobe_main_tool", canView = true))))
                        .Content(null, Modifier)
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("No casting access").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun `unreadable rights say so rather than drawing a board`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { viewer(null).Content(null, Modifier) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Something went wrong").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
