package com.zillit.desktop.feature.location

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationMessage
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.feature.location.ui.LocationEvent
import com.zillit.desktop.feature.location.ui.LocationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A location record's discussion.
 *
 * The record model has carried a `discussion` flag since the port began — the
 * wire always said a thread existed, and this client had nowhere to show it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationDiscussionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Opening a record reads its thread, oldest first. */
    @Test
    fun `opening a record loads its discussion oldest first`() = runTest(dispatcher) {
        val repository = FakeRepository(
            // The wire answers newest-first; a discussion reads downwards.
            messages = listOf(line("m2", "second"), line("m1", "first")),
        )
        val model = viewModel(repository)

        model.onEvent(LocationEvent.View(record()))
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), model.state.value.discussion.map { it.body })
        assertEquals(listOf("rec-1"), repository.asked)
    }

    /** Sending posts the line and re-reads, so what shows is what saved. */
    @Test
    fun `sending posts and refreshes`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = viewModel(repository)
        model.onEvent(LocationEvent.View(record()))
        advanceUntilIdle()

        model.onEvent(LocationEvent.DiscussionDraftChanged("  can we park a truck there?  "))
        model.onEvent(LocationEvent.SendDiscussion)
        advanceUntilIdle()

        assertEquals("rec-1" to "can we park a truck there?", repository.sent, "the body is trimmed")
        assertEquals("", model.state.value.discussionDraft, "the box empties on success")
        assertEquals(2, repository.asked.size, "the thread is re-read")
    }

    /** An empty line is not a message. */
    @Test
    fun `a blank draft sends nothing`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = viewModel(repository)
        model.onEvent(LocationEvent.View(record()))
        advanceUntilIdle()

        model.onEvent(LocationEvent.DiscussionDraftChanged("   "))
        model.onEvent(LocationEvent.SendDiscussion)
        advanceUntilIdle()

        assertEquals(null, repository.sent)
    }

    /** A refused post keeps the words, and says why. */
    @Test
    fun `a refused post keeps the draft`() = runTest(dispatcher) {
        val repository = FakeRepository(
            sendFailure = ZillitError.Http(status = 200, serverMessage = "location_access_denied"),
        )
        val model = viewModel(repository)
        model.onEvent(LocationEvent.View(record()))
        advanceUntilIdle()

        model.onEvent(LocationEvent.DiscussionDraftChanged("hello"))
        model.onEvent(LocationEvent.SendDiscussion)
        advanceUntilIdle()

        assertEquals("hello", model.state.value.discussionDraft, "words are not thrown away on failure")
        assertTrue(model.state.value.error != null)
    }

    /** Closing the record forgets the thread — a stale one must not follow. */
    @Test
    fun `closing clears the discussion`() = runTest(dispatcher) {
        val repository = FakeRepository(messages = listOf(line("m1", "first")))
        val model = viewModel(repository)
        model.onEvent(LocationEvent.View(record()))
        advanceUntilIdle()

        model.onEvent(LocationEvent.CloseView)

        assertTrue(model.state.value.discussion.isEmpty())
    }

    private fun line(id: String, body: String) =
        LocationMessage(id = id, senderId = "u9", body = body, sentAtMillis = 1, isMine = false)

    private fun record() = LocationMedia(
        id = "rec-1",
        location = "Studio floor",
        sceneNumber = "12",
        episodes = emptyList(),
        city = "Mumbai",
        address = "",
        description = "",
        contactName = "",
        email = "",
        phone = "",
        countryCode = "",
        link = "",
        attachment = null,
        linkAttachment = null,
        status = LocationStatus.Selected,
        uploadedBy = "u1",
        createdMs = 0,
        updatedMs = 0,
        deleted = false,
        discussion = true,
    )

    private fun viewModel(repository: LocationRepository) = LocationViewModel(
        repository = repository,
        transfer = NoTransfer,
        resolveViewer = { LocationViewer(ready = true) },
        nowMillis = { 1_000L },
    )

    private object NoTransfer : LocationTransfer {
        override suspend fun upload(file: PickedLocationFile) =
            ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun fetch(attachment: MediaAttachment) =
            ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) =
            ZillitResult.Failure(ZillitError.Unknown("no"))
    }

    private class FakeRepository(
        private val messages: List<LocationMessage> = emptyList(),
        private val sendFailure: ZillitError? = null,
    ) : LocationRepository {

        override val refreshes: Flow<Unit> = emptyFlow()
        val asked = mutableListOf<String>()
        var sent: Pair<String, String>? = null

        override suspend fun messages(
            recordId: String,
            beforeMillis: Long,
            page: Int,
        ): ZillitResult<List<LocationMessage>> {
            asked += recordId
            return ZillitResult.Success(messages)
        }

        override suspend fun sendMessage(recordId: String, body: String): ZillitResult<Unit> {
            sendFailure?.let { return ZillitResult.Failure(it) }
            sent = recordId to body
            return ZillitResult.Success(Unit)
        }

        override suspend fun info(status: LocationStatus) = ZillitResult.Success(emptyList<LocationInfo>())
        override suspend fun media(
            status: LocationStatus,
            location: String?,
            sceneNumber: String?,
            episode: String?,
            beforeMs: Long,
            next: Boolean,
        ) = ZillitResult.Success(emptyList<LocationMedia>())
        override suspend fun create(
            draft: LocationDraft,
            attachment: MediaAttachment?,
            linkPreview: MediaAttachment?,
        ): ZillitResult<LocationMedia?> = ZillitResult.Success(null)
        override suspend fun update(id: String, draft: LocationDraft) = ZillitResult.Success(Unit)
        override suspend fun delete(ids: List<String>, status: LocationStatus) = ZillitResult.Success(Unit)
        override suspend fun move(
            records: List<LocationMedia>,
            from: LocationStatus,
            to: LocationStatus,
        ) = ZillitResult.Success(Unit)
        override suspend fun pdf(ids: List<String>, includeDetails: Boolean) =
            ZillitResult.Failure(ZillitError.Unknown("no pdf"))
    }
}
