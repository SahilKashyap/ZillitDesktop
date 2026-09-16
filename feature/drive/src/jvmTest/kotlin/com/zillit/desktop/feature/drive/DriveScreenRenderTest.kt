package com.zillit.desktop.feature.drive

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DrivePerson
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.UploadState
import com.zillit.desktop.feature.drive.ui.AccessDraft
import com.zillit.desktop.feature.drive.ui.ActivityLogState
import com.zillit.desktop.feature.drive.ui.DetailsState
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveScreen
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveViewMode
import com.zillit.desktop.feature.drive.ui.DropUploadState
import com.zillit.desktop.feature.drive.ui.EditDraft
import com.zillit.desktop.feature.drive.ui.ItemMenuState
import com.zillit.desktop.feature.drive.ui.MoveToState
import com.zillit.desktop.feature.drive.ui.NewFolderDraft
import com.zillit.desktop.feature.drive.ui.PickedFile
import com.zillit.desktop.feature.drive.ui.PreviewState
import com.zillit.desktop.feature.drive.ui.ShareLinkForm
import com.zillit.desktop.feature.drive.ui.ShareState
import com.zillit.desktop.feature.drive.ui.ShareTab
import com.zillit.desktop.feature.drive.ui.TrashState
import com.zillit.desktop.feature.drive.ui.UploadDraft
import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the real Drive screen in every state it can be in.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual screen exercises the same layout code —
 * and catches the two failures unit tests cannot: a page that throws while
 * composing, and a lazy list measured against an infinite constraint, which
 * Compose refuses outright. Both ship looking fine in review and blank in use.
 */
