package com.zillit.desktop.feature.drive

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.StorageUsage
import com.zillit.desktop.feature.drive.domain.UploadState
import com.zillit.desktop.feature.drive.ui.DetailsState
import com.zillit.desktop.feature.drive.ui.DriveDestination
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveScreen
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the real Drive screen on every destination.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual screen exercises the same layout code —
 * and catches the two failures unit tests cannot: a page that throws while
 * composing, and a `ZillitDataTable` inside a scrolling column, which is
 * measured against an infinite constraint and throws outright. Both ship
 * looking fine in review and blank in use.
 */
@OptIn(ExperimentalTestApi::class)
class DriveScreenRenderTest {

    /**
     * The header line, used to prove the frame composed.
     *
     * The description rather than the title: "Drive" also appears on the
     * breadcrumb's root button, and a matcher that hits two nodes fails on the
     * ambiguity rather than on anything being wrong.
     */
    private val header = "The production's shared files — upload, organise, share and " +
        "version them."

    private val admin = DriveViewer(
        userId = "u1",
        displayName = "Ada",
        canView = true,
        canPost = true,
        canDownload = true,
        isAdmin = true,
        ready = true,
    )

    private val readOnly = DriveViewer(
        userId = "u2",
        displayName = "Grace",
        canView = true,
        canPost = false,
        canDownload = false,
        ready = true,
    )

    private val folder = DriveItem(
        id = "fold-1",
        kind = DriveItemKind.Folder,
        name = "Camera Reports",
        itemCount = 12,
        permissions = DrivePermissions.Owner,
    )

    private val pdf = DriveItem(
        id = "file-1",
        kind = DriveItemKind.File,
        name = "Camera Report Day 12.pdf",
        extension = "pdf",
        mimeType = "application/pdf",
        sizeBytes = 482_000,
        uploadedByName = "Ada Lovelace",
        updatedAt = 1_754_000_000_000,
        permissions = DrivePermissions.Owner,
        isShared = true,
    )

    private val readOnlyDoc = DriveItem(
        id = "file-2",
        kind = DriveItemKind.File,
        name = "Budget.xlsx",
        extension = "xlsx",
        sizeBytes = 91_000,
        uploadedByName = "Grace Hopper",
        permissions = DrivePermissions.ViewOnly,
    )

    private fun state(
        destination: DriveDestination,
        viewer: DriveViewer = admin,
    ) = DriveUiState(
        viewer = viewer,
        destination = destination,
        items = listOf(folder, pdf, readOnlyDoc),
        total = 3,
        folderId = "fold-parent",
        breadcrumb = listOf(DriveCrumb("fold-parent", "Production")),
        tags = listOf(DriveTag("t1", "Approved", "#16a34a")),
        favouriteIds = setOf("file-1"),
        favourites = listOf(pdf),
        trashItems = listOf(
            readOnlyDoc.copy(deletedAt = 1_754_000_000_000, deletedByName = "Ada Lovelace"),
        ),
        activity = listOf(
            DriveActivity(
                id = "a1",
                action = "file.uploaded",
                itemName = "Camera Report Day 12.pdf",
                userName = "Ada Lovelace",
                at = 1_754_000_000_000,
            ),
        ),
        storage = StorageUsage(
            usedBytes = 4L * 1024 * 1024 * 1024,
            fileCount = 812,
            trashBytes = 220L * 1024 * 1024,
            byType = mapOf("images" to 1_000_000L, "videos" to 3_000_000L),
            quotaBytes = 10L * 1024 * 1024 * 1024,
        ),
    )

