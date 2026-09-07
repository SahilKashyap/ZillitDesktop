package com.zillit.desktop.feature.castboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.castboard.domain.CastingEntry
import com.zillit.desktop.feature.castboard.domain.CastingUnit
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingViewer
import com.zillit.desktop.feature.castboard.ui.CastingScreen
import com.zillit.desktop.feature.castboard.ui.CastingUiState
import kotlin.test.Test

/** Composes the real board in each state, light and dark. */
@OptIn(ExperimentalTestApi::class)
class CastingScreenRenderTest {

    @Test
    fun `a board of characters draws in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        CastingScreen(state = loaded(), onEvent = {})
                    }
                }
                onNodeWithText("Inspector Rao").assertExists()
                onNodeWithText("Ravi Menon, Arjun Das").assertExists()
            }
        }
    }

    /**
     * Moving someone between stages says so.
     *
     * The view model set the notice from the start; nothing drew it, so a
     * move that worked looked exactly like one that had not registered.
     */
    @Test
    fun `a move is confirmed on screen`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(state = loaded().copy(notice = "Moved to Shortlisted"), onEvent = {})
                }
            }
            onNodeWithText("Moved to Shortlisted").assertExists()
        }
    }

    @Test
    fun `with nothing to report no confirmation is drawn`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(state = loaded(), onEvent = {})
                }
            }
            onAllNodesWithText("Moved to Shortlisted").assertCountEquals(0)
        }
    }

    /** A character nobody is up for yet says so, rather than showing blank. */
    @Test
    fun `a character with no candidate says so`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(
                        state = loaded().copy(
                            entries = listOf(CastingEntry(id = "9", characterName = "Villager 2")),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No candidate yet").assertExists()
        }
    }

    /** An empty stage names the stage and the list. */
    @Test
    fun `an empty stage is explained`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(state = loaded().copy(entries = emptyList()), onEvent = {})
                }
            }
            onNodeWithText("Nobody at this stage yet").assertExists()
        }
    }

    /** A search with no hits says so against the words typed. */
    @Test
    fun `a fruitless search is explained`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(state = loaded().copy(query = "zzz"), onEvent = {})
                }
            }
            onNodeWithText("Nobody by that name").assertExists()
        }
    }

    /** Neither list granted. */
    @Test
    fun `no access is stated plainly`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(
                        state = CastingUiState(viewer = CastingViewer(resolved = true)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No casting access").assertExists()
        }
    }

    private fun loaded() = CastingUiState(
        viewer = CastingViewer(
            units = listOf(
                CastingUnit(BoardTool.Casting.units[0], "unit-main", canPost = true, serverLabel = "Main cast"),
                CastingUnit(BoardTool.Casting.units[1], "unit-bg", canPost = false, serverLabel = "Background cast"),
            ),
            resolved = true,
        ),
        unit = CastingUnit(BoardTool.Casting.units[0], "unit-main", canPost = true, serverLabel = "Main cast"),
        entries = listOf(
            CastingEntry(
                id = "1",
                characterName = "Inspector Rao",
                talentNames = listOf("Ravi Menon", "Arjun Das"),
                episode = "3",
                hierarchy = "Lead",
                gender = "Male",
            ),
        ),
    )


    /**
     * The flip: the stage buttons stay on a unit this reader cannot post to.
     *
     * `CastingViewModel.move` answers the press by offering to ask an admin,
     * which is a grant that exists — a casting unit's posting right is a row in
     * the rights grid, unlike Payroll's designation-derived one.
     */
    @Test
    fun `a reader without posting rights still sees the stage buttons`() {
        val readOnlyUnit = CastingUnit(
            BoardTool.Casting.units[1],
            "unit-bg",
            canPost = false,
            serverLabel = "Background cast",
        )

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    CastingScreen(state = loaded().copy(unit = readOnlyUnit), onEvent = {})
                }
            }
            onAllNodesWithText("→ Shortlist").assertCountEquals(1)
        }
    }
}
