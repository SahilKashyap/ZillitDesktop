package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.settings.admin.domain.DownloadRequest
import com.zillit.desktop.feature.settings.admin.domain.DownloadStatus
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The File Cabinet page's behaviour, through the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileCabinetViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: Recorder) =
        AdminViewModel(repository, productionName = { "Feature One" }, selfUserId = { "me" })

    private fun cabinetTools() = listOf(
        ProductionTool("accounting_tool", "Accounts", enabled = true),
        ProductionTool("casting_main_tool", "Casting (Main)", enabled = true),
        ProductionTool("recce_tool", "Recce", enabled = false),
    )

    private fun TestScope.openCabinet(repository: Recorder): AdminViewModel {
        repository.toolsAnswer = cabinetTools()
        val model = viewModel(repository)
        model.onEvent(AdminEvent.Opened(AdminDestination.FileCabinet))
        advanceUntilIdle()
        return model
    }

    @Test
    fun `the cabinet lists the production's enabled tools plus home and group chat`() = runTest {
        val model = openCabinet(Recorder())

        assertEquals(
            listOf("accounting", "casting_main", "group_chat", "home"),
            model.state.value.fileCabinet.modules.map { it.identifier },
        )
        assertTrue(model.state.value.hasLoaded)
    }

    @Test
    fun `ticking modules and asking sends their identifiers in the web's order`() = runTest {
        val repository = Recorder()
        val model = openCabinet(repository)

        // Ticked out of order on purpose.
        model.onEvent(AdminEvent.CabinetToggled("home"))
        model.onEvent(AdminEvent.CabinetToggled("accounting"))
        model.onEvent(AdminEvent.CabinetRequestDownload)
        advanceUntilIdle()

        assertTrue("requestDownload:accounting,home" in repository.calls)
        val cabinet = model.state.value.fileCabinet
        assertEquals(DownloadStatus.Pending, cabinet.request?.status)
        assertTrue(cabinet.selected.isEmpty())
        assertTrue(cabinet.showNotice)
    }

    @Test
    fun `asking with nothing ticked does nothing`() = runTest {
        val repository = Recorder()
        val model = openCabinet(repository)

        model.onEvent(AdminEvent.CabinetRequestDownload)
        advanceUntilIdle()

        assertTrue(repository.calls.none { it.startsWith("requestDownload") })
    }

    @Test
    fun `select all ticks every module, and again clears them`() = runTest {
        val model = openCabinet(Recorder())

        model.onEvent(AdminEvent.CabinetSelectAllToggled)
        assertEquals(4, model.state.value.fileCabinet.selected.size)
        assertTrue(model.state.value.fileCabinet.allSelected)

        model.onEvent(AdminEvent.CabinetSelectAllToggled)
        assertTrue(model.state.value.fileCabinet.selected.isEmpty())
    }

    @Test
    fun `a request already preparing freezes the ticks and can be cancelled`() = runTest {
        val repository = Recorder()
        repository.cabinetRequest = DownloadRequest("req-9", DownloadStatus.InProgress)
        val model = openCabinet(repository)

        model.onEvent(AdminEvent.CabinetToggled("home"))
        assertTrue(model.state.value.fileCabinet.selected.isEmpty())
        assertTrue(model.state.value.fileCabinet.request!!.isPreparing)

        model.onEvent(AdminEvent.CabinetCancelRequest)
        advanceUntilIdle()

        assertTrue("cancelDownload:req-9" in repository.calls)
        assertNull(model.state.value.fileCabinet.request)
    }

    @Test
    fun `a completed request downloads, and the page is handed the address once`() = runTest {
        val repository = Recorder()
        repository.cabinetRequest = DownloadRequest("req-2", DownloadStatus.Completed)
        val model = openCabinet(repository)

        model.onEvent(AdminEvent.CabinetDownload)
        advanceUntilIdle()

        assertEquals("https://files.example/zip", model.state.value.fileCabinet.downloadUrl)
        assertNull(model.state.value.fileCabinet.request)

        model.onEvent(AdminEvent.CabinetUrlOpened)
        assertNull(model.state.value.fileCabinet.downloadUrl)
    }

    @Test
    fun `a request that is still preparing cannot be downloaded`() = runTest {
        val repository = Recorder()
        repository.cabinetRequest = DownloadRequest("req-3", DownloadStatus.Pending)
        val model = openCabinet(repository)

        model.onEvent(AdminEvent.CabinetDownload)
        advanceUntilIdle()

        assertTrue(repository.calls.none { it.startsWith("downloadUrl") })
    }

    @Test
    fun `a refused request leaves the page as it was and says why`() = runTest {
        val repository = Recorder()
        repository.requestAnswer = ZillitResult.Failure(ZillitError.Validation("file_cabinet_busy"))
        val model = openCabinet(repository)

        model.onEvent(AdminEvent.CabinetToggled("home"))
        model.onEvent(AdminEvent.CabinetRequestDownload)
        advanceUntilIdle()

        val cabinet = model.state.value.fileCabinet
        assertNull(cabinet.request)
        assertTrue(!cabinet.isWorking)
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `only an admin may ask for a download`() = runTest {
        val repository = Recorder()
        repository.toolsAnswer = cabinetTools()
        val model = AdminViewModel(repository, isAdmin = { false })
        model.onEvent(AdminEvent.Opened(AdminDestination.FileCabinet))
        advanceUntilIdle()

        model.onEvent(AdminEvent.CabinetToggled("home"))
        model.onEvent(AdminEvent.CabinetRequestDownload)
        advanceUntilIdle()

        assertTrue(repository.calls.none { it.startsWith("requestDownload") })
    }
}
