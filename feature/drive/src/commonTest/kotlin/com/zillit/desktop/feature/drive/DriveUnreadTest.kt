package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DriveBadgeWire
import com.zillit.desktop.feature.drive.domain.DriveBadges
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveUnreadRow
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.driveUnread
import com.zillit.desktop.feature.drive.domain.driveUnreadFrom
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SHARED = DriveBadgeWire.SHARED_VIEW
private const val MINE = DriveBadgeWire.MY_DRIVE_VIEW

private fun fileRow(id: String, vararg levels: String, view: String = SHARED) = DriveUnreadRow(
    unit = DriveBadgeWire.FILE_UNIT,
    referenceId = id,
    level1 = levels.getOrNull(0).orEmpty(),
    level2 = levels.getOrNull(1).orEmpty(),
    level3 = levels.getOrNull(2).orEmpty(),
    deeper = levels.drop(3),
    view = view,
)

private fun folderRow(id: String, vararg levels: String, view: String = SHARED) =
    fileRow(id, *levels, view = view).copy(unit = DriveBadgeWire.FOLDER_UNIT)

/**
 * The dots' roll-up is the web's `getDriveBadgeCounts`; these pin the cases
 * its comments argue about (ZL-18873, ZL-20181).
 */
class DriveUnreadRollupTest {

    private fun unread(
        vararg rows: DriveUnreadRow,
        parents: Map<String, String> = emptyMap(),
        files: Map<String, String> = emptyMap(),
    ) = driveUnreadFrom(rows.toList(), parents, files)

    @Test
    fun `an unread file counts for itself and every folder above it`() {
        val u = unread(fileRow("f1", "A", "B", "C", "D"))

        assertEquals(1, u.files["f1"])
        assertEquals(mapOf("A" to 1, "B" to 1, "C" to 1, "D" to 1), u.folders, "levels[] reaches depth four")
    }

    @Test
    fun `a folder row counts for the folder itself as well as its ancestors`() {
        val u = unread(folderRow("F", "P"))

        assertEquals(mapOf("F" to 1, "P" to 1), u.folders)
        assertEquals(emptyMap(), u.files)
    }

    @Test
    fun `each row is one unread, so two events on one file read two`() {
        val u = unread(fileRow("f1", "A"), fileRow("f1", "A"))

        assertEquals(2, u.files["f1"])
        assertEquals(2, u.folders["A"])
    }

    @Test
    fun `a folder moved since the notification re-homes onto its new parent`() {
        // Stored ancestry says OLD; the live tree has F inside NEW inside TOP.
        val u = unread(folderRow("F", "OLD"), parents = mapOf("F" to "NEW", "NEW" to "TOP"))

        assertEquals(mapOf("F" to 1, "OLD" to 1, "NEW" to 1, "TOP" to 1), u.folders)
    }

    @Test
    fun `a file's current folder and that folder's parents count too`() {
        val u = unread(fileRow("f1", "OLD"), parents = mapOf("C" to "D"), files = mapOf("f1" to "C"))

        assertEquals(mapOf("OLD" to 1, "C" to 1, "D" to 1), u.folders)
    }

    @Test
    fun `a loop in the tree cannot spin the walk`() {
        val u = unread(folderRow("F"), parents = mapOf("F" to "G", "G" to "F"))

        assertEquals(mapOf("F" to 1, "G" to 1), u.folders)
    }

    @Test
    fun `the root marker and the section markers are not folders`() {
        val u = unread(fileRow("f1", DriveBadgeWire.ROOT, MINE, SHARED))

        assertEquals(emptyMap(), u.folders)
        assertEquals(1, u.sharedRootFileRows, "no folder above it makes it a loose root file")
    }

    @Test
    fun `the section tab follows reference_data view, then a virtual level_1`() {
        val u = unread(
            fileRow("a", view = MINE),
            fileRow("b", view = SHARED),
            fileRow("c", MINE, view = ""),
            fileRow("d", SHARED, view = ""),
        )

        assertEquals(2, u.myDrive)
        assertEquals(2, u.shared)
    }