@OptIn(ExperimentalTestApi::class)
class DriveScreenRenderTest {

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
        parentFolderId = "fold-parent",
        createdById = "u1",
        uploadedById = "u1",
    )

    private val pdf = DriveItem(
        id = "file-1",
        kind = DriveItemKind.File,
        name = "Camera Report Day 12.pdf",
        extension = "pdf",
        mimeType = "application/pdf",
        parentFolderId = "fold-parent",
        sizeBytes = 482_000,
        uploadedByName = "Ada Lovelace",
        uploadedById = "u1",
        createdById = "u1",
        updatedAt = 1_754_000_000_000,
        permissions = DrivePermissions.Owner,
        hasExplicitPermissions = true,
        accessUserIds = listOf("u2", "u3"),
    )

    private val sharedDoc = DriveItem(
        id = "file-2",
        kind = DriveItemKind.File,
        name = "Budget.xlsx",
        extension = "xlsx",
        parentFolderId = "fold-parent",
        sizeBytes = 91_000,
        uploadedByName = "Grace Hopper",
        uploadedById = "u2",
        createdById = "u2",
        permissions = DrivePermissions.ViewOnly,
        hasExplicitPermissions = true,
    )

    private val crew = listOf(
        DrivePerson("u1", "Ada Lovelace", "Producer"),
        DrivePerson("u2", "Grace Hopper", "Editor"),
        DrivePerson("u3", "Alan Turing", "Runner"),
    )

    private val parent = DriveItem(id = "fold-parent", kind = DriveItemKind.Folder, name = "Production")

    private companion object {
        const val STAMP = 1_754_000_000_000L
    }

    private fun state(viewer: DriveViewer = admin, folderId: String? = "fold-parent") = DriveUiState(
        viewer = viewer,
        section = DriveSection.MyDrive,
        listing = DriveListing(folders = listOf(parent, folder), files = listOf(pdf, sharedDoc)),
        folderId = folderId,
        breadcrumb = if (folderId == null) emptyList() else listOf(DriveCrumb("fold-parent", "Production")),
        tags = listOf(DriveTag("t1", "Approved", "#16a34a")),
        favouriteIds = setOf("file-1"),
        crew = crew,
    )

    @Test
    fun `the browser composes in a folder, at the root and under both sections`() = runComposeUiTest {
        val current = mutableStateOf(state())
        setContent { ZillitTheme { DriveScreen(state = current.value, onEvent = {}) } }
        onNodeWithText("Camera Report Day 12.pdf").assertExists()
        // The folder is mine and shared with nobody; the PDF shows its sharees instead.
        onNodeWithText("Only you").assertExists()

        current.value = state(folderId = null)
        waitForIdle()
        onNodeWithText("Folders").assertExists()
        onNodeWithText("Files").assertExists()

        current.value = state(folderId = null).copy(innerTab = DriveInnerTab.Files, section = DriveSection.SharedWithMe)
        waitForIdle()
        // The section tab and the root crumb both read "Shared with me".
        assertEquals(2, onAllNodesWithText("Shared with me").fetchSemanticsNodes().size)
    }

    @Test
    fun `a deep trail collapses its middle behind a menu that still opens those folders`() = runComposeUiTest {
        val events = mutableListOf<DriveEvent>()
        val deep = state().copy(
            breadcrumb = listOf(
                DriveCrumb("a", "Season 1"),
                DriveCrumb("b", "Block 2"),
                DriveCrumb("c", "Unit A"),
                DriveCrumb("d", "Day 12"),
            ),
        )
        setContent { ZillitTheme { DriveScreen(state = deep, onEvent = { events += it }) } }
        onNodeWithText("Day 12").assertExists()
        onNodeWithText("Unit A").assertExists()
        onNodeWithText("Season 1").assertDoesNotExist()
        onNodeWithText("…").performClick()
        waitForIdle()
        onNodeWithText("Block 2").performClick()
        assertEquals(listOf<DriveEvent>(DriveEvent.OpenFolder("b")), events)
    }

    @Test
    fun `the grid, the trash, a search and dark mode all compose`() = runComposeUiTest {
        val current = mutableStateOf(state().copy(viewMode = DriveViewMode.Grid))
        val dark = mutableStateOf(false)
        setContent { ZillitTheme(darkTheme = dark.value) { DriveScreen(state = current.value, onEvent = {}) } }
        onNodeWithText("Camera Reports").assertExists()

        current.value = state().copy(
            showTrash = true,
            trash = TrashState(items = listOf(pdf.copy(deletedAt = 1_754_000_000_000))),
        )
        waitForIdle()
        onNodeWithText("Trash (1)").assertExists()
        onNodeWithText("Empty trash").assertExists()

        current.value = state().copy(search = "camera", searchInput = "camera")
        waitForIdle()
        onNodeWithText("Search results for").assertExists()

        dark.value = true
        current.value = state()
        waitForIdle()
        onNodeWithText("Camera Reports").assertExists()
    }

    @Test
    fun `every empty state composes`() = runComposeUiTest {
        val current = mutableStateOf(state(folderId = null).copy(listing = DriveListing()))
        setContent { ZillitTheme { DriveScreen(state = current.value, onEvent = {}) } }
        onNodeWithText("No files or folders yet").assertExists()

        current.value = current.value.copy(section = DriveSection.SharedWithMe)
        waitForIdle()
        onNodeWithText("No folders or files have been shared with you yet").assertExists()

        current.value = state(folderId = null).copy(listing = DriveListing(), search = "zzz", searchInput = "zzz")
        waitForIdle()
        onNodeWithText("No results found").assertExists()
    }

    @Test
    fun `a read-only viewer gets the banner and no upload affordance`() = runComposeUiTest {
        setContent { ZillitTheme { DriveScreen(state = state(viewer = readOnly), onEvent = {}) } }
        onNodeWithText("Upload").assertDoesNotExist()
        onNodeWithText("Create folder").assertDoesNotExist()
        onNodeWithText(
            "You can browse this drive but cannot upload to it or create folders. " +
                "Ask an administrator for posting rights on the Drive.",
        ).assertExists()
    }

    @Test
    fun `a blocked viewer sees one explanation rather than an empty tool`() = runComposeUiTest {
        val blocked = readOnly.copy(canView = false)
        setContent { ZillitTheme { DriveScreen(state = state(viewer = blocked), onEvent = {}) } }
        onNodeWithText("No access to Drive").assertIsDisplayed()
        onNodeWithText("Camera Reports").assertDoesNotExist()
    }

    @Test
    fun `the bulk bar names what will actually happen`() = runComposeUiTest {
        val events = mutableListOf<DriveEvent>()
        setContent {
            ZillitTheme {
                DriveScreen(
                    state = state(viewer = admin.copy(isAdmin = false)).copy(selected = setOf("file-1", "file-2")),
                    onEvent = { events += it },
                )
            }
        }
        onNodeWithText("2 items selected").assertExists()
        // The shared spreadsheet is view-only: one of two can be deleted.
        onNodeWithText("Delete (1 of 2)").assertExists().performClick()
        assertTrue(events.any { it is DriveEvent.RequestDelete })
    }

    @Test
    fun `clicking a section tab asks to open that section`() = runComposeUiTest {
        val events = mutableListOf<DriveEvent>()
        setContent { ZillitTheme { DriveScreen(state = state(), onEvent = { events += it }) } }
        onNodeWithText("Shared with me").performClick()
        assertEquals(listOf<DriveEvent>(DriveEvent.OpenSection(DriveSection.SharedWithMe)), events)
    }

    @Test
    fun `the details panel docks beside the listing with every section filled`() = runComposeUiTest {
        val details = DetailsState(
            item = pdf,
            comments = listOf(DriveComment("c1", "file-1", "Grace Hopper", "u2", "Looks good", 1_754_000_000_000)),
            activity = listOf(
                DriveActivity("a1", "file_created", "Camera Report Day 12.pdf", "file", "Ada Lovelace", "u1", STAMP),
            ),
            versions = listOf(DriveVersion("v1", "file-1", 1, "Camera Report Day 12.pdf", 400_000, "Ada", STAMP)),
            access = listOf(
                DriveAccessEntry("u2", "Grace Hopper", "Editor", DriveRole.Editor, DriveRole.Editor.permissions),
            ),
            tags = listOf(DriveTag("t1", "Approved", "#16a34a")),
        )
        setContent { ZillitTheme { DriveScreen(state = state().copy(details = details), onEvent = {}) } }
        onNodeWithText("Looks good").assertExists()
        onNodeWithText("Access (1)").assertExists()
        onNodeWithText("Tags (1)").assertExists()
        onNodeWithText("Comments (1)").assertExists()
        onNodeWithText("Camera Reports").assertExists()
    }

    @Test
    fun `an item with no explicit access says who can still reach it`() = runComposeUiTest {
        setContent {
            ZillitTheme { DriveScreen(state = state().copy(details = DetailsState(item = folder)), onEvent = {}) }
        }
        onNodeWithText(
            "No access records. Administrators, and anyone with access to a parent folder, can still reach it.",
        ).assertExists()
    }

    @Test
    fun `every drawer composes`() = runComposeUiTest {
        val current = mutableStateOf(state().copy(
            upload = UploadDraft(
                files = listOf(PickedFile("/tmp/a.pdf", "a.pdf", 10, "application/pdf", "photos/a.pdf")),
                unsupported = listOf(PickedFile("/tmp/b.exe", "b.exe", 10, "x/y")),
                showDetails = true,
                accessExpanded = true,
            ),
        ))
        setContent { ZillitTheme { DriveScreen(state = current.value, onEvent = {}, now = { 1_754_000_000_000 }) } }
        onNodeWithText("Upload files").assertExists()
        onNodeWithText("1 unsupported file skipped").assertExists()

        current.value = state(folderId = null).copy(newFolder = NewFolderDraft(name = "Dailies", pickExisting = true))
        waitForIdle()
        assertTrue(onAllNodesWithText("Create folder").fetchSemanticsNodes().size >= 2, "header button and drawer")
        onNodeWithText("Pick a folder above.").assertExists()

        current.value = state().copy(edit = EditDraft(pdf, "Camera Report Day 12", ""))
        waitForIdle()
        onNodeWithText("Edit file").assertExists()

        current.value = state().copy(
            share = ShareState(
                item = pdf,
                access = AccessDraft(levels = mapOf("u2" to FileAccessLevel.Edit)),
            ),
        )
        waitForIdle()
        onNodeWithText("Share file").assertExists()
        onNodeWithText("Alan Turing").assertExists()

        current.value = state().copy(
            share = ShareState(
                item = pdf,
                tab = ShareTab.Link,
                link = ShareLinkForm(
                    links = listOf(
                        DriveShareLink("l1", "tok", "https://x/share/tok", maxViews = 3, viewCount = 1, expiresOn = 1),
                    ),
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("Active share links").assertExists()
        onNodeWithText("Expired").assertExists()

        current.value = state().copy(
            share = ShareState(item = folder, access = AccessDraft(roles = mapOf("u2" to DriveRole.Editor))),
        )
        waitForIdle()
        onNodeWithText("Manage access").assertExists()

        current.value = state().copy(activityLog = ActivityLogState(open = true, items = listOf(
            DriveActivity("a1", "folder_moved", "Camera Reports", "folder", "Ada Lovelace", "u1", 1_754_000_000_000),
        ), total = 1))
        waitForIdle()
        onNodeWithText("Activity log").assertExists()
        onNodeWithText("Folder moved").assertExists()
    }

    @Test
    fun `every dialog composes`() = runComposeUiTest {
        val current = mutableStateOf(state().copy(moveTo = MoveToState(items = listOf(folder))))
        setContent { ZillitTheme { DriveScreen(state = current.value, onEvent = {}) } }
        onNodeWithText("Move here").assertExists()
        onNodeWithText("Drive (root)").assertExists()

        current.value = state().copy(
            dropUpload = DropUploadState(listOf(PickedFile("/tmp/a.pdf", "a.pdf", 10, "application/pdf"))),
        )
        waitForIdle()
        onNodeWithText("Skip & upload").assertExists()

        current.value = state().copy(
            preview = PreviewState(item = pdf, kind = PreviewKind.Pdf, loading = false, url = "u"),
        )
        waitForIdle()
        onNodeWithText("Preview is not supported for this file type.").assertExists()

        current.value = state().copy(
            preview = PreviewState(
                item = pdf.copy(name = "notes.txt", extension = "txt"),
                kind = PreviewKind.Text,
                loading = false,
                text = "hello there",
            ),
        )
        waitForIdle()
        onNodeWithText("hello there").assertExists()

        current.value = state().copy(menu = ItemMenuState(pdf, 100f, 100f))
        waitForIdle()
        onNodeWithText("Edit info").assertExists()
        onNodeWithText("Move to…").assertExists()
    }

    @Test
    fun `an upload in flight shows in the operations panel`() = runComposeUiTest {
        val queued = QueuedUpload(
            id = "up-1",
            fileName = "Dailies Day 12.mov",
            sizeBytes = 4_000_000_000,
            destinationFolderId = "fold-parent",
            state = UploadState.InProgress(uploadedParts = 3, totalParts = 8),
        )
        setContent { ZillitTheme { DriveScreen(state = state().copy(uploads = listOf(queued)), onEvent = {}) } }
        onNodeWithText("Uploading 1 of 1").assertExists()
        onNodeWithText("Dailies Day 12.mov").assertExists()
        onNodeWithText("37%").assertExists()
    }

    @Test
    fun `the compact widget layout composes with a docked-over details panel`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                DriveScreen(state = state().copy(details = DetailsState(item = folder)), onEvent = {}, compact = true)
            }
        }
        // The row beneath and the details header over it both name the folder.
        assertEquals(2, onAllNodesWithText("Camera Reports").fetchSemanticsNodes().size)
    }
}
