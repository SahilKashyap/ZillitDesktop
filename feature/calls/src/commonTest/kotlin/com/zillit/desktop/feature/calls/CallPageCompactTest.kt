package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.ui.CallUiState
import com.zillit.desktop.feature.calls.ui.drawsStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * When the engine's page should draw its shrunken layout.
 *
 * The page's compact mode hides every tile but the first, plus name chips and
 * mute badges. That is right for the thumbnail and the pill and catastrophic
 * anywhere else — it renders a full-size call window showing only yourself.
 *
 * This was `!expanded || pipCompact` inline in a LaunchedEffect, which was
 * correct only while `expanded` still described the call window too. It stopped
 * being correct the moment the call got a window of its own, and nothing caught
 * it because the rule lived in an effect no test could reach.
 */
class CallPageCompactTest {

    private fun state(pipOpen: Boolean, pipCompact: Boolean, expanded: Boolean) =
        CallUiState(pipOpen = pipOpen, pipCompact = pipCompact, expanded = expanded)

    @Test
    fun `the thumbnail is compact`() {
        assertEquals(true, state(pipOpen = true, pipCompact = true, expanded = true).pageCompact)
        assertEquals(true, state(pipOpen = true, pipCompact = true, expanded = false).pageCompact)
    }

    @Test
    fun `the minimised pill in the main window is compact`() {
        assertEquals(true, state(pipOpen = false, pipCompact = false, expanded = false).pageCompact)
    }

    @Test
    fun `the stage in the main window is not compact`() {
        assertEquals(false, state(pipOpen = false, pipCompact = false, expanded = true).pageCompact)
    }

    /**
     * The regression. After a detach, TogglePip leaves `expanded` false while
     * the call window draws the full stage — the page must NOT go compact.
     */
    @Test
    fun `the full call window is never compact, whatever expanded says`() {
        for (expanded in listOf(true, false)) {
            assertFalse(
                state(pipOpen = true, pipCompact = false, expanded = expanded).pageCompact,
                "a full-size call window must draw the whole grid (expanded=$expanded)",
            )
        }
    }

    /**
     * The page and the Compose side must agree about what is on screen: the one
     * state that draws the full stage is the one state that must not be compact.
     */
    @Test
    fun `compact never contradicts the stage being drawn`() {
        val every = listOf(true, false)
        val combinations = every.flatMap { pipOpen ->
            every.flatMap { pipCompact -> every.map { expanded -> Triple(pipOpen, pipCompact, expanded) } }
        }
        // The thumbnail is the deliberate exception: it hosts the page
        // directly and never draws the Compose stage over it.
        combinations
            .filterNot { (_, pipCompact, _) -> pipCompact }
            .filter { (pipOpen, _, expanded) -> drawsStage(pipOpen, pipOpen, expanded) }
            .forEach { (pipOpen, pipCompact, expanded) ->
                assertFalse(
                    state(pipOpen, pipCompact, expanded).pageCompact,
                    "full stage but compact page (pipOpen=$pipOpen, expanded=$expanded)",
                )
            }
    }
}
