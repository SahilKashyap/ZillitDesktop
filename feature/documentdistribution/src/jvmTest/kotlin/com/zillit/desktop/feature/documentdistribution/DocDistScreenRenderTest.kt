package com.zillit.desktop.feature.documentdistribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.MediaKind
import com.zillit.desktop.feature.documentdistribution.domain.OpenState
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistScreen
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the real Document Distribution screen on every destination.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual screen exercises the same layout code —
 * and catches the two failures unit tests cannot: a page that throws while
 * composing, and a `ZillitDataTable` inside a scrolling column, which is
 * measured against an infinite constraint and throws outright rather than
 * degrading. Both ship looking fine in review and blank in use.
 */
@OptIn(ExperimentalTestApi::class)
class DocDistScreenRenderTest {

    private val full = DocDistViewer(
        userId = "u1",
        userEmail = "coordinator@example.com",
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
    )

    private val readOnly = full.copy(canPost = false, canDownload = false)

    private val pdf = LibraryDocument(
        id = "doc-1",
        name = "Call Sheet Day 12.pdf",
        mediaKind = MediaKind.Pdf,
        sizeBytes = 482_000,
        documentDate = "2026-08-11",
    )

    private val sheet = LibraryDocument(
        id = "doc-2",
        name = "Budget.xlsx",
        mediaKind = MediaKind.Document,
        sizeBytes = 91_000,
        documentDate = "2026-08-10",
    )

    private fun state(
        destination: DocDistDestination,
        viewer: DocDistViewer = full,
    ) = DocDistUiState(
        viewer = viewer,
        destination = destination,
        folders = listOf(
            LibraryFolder(id = "f1", name = "Call Sheets"),
            LibraryFolder(id = "f2", name = "Day 12", parentId = "f1"),
        ),
        currentFolderId = "f1",
        documents = listOf(pdf, sheet),
        totalDocuments = 2,
        history = listOf(
            Distribution(
                id = "d1",
                subject = "Call Sheet — Day 12",
                sentAt = 1_754_000_000_000,
                sentByName = "Ada Lovelace",
                recipients = listOf(
                    DeliveryStatus(Recipient("ada@x.co", "Ada Lovelace"), "u-1", OpenState.Opened),
                    DeliveryStatus(Recipient("grace@x.co", "Grace Hopper"), "u-2", OpenState.NotOpened),
                    DeliveryStatus(Recipient("alan@x.co"), "u-3", OpenState.Unknown),
                ),
                attachmentNames = listOf("Call Sheet Day 12.pdf"),
                listsUsed = listOf("Full unit"),
            ),
        ),
        lists = listOf(
            DistributionList("l1", "Full unit", listOf(Recipient("ada@x.co", "Ada Lovelace"))),
        ),
        contacts = listOf(Contact("ada@x.co", "Ada Lovelace", "1st AD")),
        templates = listOf(EmailTemplate("t1", "Daily call sheet", "Call sheet", "<p>Attached.</p>")),
    )

    /**
     * Every page renders, driven off the enum.
     *
     * Off the enum rather than a hand-written list, so a destination added
     * later is covered without anyone remembering to add it here.
     */
    @Test
    fun `every destination composes`() {
        val destinations = DocDistDestination.entries.filter { it.visibleTo(full) }
        assertTrue(destinations.size >= DocDistDestination.entries.size)

        destinations.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        DocDistScreen(state = state(destination), onEvent = {})
                    }
                }
                // The header is constant across pages, so its presence proves
                // the frame and the page under it both composed.
                onNodeWithText("Document Distribution").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `every destination composes in dark mode too`() {
        DocDistDestination.entries.filter { it.visibleTo(full) }.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = true) {
                        DocDistScreen(state = state(destination), onEvent = {})
                    }
                }
                onNodeWithText("Document Distribution").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `a read-only viewer gets the banner and no send affordance`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library, readOnly)
                            .copy(selectedDocumentIds = setOf("doc-1")),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("New folder").assertDoesNotExist()
            onNodeWithText("Distribute").assertDoesNotExist()
        }
    }

    @Test
    fun `a blocked viewer sees one explanation rather than an empty tool`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(
                            DocDistDestination.Library,
                            full.copy(canView = false),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No access to Document Distribution").assertIsDisplayed()
        }
    }

    @Test
    fun `selecting documents offers the distribute action`() {
        var composed = false
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library)
                            .copy(selectedDocumentIds = setOf("doc-1", "doc-2")),
                        onEvent = { if (it is DocDistEvent.Compose) composed = true },
                    )
                }
            }
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithText("Distribute").performClick()
        }
        assertTrue(composed)
    }

    /**
     * The tabs are wired to the events the view model expects.
     *
     * A strip that renders but reports the wrong page is worse than one that
     * does not render, because it looks like it works.
     */
    @Test
    fun `clicking a tab asks to open that destination`() {
        var opened: DocDistDestination? = null
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library),
                        onEvent = { if (it is DocDistEvent.Open) opened = it.destination },
                    )
                }
            }
            onNodeWithText("History").performClick()
        }
        assertEquals(DocDistDestination.History, opened)
    }

    @Test
    fun `history distinguishes no read receipt from not opened`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.History)
                            .copy(expandedDistributionId = "d1"),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Opened").assertIsDisplayed()
            onNodeWithText("Not opened").assertIsDisplayed()
            // The third recipient has no evidence either way. Reporting that as
            // "not opened" is what sends a coordinator chasing someone who read
            // it with images off.
            onNodeWithText("No read receipt").assertIsDisplayed()
        }
    }

    @Test
    fun `the composer defaults every watermarkable attachment to stamped`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library).copy(
                            composer = ComposerState(
                                open = true,
                                attachments = listOf(pdf, sheet),
                                watermarked = setOf(pdf.id),
                                to = listOf(Recipient("ada@x.co", "Ada Lovelace")),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Distribute documents").assertIsDisplayed()
            // The spreadsheet says why it has no checkbox, rather than showing
            // an unticked one that reads as a choice the sender made.
            onNodeWithText("Cannot be watermarked").assertIsDisplayed()
        }
    }

    /**
     * The action row stays reachable however tall the body gets.
     *
     * `ZillitDialogShell` used to clip past its max height rather than scroll,
     * and the first thing lost is always the buttons at the bottom — the Send
     * button was half off its own card with the watermark controls open. Six
     * attachments is well past the limit; `assertIsDisplayed` is the point of
     * the test, since a clipped button still *exists*.
     */
    @Test
    fun `a tall composer keeps its send button on screen`() {
        val many = (1..6).map { index ->
            pdf.copy(id = "doc-$index", name = "Call Sheet Day $index.pdf")
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library).copy(
                            composer = ComposerState(
                                open = true,
                                attachments = many,
                                watermarked = many.map { it.id }.toSet(),
                                to = listOf(Recipient("ada@x.co", "Ada Lovelace")),
                                subject = "Call sheets",
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Send").assertIsDisplayed()
        }
    }

    @Test
    fun `the composer refuses a send with no recipients and says why`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library).copy(
                            composer = ComposerState(open = true, attachments = listOf(pdf)),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Add at least one recipient.").assertIsDisplayed()
        }
    }
}
