package com.zillit.desktop.feature.location

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.location.domain.LatLng
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationPin
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.MovePlace
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.feature.location.ui.LocationEditor
import com.zillit.desktop.feature.location.ui.LocationEvent
import com.zillit.desktop.feature.location.ui.LocationViewModel
import com.zillit.desktop.feature.location.ui.PlaceEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * Picking where a location is, and what that sends.
 *
 * The pin is the whole answer: the link saved is always its Google Maps
 * link, the picture of the map around it is the record's `link_attachment`,
 * and a form opened and saved without touching the map must leave all three
 * alone (web PRs #6517, #6521).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPlaceFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val pin = LatLng(51.5079, -0.0877)

    private class Recording(
        val pins: List<LocationPin> = emptyList(),
        val records: List<LocationMedia> = emptyList(),
    ) : LocationRepository {
        val created = mutableListOf<Triple<LocationDraft, MediaAttachment?, MediaAttachment?>>()
        val updated = mutableListOf<Pair<LocationDraft, MediaAttachment?>>()
        val moves = mutableListOf<MovePlace?>()
        var pinsAskedFor: LocationStatus? = null

        override suspend fun info(status: LocationStatus, fileCabinet: Boolean) =
            ZillitResult.Success(emptyList<LocationInfo>())

        override suspend fun media(
            status: LocationStatus, location: String?, sceneNumber: String?,
            episode: String?, beforeMs: Long, next: Boolean, fileCabinet: Boolean,
        ) = ZillitResult.Success(records)

        override suspend fun create(
            draft: LocationDraft, attachment: MediaAttachment?, linkPreview: MediaAttachment?,
        ): ZillitResult<LocationMedia?> {
            created += Triple(draft, attachment, linkPreview)
            return ZillitResult.Success(null)
        }

        override suspend fun update(id: String, draft: LocationDraft, linkPreview: MediaAttachment?):
            ZillitResult<Unit> {
            updated += draft to linkPreview
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(ids: List<String>, status: LocationStatus) = ZillitResult.Success(Unit)

        override suspend fun move(
            records: List<LocationMedia>, from: LocationStatus, to: LocationStatus, place: MovePlace?,
        ): ZillitResult<Unit> {
            moves += place
            return ZillitResult.Success(Unit)
        }

        override suspend fun mapPins(status: LocationStatus?): ZillitResult<List<LocationPin>> {
            pinsAskedFor = status
            return ZillitResult.Success(pins)
        }

        override suspend fun pdf(ids: List<String>, includeDetails: Boolean) = ZillitResult.Success(stored)
    }

    private object Transfer : LocationTransfer {
        val uploaded = mutableListOf<PickedLocationFile>()
        override suspend fun upload(file: PickedLocationFile): ZillitResult<MediaAttachment> {
            uploaded += file
            return ZillitResult.Success(stored)
        }
        override suspend fun fetch(attachment: MediaAttachment) = ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private fun model(repository: Recording) = LocationViewModel(
        repository = repository,
        transfer = Transfer,
        resolveViewer = { LocationViewer(ready = true, canPost = true) },
        nowMillis = { 0L },
        mapSnapshot = { PickedLocationFile("location-map.png", "image/png", MAP_BYTES) },
        pinsMap = { PICTURE },
    )

    @Test
    fun `a picked place saves its link, its pin and a picture of the map`() = runTest(dispatcher) {
        Transfer.uploaded.clear()
        val repository = Recording()
        val model = model(repository)
        model.start()
        advanceUntilIdle()

        model.onEvent(LocationEvent.NewLink)
        model.onEvent(
            LocationEvent.EditorChanged(
                LocationEditor(location = "Millennium Bridge", place = PlaceEditor(pin = pin, address = "Bankside")),
            ),
        )
        advanceUntilIdle()
        model.onEvent(LocationEvent.Save)
        advanceUntilIdle()

        val (draft, attachment, mapPicture) = repository.created.single()
        assertEquals("https://www.google.com/maps?q=51.5079,-0.0877", draft.link)
        assertEquals(pin, draft.pin)
        assertNull(attachment, "no file was picked")
        assertEquals(stored, mapPicture, "the map picture is the record's link_attachment")
        assertEquals(listOf("location-map.png"), Transfer.uploaded.map { it.name })
    }

    @Test
    fun `the pin's address fills an empty Address box`() = runTest(dispatcher) {
        val model = model(Recording())
        model.start()
        advanceUntilIdle()

        model.onEvent(LocationEvent.NewLink)
        model.onEvent(
            LocationEvent.EditorChanged(
                LocationEditor(location = "Bridge", place = PlaceEditor(pin = pin, address = "Bankside")),
            ),
        )
        advanceUntilIdle()

        assertEquals("Bankside", model.state.value.editor?.address)
    }

    @Test
    fun `an untouched place sends nothing place-shaped`() = runTest(dispatcher) {
        Transfer.uploaded.clear()
        val repository = Recording()
        val model = model(repository)
        model.start()
        advanceUntilIdle()

        // A record edited for its description alone must keep its link, its
        // pin and the picture the server already holds.
        model.onEvent(LocationEvent.Edit(record()))
        model.onEvent(LocationEvent.EditorChanged(model.state.value.editor!!.copy(description = "Night shoot")))
        advanceUntilIdle()
        model.onEvent(LocationEvent.Save)
        advanceUntilIdle()

        val (draft, mapPicture) = repository.updated.single()
        assertEquals("", draft.link, "an empty link leaves the stored one alone")
        assertNull(draft.pin)
        assertNull(mapPicture)
        assertTrue(Transfer.uploaded.isEmpty(), "nothing was uploaded for an untouched place")
    }

    @Test
    fun `clearing the pin of a record that has media asks for the link to go`() = runTest(dispatcher) {
        val repository = Recording()
        val model = model(repository)
        model.start()
        advanceUntilIdle()

        model.onEvent(LocationEvent.Edit(record()))
        val open = model.state.value.editor!!
        model.onEvent(LocationEvent.EditorChanged(open.copy(place = open.place.copy(pin = null))))
        advanceUntilIdle()
        model.onEvent(LocationEvent.Save)
        advanceUntilIdle()

        assertTrue(repository.updated.single().first.removeLink)
    }

    @Test
    fun `moving to Final asks where the location is first`() = runTest(dispatcher) {
        val repository = Recording(records = listOf(record()))
        val model = model(repository)
        model.start()
        advanceUntilIdle()
        tickOne(model)
        advanceUntilIdle()

        model.onEvent(LocationEvent.MoveSelected(LocationStatus.Published))
        advanceUntilIdle()

        assertNotNull(model.state.value.moving, "the Move form should be up")
        assertTrue(repository.moves.isEmpty(), "nothing moved before the form was answered")
    }

    @Test
    fun `a place picked in the Move form rides with the move`() = runTest(dispatcher) {
        val repository = Recording(records = listOf(record(pin = null)))
        val model = model(repository)
        model.start()
        advanceUntilIdle()
        tickOne(model)
        advanceUntilIdle()

        model.onEvent(LocationEvent.MoveSelected(LocationStatus.Published))
        advanceUntilIdle()
        model.onEvent(
            LocationEvent.MovePlaceChanged(model.state.value.moving!!.place.copy(pin = pin, address = "Bankside")),
        )
        advanceUntilIdle()
        model.onEvent(LocationEvent.ConfirmMove)
        advanceUntilIdle()

        val place = assertNotNull(repository.moves.single())
        assertEquals(pin, place.pin)
        assertEquals("https://www.google.com/maps?q=51.5079,-0.0877", place.link)
        assertEquals(stored, place.mapImage)
    }

    @Test
    fun `moving to a shortlist the map does not draw goes straight through`() = runTest(dispatcher) {
        val repository = Recording(records = listOf(record()))
        val model = model(repository)
        model.start()
        advanceUntilIdle()
        tickOne(model)
        advanceUntilIdle()

        // Selected is not on the Map tool, so there is nothing to ask for.
        model.onEvent(LocationEvent.MoveSelected(LocationStatus.Selected))
        advanceUntilIdle()

        assertNull(model.state.value.moving)
        assertEquals(1, repository.moves.size)
        assertNull(repository.moves.single(), "nothing was asked for, so no place was sent")
    }

    @Test
    fun `the map view reads this tab's pins and draws them`() = runTest(dispatcher) {
        val repository = Recording(pins = listOf(pinRow()))
        val model = model(repository)
        model.start()
        advanceUntilIdle()

        model.onEvent(LocationEvent.ToggleMapView)
        advanceUntilIdle()

        assertEquals(LocationStatus.Selected, repository.pinsAskedFor, "the open tab's pins, not every pin")
        assertEquals(listOf("p1"), model.state.value.map.pins.map { it.id })
        assertEquals(PICTURE, model.state.value.map.picture)
    }

    @Test
    fun `opening a pin moves to its shortlist and opens its folder`() = runTest(dispatcher) {
        val repository = Recording(pins = listOf(pinRow()))
        val model = model(repository)
        model.start()
        advanceUntilIdle()
        model.onEvent(LocationEvent.ToggleMapView)
        advanceUntilIdle()

        model.onEvent(LocationEvent.OpenPin(pinRow()))
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(LocationStatus.Published, state.status)
        assertEquals("Millennium Bridge", state.gallery?.pick?.location)
        assertEquals("14", state.gallery?.pick?.scene)
        assertTrue(!state.map.open, "the map steps aside for the folder it opened")
    }

    /** Opens the gallery the fake repository answers, and ticks its one record. */
    private fun tickOne(model: LocationViewModel) {
        model.onEvent(LocationEvent.OpenPick(LocationPick("Bridge", "14")))
        model.onEvent(LocationEvent.ToggleSelecting)
        model.onEvent(LocationEvent.ToggleSelect("m1"))
    }

    private fun record(pin: LatLng? = this.pin) = LocationMedia(
        id = "m1", location = "Bridge", sceneNumber = "14", episodes = emptyList(), city = "",
        address = "Bankside", description = "", contactName = "", email = "", phone = "", countryCode = "",
        link = "https://www.google.com/maps?q=51.5079,-0.0877", attachment = stored, linkAttachment = null,
        pin = pin, status = LocationStatus.Selected, uploadedBy = "u", createdMs = 0, updatedMs = 0,
        deleted = false, discussion = false,
    )

    private fun pinRow() = LocationPin(
        id = "p1", location = "Millennium Bridge", sceneNumber = "14", address = "Bankside", city = "",
        description = "", link = "", pin = pin, status = LocationStatus.Published,
    )

    private companion object {
        val stored = MediaAttachment("k/map.png", "", "image", "png", "map.png", "b", "r", "0")
        val MAP_BYTES = ByteArray(4) { 7 }
        val PICTURE = ByteArray(3) { 9 }
    }
}
