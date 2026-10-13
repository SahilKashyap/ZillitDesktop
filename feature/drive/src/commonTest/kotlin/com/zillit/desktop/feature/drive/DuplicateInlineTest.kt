package com.zillit.desktop.feature.drive

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePermissions
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.NewFolder
import com.zillit.desktop.feature.drive.ui.DriveEffect
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A taken name is the name field's problem: said on the field, not in a toast, and gone when edited. */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateInlineTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val admin = DriveViewer(
        userId = "u1", canView = true, canPost = true, canDownload = true, isAdmin = true, ready = true,
    )
    private val file = DriveItem(
        id = "f1", kind = DriveItemKind.File, name = "plan.pdf", extension = "pdf",
        permissions = DrivePermissions.Owner, hasExplicitPermissions = true, createdById = "u1",
    )

    private var refusal: ZillitError = ZillitError.Http(status = 200, serverMessage = "duplicate_folder_name")

    private inner class Repo : FakeDriveRepository() {
        override suspend fun createFolder(folder: NewFolder): ZillitResult<DriveItem> = ZillitResult.Failure(refusal)
        override suspend fun rename(ref: DriveRef, name: String, description: String?): ZillitResult<Unit> =
            ZillitResult.Failure(refusal)
    }

    private fun model() = DriveViewModel(repository = Repo().also { it.rows = listOf(file) }, viewer = { admin })

    @Test
    fun `a taken folder name lands on the field and is cleared by typing`() = runTest(dispatcher) {
        val vm = model()
        val toasts = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { toasts += it } }
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.OpenNewFolder)
        vm.onEvent(DriveEvent.NewFolderName("Rushes"))

        vm.onEvent(DriveEvent.SubmitNewFolder)
        runCurrent()

        val draft = assertNotNull(vm.state.value.newFolder, "the drawer stays open")
        assertNotNull(draft.nameError)
        assertTrue(!draft.submitting)
        assertTrue(toasts.isEmpty(), "said once, on the field")

        vm.onEvent(DriveEvent.NewFolderName("Rushes 2"))
        assertNull(vm.state.value.newFolder?.nameError)
    }

    @Test
    fun `a taken name on a rename lands on the field too`() = runTest(dispatcher) {
        refusal = ZillitError.Http(status = 200, serverMessage = "duplicate_file_name")
        val vm = model()
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.OpenEdit(file))
        vm.onEvent(DriveEvent.EditName("plan v2"))

        vm.onEvent(DriveEvent.SubmitEdit)
        runCurrent()

        assertNotNull(vm.state.value.edit?.nameError)
        vm.onEvent(DriveEvent.EditName("plan v3"))
        assertNull(vm.state.value.edit?.nameError)
    }

    @Test
    fun `any other refusal is still a toast and leaves the field alone`() = runTest(dispatcher) {
        refusal = ZillitError.Http(status = 200, serverMessage = "route_not_found")
        val vm = model()
        val toasts = mutableListOf<DriveEffect>()
        backgroundScope.launch { vm.effects.collect { toasts += it } }
        vm.start()
        runCurrent()
        vm.onEvent(DriveEvent.OpenNewFolder)
        vm.onEvent(DriveEvent.NewFolderName("Rushes"))

        vm.onEvent(DriveEvent.SubmitNewFolder)
        runCurrent()

        assertNull(vm.state.value.newFolder?.nameError)
        assertEquals(1, toasts.size)
    }
}
