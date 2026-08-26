package com.zillit.desktop.feature.location

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.location.data.matchesProject
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.feature.location.ui.LocationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `location:created/updated/deleted` frame reloads the open shortlist
 * exactly once — the web handlers re-run `getLocationList`
 * (`LocationPage.jsx:1044,1108,1126`) — and a frame naming another
 * production is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(override val refreshes: Flow<Unit>) : LocationRepository {
        var infoCalls = 0

        override suspend fun info(status: LocationStatus): ZillitResult<List<LocationInfo>> {
            infoCalls++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun media(
            status: LocationStatus, location: String?, sceneNumber: String?,
            episode: String?, beforeMs: Long, next: Boolean,
        ) = ZillitResult.Success(emptyList<LocationMedia>())
        override suspend fun create(
            draft: LocationDraft, attachment: MediaAttachment?, linkPreview: MediaAttachment?,
        ): ZillitResult<LocationMedia?> = ZillitResult.Success(null)
        override suspend fun update(id: String, draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun delete(ids: List<String>, status: LocationStatus) = ZillitResult.Success(Unit)
        override suspend fun move(records: List<LocationMedia>, from: LocationStatus, to: LocationStatus) =
            ZillitResult.Success(Unit)
        override suspend fun pdf(ids: List<String>, includeDetails: Boolean) =
            ZillitResult.Success(attachment)
    }

    private object NoTransfer : LocationTransfer {
        override suspend fun upload(file: PickedLocationFile) = ZillitResult.Success(attachment)
        override suspend fun fetch(attachment: MediaAttachment) = ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private companion object {
        val attachment = MediaAttachment(
            media = "k", thumbnail = "", contentType = "image", contentSubtype = "jpeg",
            name = "n", bucket = "b", region = "r", fileSize = "0",
        )
    }

    @Test
    fun `an emitted event reloads the shortlist once, and a second start does not stack`() =
        runTest(dispatcher) {
            val events = MutableSharedFlow<Unit>()
            val repository = FakeRepository(events)
            val model = LocationViewModel(
                repository = repository,
                transfer = NoTransfer,
                resolveViewer = { LocationViewer(ready = true) },
                nowMillis = { 0L },
            )

            model.start()
            runCurrent()
            assertEquals(1, repository.infoCalls, "start loads once")

            events.emit(Unit)
            runCurrent()
            assertEquals(2, repository.infoCalls, "the event reloads")

            model.start() // the window reopening must not add a second collector
            runCurrent()
            events.emit(Unit)
            runCurrent()
            assertEquals(4, repository.infoCalls, "start reloads, the event reloads ONCE")
        }

    @Test
    fun `only a frame naming another production is dropped`() {
        val mine = Json.parseToJsonElement("""{"project_id":"p1","status":"selected"}""")
        val other = Json.parseToJsonElement("""{"project_id":"p2"}""")

        assertTrue(mine.matchesProject("p1"))
        assertFalse(other.matchesProject("p1"))
        assertTrue(null.matchesProject("p1"), "a payload-less frame must pass")
        assertTrue(other.matchesProject(null), "no known project, nothing to compare")
    }
}
