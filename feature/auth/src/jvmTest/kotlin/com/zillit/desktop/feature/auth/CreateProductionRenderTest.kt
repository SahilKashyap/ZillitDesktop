package com.zillit.desktop.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.auth.domain.ProductionField
import com.zillit.desktop.feature.auth.ui.CreateProductionDialog
import com.zillit.desktop.feature.auth.ui.CreateProductionUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Start Project form, rendered.
 *
 * Reported as "the Start Project button does nothing". The button, event, view
 * model and dialog were all wired and the dialog DID open — but a nested scroll
 * with a `weight` inside ZillitDialogShell's own scrolling body measured the
 * form at zero height, so it was never placed: a card with a title and a
 * Continue button and no fields.
 *
 * Checked against the UNMERGED tree with `isDisplayed()`. Both matter: the
 * dialog merges its children's semantics, so a plain `onNodeWithText` finds
 * the whole card and passes whether the form is there or not; and
 * `boundsInRoot` alone ignores clipping.
 */
@OptIn(ExperimentalTestApi::class)
class CreateProductionRenderTest {

    private fun formIsVisible(state: CreateProductionUiState) = runComposeUiTest {
        setContent { ZillitTheme { CreateProductionDialog(state, {}, {}, visible = true) } }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val section = onNodeWithText("Who you are", useUnmergedTree = true)
        assertTrue(
            section.isDisplayed(),
            "the form must be on screen, not collapsed to nothing " +
                "(bounds=${section.fetchSemanticsNode().boundsInRoot})",
        )
    }

    @Test
    fun `pressing Start Project shows the form`() = formIsVisible(CreateProductionUiState())

    /** A failed preset load puts an error beside the form; the form must survive it. */
    @Test
    fun `the form stays visible when a request has failed`() =
        formIsVisible(CreateProductionUiState(error = "Could not load project types"))

    /** Pressing Continue with gaps shows per-field errors, which live inside the form. */
    @Test
    fun `the form stays visible while showing field errors`() =
        formIsVisible(CreateProductionUiState(fieldErrors = ProductionField.entries.associateWith { "Required" }))
}
