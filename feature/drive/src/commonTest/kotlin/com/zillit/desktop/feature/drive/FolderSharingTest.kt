package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.data.AccessDto
import com.zillit.desktop.feature.drive.data.ZipLinkDto
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DrivePerson
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.domain.nextAccessLevel
import com.zillit.desktop.feature.drive.ui.AccessDraft
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.drive.ui.floors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folder sharing as Google Drive does it: a folder's access reaches
 * everything inside it, and the **highest** access wins — so a subfolder or a
 * file can give someone more than the folder above, never less.
 *
 * The rules are the server's; what is tested here is that the client never
 * offers a grant the server would refuse to lower, and never sends one.
 */
class FolderSharingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun person(id: String) = DrivePerson(id = id, name = id.uppercase())

    private fun entry(
        userId: String,
        role: DriveRole = DriveRole.Viewer,
        inherited: Boolean = false,
        inheritedRole: DriveRole? = null,
        permissions: DrivePermissions = role.permissions,
    ) = DriveAccessEntry(
        userId = userId,
        role = role,
        permissions = permissions,
        inherited = inherited,
        inheritedRole = inheritedRole,
    )

    // -- the nested boxes ---------------------------------------------------

    @Test
    fun `a box takes the ones below it and unticking drops one level`() {
        assertEquals(3, nextAccessLevel(current = 0, clicked = 3), "ticking Owner grants Owner")
        assertEquals(2, nextAccessLevel(current = 3, clicked = 3), "unticking Owner drops to Editor")
        assertEquals(0, nextAccessLevel(current = 1, clicked = 1), "unticking the lowest stops sharing")
        assertEquals(2, nextAccessLevel(current = 1, clicked = 2), "ticking a higher box grants it")
    }

    @Test
    fun `a folder row walks owner to editor to not shared`() {
        var draft = AccessDraft()
        draft = draft.clicked("b", DriveRole.Owner.level, forFolder = true)
        assertEquals(DriveRole.Owner, draft.roles["b"])

        draft = draft.clicked("b", DriveRole.Owner.level, forFolder = true)
        assertEquals(DriveRole.Editor, draft.roles["b"])

        draft = draft.clicked("b", DriveRole.Viewer.level, forFolder = true)
        assertEquals(null, draft.roles["b"], "unticking Viewer stops sharing with that person")
    }

    @Test
    fun `a file row walks the same way over its levels`() {
        var draft = AccessDraft()
        draft = draft.clicked("b", FileAccessLevel.Edit.level, forFolder = false)
        assertEquals(FileAccessLevel.Edit, draft.levels["b"])

        draft = draft.clicked("b", FileAccessLevel.Edit.level, forFolder = false)
        assertEquals(FileAccessLevel.Download, draft.levels["b"])

        draft = draft.clicked("b", FileAccessLevel.View.level, forFolder = false)
        assertNull(draft.levels["b"])
    }

    // -- the floor a parent folder sets -------------------------------------

    @Test
    fun `an inherited role cannot be lowered or removed`() {
        val draft = AccessDraft(
            roles = mapOf("b" to DriveRole.Editor),
            inherited = mapOf("b" to DriveRole.Editor),
        )

        assertEquals(2, draft.floor("b", forFolder = true))
        // Clicking Editor would drop to Viewer; the floor holds it where it is.
        assertEquals(DriveRole.Editor, draft.clicked("b", DriveRole.Editor.level, forFolder = true).roles["b"])
        // Clicking Viewer would stop sharing; it cannot.
        assertEquals(DriveRole.Editor, draft.clicked("b", DriveRole.Viewer.level, forFolder = true).roles["b"])
        // More is still allowed.
        assertEquals(DriveRole.Owner, draft.clicked("b", DriveRole.Owner.level, forFolder = true).roles["b"])
    }

    @Test
    fun `a folder editor keeps Edit on a file inside it`() {
        val draft = AccessDraft(inherited = mapOf("b" to DriveRole.Editor))

        // Editor on the folder resolves to view + edit + download, which is
        // the file grid's top level — so nothing on the file can lower it.
        assertEquals(FileAccessLevel.Edit.level, draft.floor("b", forFolder = false))
        val viewerFloor = AccessDraft(inherited = mapOf("b" to DriveRole.Viewer))
        assertEquals(FileAccessLevel.View.level, viewerFloor.floor("b", forFolder = false))
    }

    @Test
    fun `the floors come from the rows the server marked inherited`() {
        val rows = listOf(
            entry("a", role = DriveRole.Editor),
            entry("b", inherited = true, inheritedRole = DriveRole.Owner),
            // A file's inherited row carries the folder's flags and no role.
            entry("c", inherited = true, permissions = DriveRole.Editor.permissions),
        )

        val floors = rows.floors()

        assertNull(floors["a"], "a row the item granted itself is not a floor")
        assertEquals(DriveRole.Owner, floors["b"])
        assertEquals(DriveRole.Editor, floors["c"], "flags are read back as the role they amount to")
    }

    @Test
    fun `an upload sends nothing for the people the folder already covers`() {
        val draft = AccessDraft(
            levels = mapOf(
                "covered" to FileAccessLevel.View,
                "raised" to FileAccessLevel.Edit,
                "new" to FileAccessLevel.Download,
            ),
            inherited = mapOf("covered" to DriveRole.Viewer, "raised" to DriveRole.Viewer),
        )

        val sent = draft.fileEntries(listOf(person("covered"), person("raised"), person("new")), skipCovered = true)

        assertEquals(setOf("raised", "new"), sent.map { it.userId }.toSet())
        assertTrue(
            draft.fileEntries(listOf(person("covered")), skipCovered = false).any { it.userId == "covered" },
            "the share screen itself still sends the full list",
        )
    }

    // -- the wire -----------------------------------------------------------

    @Test
    fun `the access rows carry inherited and inherited_role`() {
        val rows = json.decodeFromString(
            ListSerializer(AccessDto.serializer()),
            """
            [
              {"user_id":{"_id":"u1","full_name":"Ada"},"role":"editor"},
              {"user_id":"u2","role":"viewer","inherited":true,"inherited_role":"owner"},
              {"user_id":"u3","can_view":true,"can_edit":true,"can_download":true,
               "inherited":true,"inherited_from_folder_id":"fold1"}
            ]
            """.trimIndent(),
        ).mapNotNull { it.toDomain() }

        assertFalse(rows[0].inherited)
        assertNull(rows[0].inheritedRole)
        assertTrue(rows[1].inherited)
        assertEquals(DriveRole.Owner, rows[1].inheritedRole)
        assertTrue(rows[2].inherited, "a file's folder-granted row says so without a role")
        assertEquals("fold1", rows[2].inheritedFromFolderId)
    }

    @Test
    fun `a row without the fields is not treated as inherited`() {
        val row = json.decodeFromString(
            AccessDto.serializer(),
            """{"user_id":"u1","role":"viewer","inherited_role":null}""",
        ).toDomain()

        // `DriveRole.from` answers Viewer for anything it does not know, so an
        // absent field read as a role would put a floor under every row.
        assertNull(row?.inheritedRole)
        assertFalse(row?.inherited == true)
    }

    @Test
    fun `the zip link reply is read whole`() {
        val link = json.decodeFromString(
            ZipLinkDto.serializer(),
            """
            {"token":"abc","expires_in_seconds":120,"file_name":"Drive files (3).zip",
             "file_count":3,"skipped_count":1}
            """.trimIndent(),
        ).toDomain()

        assertEquals("abc", link?.token)
        assertEquals("Drive files (3).zip", link?.fileName)
        assertEquals(3, link?.fileCount)
        assertEquals(1, link?.skippedCount)
        assertEquals(120, link?.expiresInSeconds)
        assertNull(
            json.decodeFromString(ZipLinkDto.serializer(), """{"file_count":2}""").toDomain(),
            "no token is no link, however much else came back",
        )
    }

    // -- what may be done where --------------------------------------------

    private fun folder(id: String, permissions: DrivePermissions?) = DriveItem(
        id = id,
        kind = DriveItemKind.Folder,
        name = id,
        permissions = permissions ?: DrivePermissions.ViewOnly,
        hasExplicitPermissions = permissions != null,
        createdById = "someone-else",
    )

    private fun state(section: DriveSection, folderId: String?, folders: List<DriveItem>) = DriveUiState(
        viewer = DriveViewer(userId = "me", canView = true, canPost = true, canDownload = true, ready = true),
        section = section,
        folderId = folderId,
        listing = DriveListing(folders = folders),
    )

    @Test
    fun `an editor on a shared folder may add to it, a viewer may not`() {
        val editable = folder("f1", DrivePermissions(canView = true, canEdit = true, canDownload = true))
        val readable = folder("f2", DrivePermissions(canView = true))

        assertTrue(state(DriveSection.SharedWithMe, "f1", listOf(editable, readable)).canAddHere)
        assertFalse(state(DriveSection.SharedWithMe, "f2", listOf(editable, readable)).canAddHere)
        assertFalse(
            state(DriveSection.SharedWithMe, null, listOf(editable)).canAddHere,
            "the root of Shared with me is a list of other people's items, with no folder to add to",
        )
        assertTrue(state(DriveSection.MyDrive, null, emptyList()).canAddHere, "My Drive is unchanged")
    }

    @Test
    fun `nothing under Shared with me offers Move, at any depth`() {
        val editable = folder("f1", DrivePermissions.Owner)

        assertFalse(state(DriveSection.SharedWithMe, null, listOf(editable)).canMoveHere)
        assertFalse(
            state(DriveSection.SharedWithMe, "f1", listOf(editable)).canMoveHere,
            "an Editor of a shared folder does not get Move inside it either",
        )
        assertTrue(state(DriveSection.MyDrive, "f1", listOf(editable)).canMoveHere)
    }
}

