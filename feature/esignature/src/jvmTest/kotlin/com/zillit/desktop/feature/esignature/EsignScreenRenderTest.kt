package com.zillit.desktop.feature.esignature

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.ui.EsignScreen
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.ManageState
import kotlin.test.Test

/**
 * Composes the real e-signature screen.
 *
 * The module had none: its view model was well covered and its layout was
 * not, which is the half where a screen throws on open rather than returning
 * a wrong value.
 */
@OptIn(ExperimentalTestApi::class)
class EsignScreenRenderTest {

    /** Envelopes, in both themes. */
    @Test
    fun `the envelope list draws in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        EsignScreen(state = envelopes(), onEvent = {})
                    }
                }
                onNodeWithText("Crew deal memo").assertExists()
            }
        }
    }

    /** The signing surface is a different layout entirely. */
    @Test
    fun `the signing surface composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    EsignScreen(
                        state = envelopes().copy(surface = EsignSurface.Sign),
                        onEvent = {},
                    )
                }
            }
        }
    }

    /** Nothing sent yet — the state a production starts in. */
    @Test
    fun `an empty list composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    EsignScreen(state = EsignUiState(viewer = viewer()), onEvent = {})
                }
            }
        }
    }

    /** Denied by the rights grid: the screen must still draw its refusal. */
    @Test
    fun `a blocked viewer composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    EsignScreen(
                        state = EsignUiState(
                            viewer = EsignViewer(canView = false, canPost = false, ready = true),
                        ),
                        onEvent = {},
                    )
                }
            }
        }
    }

    private fun viewer() = EsignViewer(canView = true, canPost = true, canDownload = true, ready = true)

    private fun envelopes() = EsignUiState(
        viewer = viewer(),
        currentUserId = "u1",
        surface = EsignSurface.Manage,
        manage = ManageState(
            rows = listOf(
                Envelope(
                    id = "e1",
                    title = "Crew deal memo",
                    description = "For signature by Monday",
                    status = EnvelopeStatus.Unknown,
                    createdBy = "u1",
                    sentOn = "26 Aug 2026",
                ),
            ),
        ),
    )


    /**
     * The flip: the send button stays for a reader without posting rights.
     *
     * Hiding it left them with a tool that looked broken. It is here, and
     * `EsignViewModel.refusesPost` answers the press by offering to ask an
     * admin — which is the only thing that changes the answer.
     */
    @Test
    fun `a reader without posting rights still sees the send button`() {
        val reader = envelopes().copy(
            viewer = EsignViewer(canView = true, canPost = false, ready = true),
        )

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { EsignScreen(state = reader, onEvent = {}) }
            }
            onNodeWithText("Send for e-signature").assertExists()
        }
    }
}