    @Test
    fun `a row with no section falls back to the action, as Android does`() {
        val u = unread(
            fileRow("a", view = "").copy(action = "drive_file_shared"),
            fileRow("b", view = "").copy(action = "drive_file_uploaded"),
        )

        assertEquals(1, u.shared)
        assertEquals(1, u.myDrive)
    }

    @Test
    fun `the root tabs split shared rows into folders and loose files`() {
        val u = unread(
            folderRow("F1"),
            fileRow("loose"),
            fileRow("nested", "A"),
            fileRow("nested2", "A"),
        )

        assertEquals(3, u.foldersTab(DriveSection.SharedWithMe), "one folder row plus two files inside folders")
        assertEquals(1, u.filesTab(DriveSection.SharedWithMe))
        assertEquals(1, u.tab(DriveSection.SharedWithMe, DriveInnerTab.Files))
    }

    @Test
    fun `the root tabs carry nothing in My Drive`() {
        val u = unread(folderRow("F1", view = MINE), fileRow("f", view = MINE))

        assertEquals(2, u.myDrive)
        assertEquals(0, u.foldersTab(DriveSection.MyDrive))
        assertEquals(0, u.filesTab(DriveSection.MyDrive))
    }

    @Test
    fun `the listing supplies the current tree`() {
        val folder = DriveItem(id = "C", kind = DriveItemKind.Folder, name = "C", parentFolderId = "D")
        val file = DriveItem(id = "f1", kind = DriveItemKind.File, name = "f1", parentFolderId = "C")

        val u = DriveListing(files = listOf(file), folders = listOf(folder)).driveUnread(listOf(fileRow("f1")))

        assertEquals(mapOf("C" to 1, "D" to 1), u.folders)
        assertEquals(1, u.of(file))
        assertEquals(1, u.of(folder))
    }
}

/** The view model hands the ledger's rows to the screen and sends the reads back. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveUnreadViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeBadges : DriveBadges {
        val ledger = MutableStateFlow<List<DriveUnreadRow>>(emptyList())
        val read = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        override val rows: Flow<List<DriveUnreadRow>> = ledger
        override fun readItem(id: String) { read += id }
        override fun forget(ids: Collection<String>) { forgotten += ids }
    }

    private val folder = DriveItem(id = "A", kind = DriveItemKind.Folder, name = "A")
    private val file = DriveItem(id = "f1", kind = DriveItemKind.File, name = "f1.txt", parentFolderId = "A")

    private fun model(badges: FakeBadges): DriveViewModel {
        val repo = FakeDriveRepository().also { it.rows = listOf(folder, file) }
        return DriveViewModel(
            repository = repo,
            viewer = { DriveViewer(userId = "u1", ready = true) },
            badges = badges,
        )
    }

    @Test
    fun `the ledger's rows become the screen's dots over the loaded listing`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        vm.start()
        runCurrent()

        badges.ledger.value = listOf(fileRow("f1", "A"))
        runCurrent()

        val unread = vm.state.value.unread
        assertEquals(1, unread.of(folder), "the folder shows what is unread inside it")
        assertEquals(1, unread.of(file))
        assertEquals(1, unread.of(DriveSection.SharedWithMe))
    }

    @Test
    fun `rows that arrive before the listing are applied when it lands`() = runTest(dispatcher) {
        val badges = FakeBadges().also { it.ledger.value = listOf(fileRow("f1", "A")) }
        val vm = model(badges)
        vm.start()
        runCurrent()

        assertEquals(1, vm.state.value.unread.of(folder))
    }

    @Test
    fun `opening a folder reads that folder and nothing inside it`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenFolder("A"))
        runCurrent()

        assertEquals(listOf("A"), badges.read)
    }

    @Test
    fun `going back to the root reads nothing`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenFolder(null))
        runCurrent()

        assertEquals(emptyList(), badges.read)
    }

    @Test
    fun `opening a file reads it`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.OpenItem(file))
        runCurrent()

        assertEquals(listOf("f1"), badges.read)
    }

    @Test
    fun `deleting items forgets their rows`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        vm.start()
        runCurrent()

        vm.onEvent(DriveEvent.Delete(listOf(file.ref)))
        runCurrent()

        assertEquals(listOf("f1"), badges.forgotten)
    }
}