/**
 * The two reads that went stale while the tool stayed open: the favourites
 * set, and who may be shared with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveStaleReadsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val viewer = DriveViewer(userId = "u1", canView = true, canPost = true, ready = true)

    private class Repo : FakeDriveRepository() {
        var favouriteCalls = 0
        var favouritesAnswer: ZillitResult<Set<String>> = ZillitResult.Failure(ZillitError.NoConnection())
        var shareableCalls = 0

        override suspend fun favouriteIds(): ZillitResult<Set<String>> {
            favouriteCalls++
            return favouritesAnswer
        }

        override suspend fun viewAccessUserIds(): ZillitResult<Set<String>?> {
            shareableCalls++
            return ZillitResult.Success(setOf("u2"))
        }
    }

    @Test
    fun `a favourites fetch that never answered is asked again when the filter goes on`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = DriveViewModel(repository = repo, viewer = { viewer })
        vm.start()
        runCurrent()
        assertEquals(1, repo.favouriteCalls)
        assertFalse(vm.state.value.favouritesLoaded, "a failure is not an answer")

        repo.favouritesAnswer = ZillitResult.Success(setOf("f1"))
        vm.onEvent(DriveEvent.ShowFavouritesOnly(true))
        runCurrent()

        assertEquals(2, repo.favouriteCalls, "the dropped request heals instead of showing an empty drive")
        assertTrue(vm.state.value.favouritesLoaded)
        assertEquals(setOf("f1"), vm.state.value.favouriteIds)

        vm.onEvent(DriveEvent.ShowFavouritesOnly(false))
        vm.onEvent(DriveEvent.ShowFavouritesOnly(true))
        runCurrent()
        assertEquals(2, repo.favouriteCalls, "once it has answered, the filter is free")
    }

    @Test
    fun `opening a picker re-reads who may be shared with`() = runTest(dispatcher) {
        val repo = Repo()
        val file = DriveItem(
            id = "f1",
            kind = DriveItemKind.File,
            name = "plan.pdf",
            permissions = DrivePermissions.Owner,
            hasExplicitPermissions = true,
            createdById = "u1",
        )
        repo.rows = listOf(file)
        val vm = DriveViewModel(repository = repo, viewer = { viewer })
        vm.start()
        runCurrent()
        assertEquals(1, repo.shareableCalls, "read once when the tool opened")

        vm.onEvent(DriveEvent.OpenShare(file))
        runCurrent()
        assertEquals(2, repo.shareableCalls, "someone who joined since must appear in the list")

        vm.onEvent(DriveEvent.OpenUpload)
        runCurrent()
        assertEquals(3, repo.shareableCalls)

        vm.onEvent(DriveEvent.OpenNewFolder)
        runCurrent()
        assertEquals(4, repo.shareableCalls)
    }
}
