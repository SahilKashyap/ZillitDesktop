package com.zillit.desktop.feature.documentdistribution

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.documentdistribution.domain.DocDistCrewMember
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.MediaKind
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.AddressField
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistScreen
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.pages.addressFieldTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composer's To / Cc / Bcc type-ahead, driven the way a person drives it:
 * type part of a name, click the person.
 *
 * Its own class rather than another case in [DocDistScreenRenderTest] because
 * it holds state across the interaction — the screen is stateless, so the test
 * plays the view model for the one event it needs.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerTypeaheadRenderTest {

    private val viewer = DocDistViewer(
        userId = "u1",
        userEmail = "coordinator@example.com",
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
    )

    private val pdf = LibraryDocument(
        id = "doc-1",
        name = "Call Sheet Day 12.pdf",
        mediaKind = MediaKind.Pdf,
        sizeBytes = 482_000,
        documentDate = "2026-08-11",
    )

    /**
     * Picking someone from the type-ahead must add their ADDRESS.
     *
     * Reported live 2026-10-21: typing a name and clicking the suggestion left
     * the *name* in the field as an invalid recipient ("Invalid email address:
     * shamshe"). The row's click moved focus out of the token field; the field
     * commits whatever is typed when focus leaves, which cleared the input;
     * and the suggestion list — which only exists while something is typed —
     * left the composition before the click could be delivered. So the row has
     * to take the press without taking focus.
     */
    @Test
    fun `picking a suggestion adds the address, not the name typed to find them`() = runComposeUiTest {
        var shown by mutableStateOf(
            DocDistUiState(
                viewer = viewer,
                destination = DocDistDestination.Library,
                crew = listOf(
                    DocDistCrewMember(
                        "u9",
                        "Shamshe",
                        mailboxAddress = "shamshe@example.com",
                        job = "Gaffer",
                        department = "Lighting",
                    ),
                ),
                composer = ComposerState(open = true, attachments = listOf(pdf)),
            ),
        )
        setContent {
            ZillitTheme(darkTheme = false) {
                DocDistScreen(
                    state = shown,
                    onEvent = { event ->
                        if (event is DocDistEvent.ComposeAddresses) {
                            shown = shown.copy(
                                composer = shown.composer.copy(
                                    to = event.tokens.map { Recipient(it) },
                                    toInput = event.input,
                                ),
                            )
                        }
                    },
                )
            }
        }

        val toInput = onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(addressFieldTag(AddressField.To))),
            useUnmergedTree = true,
        )
        toInput.performClick()
        toInput.performTextInput("sham")
        onNodeWithText("Shamshe").performClick()

        assertEquals(listOf("shamshe@example.com"), shown.composer.to.map { it.email })
        assertEquals("", shown.composer.toInput, "the name typed to find them is not left behind")
    }
}