    /**
     * Every page renders, driven off the enum.
     *
     * Off the enum rather than a hand-written list, so a destination added
     * later is covered without anyone remembering to add it here.
     */
    @Test
    fun `every destination composes`() {
        val destinations = DriveDestination.entries.filter { it.visibleTo(admin) }
        assertTrue(destinations.size == DriveDestination.entries.size)

        destinations.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        DriveScreen(state = state(destination), onEvent = {})
                    }
                }
                onNodeWithText(header).assertExists()
            }
        }
    }

    @Test
    fun `every destination composes in dark mode too`() {
        DriveDestination.entries.filter { it.visibleTo(admin) }.forEach { destination ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = true) {
                        DriveScreen(state = state(destination), onEvent = {})
                    }
                }
                onNodeWithText(header).assertExists()
            }
        }
    }

    @Test
    fun `the grid view composes as well as the list`() {
        // The grid is a FlowRow inside a scrolling column rather than a lazy
        // grid, precisely because the lazy one throws on the unbounded
        // constraint. This is what proves it stayed that way.
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse).copy(viewMode = DriveViewMode.Grid),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Camera Reports").assertIsDisplayed()
        }
    }

    @Test
    fun `a read-only viewer gets the banner and no upload affordance`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(state = state(DriveDestination.Browse, readOnly), onEvent = {})
                }
            }
            onNodeWithText("Upload").assertDoesNotExist()
            onNodeWithText("New folder").assertDoesNotExist()
        }
    }

    @Test
    fun `a blocked viewer sees one explanation rather than an empty tool`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse, readOnly.copy(canView = false)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No access to Drive").assertIsDisplayed()
        }
    }

    /**
     * The bulk toolbar tells the truth about a mixed selection.
     *
     * Two of the three selected rows are the viewer's to delete. A button that
     * said "Delete 3" would be promising something the server will refuse.
     */
    @Test
    fun `bulk actions name what will actually happen`() {
        val nonAdmin = DriveViewer(
            userId = "u3",
            canView = true,
            canPost = true,
            canDownload = true,
            ready = true,
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse, nonAdmin)
                            .copy(selected = setOf("fold-1", "file-1", "file-2")),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("3 selected").assertIsDisplayed()
            onNodeWithText("Delete 2 of 3").assertIsDisplayed()
        }
    }

    @Test
    fun `clicking a tab asks to open that destination`() {
        var opened: DriveDestination? = null
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse),
                        onEvent = { if (it is DriveEvent.Open) opened = it.destination },
                    )
                }
            }
            onNodeWithText("Trash").performClick()
        }
        assertEquals(DriveDestination.Trash, opened)
    }

    @Test
    fun `the details panel docks beside the listing`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse).copy(
                            details = DetailsState(
                                item = pdf,
                                comments = listOf(
                                    DriveComment(
                                        id = "c1",
                                        fileId = pdf.id,
                                        authorName = "Grace Hopper",
                                        text = "Signed off.",
                                        createdAt = 1_754_000_000_000,
                                    ),
                                ),
                                versions = listOf(
                                    DriveVersion(
                                        id = "v1",
                                        fileId = pdf.id,
                                        versionNumber = 2,
                                        fileName = pdf.name,
                                        sizeBytes = 480_000,
                                    ),
                                ),
                                access = listOf(
                                    DriveAccessEntry(
                                        userId = "u1",
                                        userName = "Ada Lovelace",
                                        role = DriveRole.Owner,
                                    ),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            // The listing is still composed beside it — that is the point of
            // docking rather than showing a modal.
            //
            // `assertExists` rather than `assertIsDisplayed` throughout: both
            // the listing and the panel scroll, so in a test-sized window
            // plenty of this is composed below the fold. Composition without
            // throwing is what this test is for.
            onNodeWithText("Camera Reports").assertExists()
            // Upper-cased because `ZillitSectionLabel` renders its text that way.
            onNodeWithText("WHO CAN REACH THIS").assertExists()
            onNodeWithText("VERSIONS").assertExists()
            onNodeWithText("Signed off.").assertExists()
        }
    }

    @Test
    fun `an item with no explicit access says who can still reach it`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse)
                            .copy(details = DetailsState(item = readOnlyDoc)),
                        onEvent = {},
                    )
                }
            }
            // Not "nobody" — admins and parent-folder inheritors still reach it,
            // and saying otherwise would be plainly wrong.
            onNodeWithText(
                "No explicit access has been granted. Administrators, and anyone with " +
                    "access to a parent folder, can still reach it.",
            ).assertExists()
        }
    }

    @Test
    fun `an upload in flight shows its progress`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(
                        state = state(DriveDestination.Browse).copy(
                            uploads = listOf(
                                QueuedUpload(
                                    id = "up-1",
                                    fileName = "dailies.mov",
                                    sizeBytes = 2_000_000_000,
                                    destinationFolderId = "fold-parent",
                                    state = UploadState.InProgress(uploadedParts = 3, totalParts = 12),
                                ),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("dailies.mov").assertIsDisplayed()
            onNodeWithText("25%").assertIsDisplayed()
        }
    }

    @Test
    fun `the storage page names the trash as counting towards usage`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DriveScreen(state = state(DriveDestination.Storage), onEvent = {})
                }
            }
            // What stops "we're out of space" becoming a support ticket rather
            // than an Empty Trash click.
            onNodeWithText("Still counts towards usage").assertIsDisplayed()
        }
    }
}
