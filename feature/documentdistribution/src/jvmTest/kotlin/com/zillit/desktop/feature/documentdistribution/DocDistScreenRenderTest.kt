package com.zillit.desktop.feature.documentdistribution

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.ExperimentalTestApi
import com.zillit.desktop.feature.documentdistribution.ui.pages.LIBRARY_LISTING_TAG
import com.zillit.desktop.feature.documentdistribution.domain.ListUsed
import com.zillit.desktop.feature.documentdistribution.domain.SentAttachment
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistCrewMember
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.MediaKind
import com.zillit.desktop.feature.documentdistribution.domain.OpenState
import com.zillit.desktop.feature.documentdistribution.domain.RecipientStatus
import com.zillit.desktop.feature.documentdistribution.ui.ComposerStage
import com.zillit.desktop.feature.documentdistribution.ui.HistoryDetailState
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistScreen
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import com.zillit.desktop.feature.documentdistribution.domain.FolderEntry
import com.zillit.desktop.feature.documentdistribution.domain.buildUploadPlan
import com.zillit.desktop.feature.documentdistribution.ui.DocThumbnails
import com.zillit.desktop.feature.documentdistribution.ui.FolderUploadState
import com.zillit.desktop.feature.documentdistribution.ui.LibraryView
import com.zillit.desktop.feature.documentdistribution.ui.MergeAction
import com.zillit.desktop.feature.documentdistribution.ui.PreviewState
import com.zillit.desktop.feature.documentdistribution.ui.MergeState
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
                attachments = listOf(SentAttachment(documentId = "doc-1", name = "Call Sheet Day 12.pdf")),
                listsUsed = listOf(ListUsed("l1", "Full unit", listOf("ada@x.co"))),
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
    fun `a read-only viewer keeps the buttons, and each one asks`() {
        val raised = mutableListOf<DocDistEvent>()

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library, readOnly)
                            .copy(selectedDocumentIds = setOf("doc-1"), infoBannerDismissed = true),
                        // The four-point banner is taller than this test window can spare
                        // above the Actions menu; these tests are about the menu.
                        onEvent = { raised += it },
                    )
                }
            }
            // The flip QA asked for on the phones: the controls stay, so
            // somebody who cannot send can still find out why and fix it.
            onNodeWithText("Create folder").assertExists()
            onNodeWithText("Actions").assertExists()

            onNodeWithText("Actions").performClick()
            onNodeWithText("Share documents").performClick()
            onNodeWithText("Create folder").performClick()
        }

        // Neither press reached the action it names.
        assertEquals(
            listOf<DocDistEvent>(
                DocDistEvent.RequestRights(RightsKind.Post),
                DocDistEvent.RequestRights(RightsKind.Post),
            ),
            raised,
        )
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
                            .copy(selectedDocumentIds = setOf("doc-1", "doc-2"), infoBannerDismissed = true),
                        // The four-point banner is taller than this test window can spare
                        // above the Actions menu; these tests are about the menu.
                        onEvent = { if (it is DocDistEvent.Compose) composed = true },
                    )
                }
            }
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithText("Actions").performClick()
            onNodeWithText("Share documents").performClick()
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
    fun `history detail shows each copy's delivery, not just whether it was opened`() {
        val base = state(DocDistDestination.History)
        val sent = base.history.first().let { d ->
            d.copy(
                recipients = listOf(
                    d.recipients[0].copy(status = RecipientStatus.Opened),
                    d.recipients[1].copy(status = RecipientStatus.Accepted),
                    d.recipients[2].copy(status = RecipientStatus.Pending),
                ),
            )
        }
        runSkikoComposeUiTest(size = Size(1280f, 1000f)) {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = base.copy(
                            history = listOf(sent),
                            expandedDistributionId = "d1",
                            historyDetail = HistoryDetailState(id = "d1", distribution = sent, loading = false),
                        ),
                        onEvent = {},
                    )
                }
            }
            // A delivered-but-unread copy is "Delivered", never "not opened":
            // no evidence is not the same news, and a coordinator chasing
            // someone who read it with images off is the cost of conflating them.
            onAllNodesWithText("Opened").assertCountEquals(2)
            onAllNodesWithText("Delivered").assertCountEquals(2)
            onAllNodesWithText("Sending").assertCountEquals(2)
            onNodeWithText("Call Sheet Day 12.pdf").assertIsDisplayed()
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
            // Twice: the toolbar's button and the dialog's title.
            onAllNodesWithText("Compose email").assertCountEquals(2)
            // One stamped pill: the PDF. The spreadsheet's toggle is disabled
            // rather than unticked, so it never reads as a choice the sender made.
            onAllNodesWithText("watermarked").assertCountEquals(1)
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
                                stage = ComposerStage.Preview,
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

    /**
     * A production accumulates folders, and a fixed page clips whatever does
     * not fit. Verified live 2026-08-27: twelve folders, six reachable, no
     * way to scroll to the rest.
     */
    @Test
    fun `a long folder list still reaches its last folder`() {
        val many = (1..20).map { LibraryFolder(id = "f$it", name = "Folder $it") }
        val crowded = state(DocDistDestination.Library).let { base ->
            base.copy(folders = many, currentFolderId = null)
        }

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { DocDistScreen(state = crowded, onEvent = {}) }
            }
            // Reachable, not merely present: a bounded list composes only
            // what is on screen, so the twentieth folder proves itself by
            // being scrolled to.
            onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(LIBRARY_LISTING_TAG)))
                .performScrollToNode(hasText("Folder 20"))
            onNodeWithText("Folder 20").assertIsDisplayed()
        }
    }

    /**
     * The restriction banner offers a way forward, not just a diagnosis.
     *
     * QA's flip on the phones: a refusal that leaves the reader nowhere is
     * the bug. The banner already said what was missing; the button is what
     * lets them ask an admin for it, and it names the right they are short of
     * rather than always asking for the same one.
     */
    @Test
    fun `a restricted viewer is offered a way to ask for the missing right`() {
        val noPosting = full.copy(canPost = false)
        val asked = mutableListOf<DocDistEvent>()

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library).copy(viewer = noPosting),
                        onEvent = { asked += it },
                    )
                }
            }

            onNodeWithText("Request access").performClick()
        }

        assertEquals<List<DocDistEvent>>(
            listOf(DocDistEvent.RequestRights(RightsKind.Post)),
            asked,
            "the banner asked for the wrong right",
        )
    }

    /** Short of download instead: the same button, the other request. */
    @Test
    fun `a viewer who cannot download asks for download`() {
        val asked = mutableListOf<DocDistEvent>()

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library)
                            .copy(viewer = full.copy(canDownload = false)),
                        onEvent = { asked += it },
                    )
                }
            }

            onNodeWithText("Request access").performClick()
        }

        assertEquals<List<DocDistEvent>>(
            listOf(DocDistEvent.RequestRights(RightsKind.Download)),
            asked,
        )
    }

    /** Nothing missing, nothing to ask for — and no banner to ask from. */
    @Test
    fun `a viewer with every right sees no request button`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                DocDistScreen(state = state(DocDistDestination.Library), onEvent = {})
            }
        }

        onAllNodesWithText("Request access").assertCountEquals(0)
    }

    // -- merge PDFs to download or print --------------------------------------------

    private val mergeable = pdf.copy(contentType = "application/pdf")

    private fun mergeState(merge: MergeState = MergeState(documents = listOf(mergeable, sheet))) =
        state(DocDistDestination.Library).copy(
            merge = merge,
            crew = listOf(
                DocDistCrewMember("u1", "Coordinator", mailboxAddress = "coordinator@example.com", job = "Producer"),
                DocDistCrewMember(
                    "u2", "Rory", mailboxAddress = "rory@example.com", job = "Gaffer", department = "Lighting",
                ),
                DocDistCrewMember(
                    "u3", "Sam", mailboxAddress = "sam@example.com", job = "Focus Puller", department = "Camera",
                ),
            ),
            contacts = listOf(Contact("vendor@hire.co", "Hire Co", "Equipment")),
        )

    /**
     * The dialog is a lazy list in a scrolling shell, which is exactly the shape
     * that throws when one of them is measured against infinite height — and it
     * only does so on composition, so unit tests on the state cannot see it.
     */
    @Test
    fun `the merge dialog composes, light and dark`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { DocDistScreen(state = mergeState(), onEvent = {}) } }
                onNodeWithText("Merge PDFs to download or print").assertIsDisplayed()
                // FieldLabel draws its heading in capitals.
                onNodeWithText("YOUR COPY").assertIsDisplayed()
                onNodeWithText("Rory").assertIsDisplayed()
                // Crew under their department; an address-book-only person under Contacts.
                onNodeWithText("Hire Co").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the merge dialog names a file it will leave out`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { DocDistScreen(state = mergeState(), onEvent = {}) } }
            onNodeWithText("1 file will be left out").assertIsDisplayed()
            onNodeWithText("Only PDFs can be combined.").assertIsDisplayed()
        }
    }

    @Test
    fun `ticking a person and pressing Download asks for the merge`() {
        val events = mutableListOf<DocDistEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = mergeState(
                            MergeState(documents = listOf(mergeable), picked = setOf("rory@example.com")),
                        ),
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("Sam").performClick()
            onNodeWithText("Download").performClick()
        }
        assertTrue(events.any { it == DocDistEvent.MergeToggle("sam@example.com") }, events.toString())
        assertTrue(events.any { it == DocDistEvent.RunMerge(MergeAction.Download) }, events.toString())
    }

    @Test
    fun `nobody chosen leaves Print and Download dead`() {
        val events = mutableListOf<DocDistEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = mergeState(MergeState(documents = listOf(mergeable))),
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("Download").performClick()
            onNodeWithText("Print").performClick()
        }
        assertTrue(events.none { it is DocDistEvent.RunMerge }, events.toString())
    }

    @Test
    fun `the selection bar offers Merge PDFs`() {
        var asked = false
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library)
                            .copy(selectedDocumentIds = setOf("doc-1", "doc-2"), infoBannerDismissed = true),
                        onEvent = { if (it is DocDistEvent.OpenMerge) asked = true },
                    )
                }
            }
            onNodeWithText("Actions").performClick()
            onNodeWithText("Merge PDFs to download or print").performClick()
        }
        assertTrue(asked)
    }

    // -- upload a folder ------------------------------------------------------------------------------

    private fun entry(path: String, type: String = "application/pdf") = FolderEntry(path, type, 2_048) { null }

    private fun folderUploadState(): DocDistUiState {
        val plan = buildUploadPlan(listOf(entry("Docs/a.pdf"), entry("Docs/Sub/b.pdf")))
        return state(DocDistDestination.Library).copy(
            folderUpload = FolderUploadState(
                plan = plan,
                rejected = listOf(entry("Docs/run.exe", "application/octet-stream")),
                parentId = null,
                parentLabel = "the library root",
                date = "2026-10-07",
            ),
        )
    }

    @Test
    fun `the folder upload dialog shows what will be created and what will be skipped`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) { DocDistScreen(state = folderUploadState(), onEvent = {}) }
                }
                onNodeWithText("Upload Folder").assertIsDisplayed()
                onNodeWithText("2 folders", substring = true).assertIsDisplayed()
                onNodeWithText("into the library root", substring = true).assertIsDisplayed()
                onNodeWithText("Sub").assertIsDisplayed()
                onNodeWithText("1 file will be skipped").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `confirming the folder upload asks for it`() {
        val events = mutableListOf<DocDistEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(state = folderUploadState(), onEvent = { events += it })
                }
            }
            // The toolbar's Upload sits behind the dialog; the dialog's own button is the last one drawn.
            onAllNodesWithText("Upload").onLast().performClick()
        }
        assertTrue(events.any { it == DocDistEvent.ConfirmFolderUpload }, events.toString())
    }

    @Test
    fun `the Upload menu offers files and a folder`() {
        val events = mutableListOf<DocDistEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(state = state(DocDistDestination.Library), onEvent = { events += it })
                }
            }
            onNodeWithText("Upload").performClick()
            onNodeWithText("Upload Files").assertIsDisplayed()
            onNodeWithText("Upload Folder").performClick()
        }
        assertTrue(events.any { it == DocDistEvent.PickAndUploadFolder }, events.toString())
    }

    @Test
    fun `the Upload menu is there at the library root, where a folder may be dropped`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(state = state(DocDistDestination.Library).copy(currentFolderId = null), onEvent = {})
                }
            }
            onNodeWithText("Upload").assertIsDisplayed()
        }
    }

    // -- covers ------------------------------------------------------------------------------------------

    private val covered = pdf.copy(thumbnail = DocumentStorage("t.jpg", "bkt", "eu"))

    @Test
    fun `a grid card with a cover shows the picture, one without keeps its icon`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library).copy(
                            view = LibraryView.Grid,
                            documents = listOf(covered, sheet),
                            infoBannerDismissed = true,
                        ),
                        onEvent = {},
                        thumbnails = DocThumbnails { ImageBitmap(32, 32) },
                    )
                }
            }
            waitForIdle()
            onNodeWithContentDescription("Call Sheet Day 12.pdf").assertIsDisplayed()
            // The spreadsheet has no cover, so it keeps its file-type glyph and its name.
            onNodeWithText("Budget.xlsx").assertIsDisplayed()
        }
    }

    @Test
    fun `a cover that cannot be fetched leaves the icon, not a hole`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = true) {
                    DocDistScreen(
                        state = state(DocDistDestination.Library).copy(
                            view = LibraryView.Grid,
                            documents = listOf(covered),
                            infoBannerDismissed = true,
                        ),
                        onEvent = {},
                        thumbnails = DocThumbnails.None,
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Call Sheet Day 12.pdf").assertIsDisplayed()
        }
    }

    // -- the PDF viewer ------------------------------------------------------------------------------

    private fun pagePng(): ByteArray {
        val image = java.awt.image.BufferedImage(60, 85, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val sink = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", sink)
        return sink.toByteArray()
    }

    private fun previewState(pages: Int, drawn: Int) = state(DocDistDestination.Library).copy(
        preview = PreviewState(
            document = pdf.copy(contentType = "application/pdf"),
            loading = false,
            pages = List(drawn) { pagePng() },
            pageCount = pages,
        ),
    )

    @Test
    fun `the viewer says which page of how many, in light and dark`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { DocDistScreen(state = previewState(5, 5), onEvent = {}) } }
                onNodeWithText("Page 1 of 5").assertIsDisplayed()
                onNodeWithText("100%").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `pages still being drawn are counted from the start`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { DocDistScreen(state = previewState(23, 1), onEvent = {}) } }
            onNodeWithText("Page 1 of 23").assertIsDisplayed()
        }
    }

    @Test
    fun `zoom steps by a quarter, and fit width fills the viewer`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { DocDistScreen(state = previewState(3, 3), onEvent = {}) } }
            onNodeWithText("+").performClick()
            onNodeWithText("125%").assertIsDisplayed()
            onNodeWithText("−").performClick()
            onNodeWithText("−").performClick()
            onNodeWithText("75%").assertIsDisplayed()
            // Fit width fills the viewer; the same button then returns to reading size.
            onNodeWithText("Fit width").performClick()
            onNodeWithText("75%").assertDoesNotExist()
        }
    }

    @Test
    fun `zoom stops at half and at double`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { DocDistScreen(state = previewState(3, 3), onEvent = {}) } }
            repeat(6) { onNodeWithText("+").performClick() }
            onNodeWithText("200%").assertIsDisplayed()
            repeat(8) { onNodeWithText("−").performClick() }
            onNodeWithText("50%").assertIsDisplayed()
        }
    }
}
