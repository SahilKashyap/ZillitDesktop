package com.zillit.desktop.feature.location

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.MovePlace
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.feature.location.ui.LocationEvent
import com.zillit.desktop.feature.location.ui.LocationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cabinet's view model reads with the flag on every call and refuses every write. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationCabinetViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `folders and galleries are read as the cabinet, on the default Selected tab`() = runTest(dispatcher) {
        val repository = Recording()
        val model = model(repository)

        model.start()
        runCurrent()
        model.onEvent(LocationEvent.OpenPick(LocationPick("Hall", "12")))
        runCurrent()
        model.onEvent(LocationEvent.SelectStatus(LocationStatus.Published))
        runCurrent()

        assertEquals(LocationStatus.Published, model.state.value.status)
        assertTrue(repository.infoFlags.all { it }, "every folder read is a cabinet read: ${repository.infoFlags}")
        assertEquals(listOf(true), repository.mediaFlags)
        assertEquals(
            listOf(LocationStatus.Selected, LocationStatus.Published), repository.infoStatuses,
            "the cabinet opens on the page's own default, Selected",
        )
    }

    @Test
    fun `every act that would write is ignored`() = runTest(dispatcher) {
        val repository = Recording()
        val model = model(repository)
        model.start()
        runCurrent()

        model.onEvent(LocationEvent.NewLink)
        model.onEvent(LocationEvent.PickFile)
        model.onEvent(LocationEvent.ToggleSelecting)
        model.onEvent(LocationEvent.DeleteSelected)
        model.onEvent(LocationEvent.ConfirmDelete)
        model.onEvent(LocationEvent.MoveSelected(LocationStatus.Published))
        model.onEvent(LocationEvent.SendDiscussion)
        model.onEvent(LocationEvent.Save)
        runCurrent()

        assertNull(model.state.value.editor)
        assertNull(model.state.value.confirmDelete)
        assertEquals(emptyList(), repository.writes)
    }

    private fun model(repository: LocationRepository) = LocationViewModel(
        repository = repository,
        transfer = NoTransfer,
        resolveViewer = { LocationViewer(isAdmin = true, ready = true) },
        nowMillis = { 1L },
        fileCabinet = true,
    )

    private object NoTransfer : LocationTransfer {
        override suspend fun upload(file: PickedLocationFile) = ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun fetch(attachment: MediaAttachment) = ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) =
            ZillitResult.Failure(ZillitError.Unknown("no"))
    }

    private class Recording : LocationRepository {
        val infoFlags = mutableListOf<Boolean>()
        val infoStatuses = mutableListOf<LocationStatus>()
        val mediaFlags = mutableListOf<Boolean>()
        val writes = mutableListOf<String>()

        override suspend fun info(status: LocationStatus, fileCabinet: Boolean): ZillitResult<List<LocationInfo>> {
            infoFlags += fileCabinet
            infoStatuses += status
            return ZillitResult.Success(emptyList())
        }

        override suspend fun media(
            status: LocationStatus, location: String?, sceneNumber: String?,
            episode: String?, beforeMs: Long, next: Boolean, fileCabinet: Boolean,
        ): ZillitResult<List<LocationMedia>> {
            mediaFlags += fileCabinet
            return ZillitResult.Success(emptyList())
        }

        override suspend fun create(draft: LocationDraft, attachment: MediaAttachment?,
            linkPreview: MediaAttachment?): ZillitResult<LocationMedia?> {
            writes += "create"
            return ZillitResult.Success(null)
        }

        override suspend fun update(id: String, draft: LocationDraft, linkPreview: MediaAttachment?):
            ZillitResult<Unit> {
            writes += "update"
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(ids: List<String>, status: LocationStatus): ZillitResult<Unit> {
            writes += "delete"
            return ZillitResult.Success(Unit)
        }

        override suspend fun move(records: List<LocationMedia>, from: LocationStatus,
            to: LocationStatus, place: MovePlace?): ZillitResult<Unit> {
            writes += "move"
            return ZillitResult.Success(Unit)
        }

        override suspend fun pdf(ids: List<String>, includeDetails: Boolean) =
            ZillitResult.Failure(ZillitError.Unknown("no pdf"))

        override suspend fun sendMessage(recordId: String, body: String): ZillitResult<Unit> {
            writes += "send"
            return ZillitResult.Success(Unit)
        }
    }
}
