package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.documentdistribution.data.DOC_DIST_REFRESH_BY_EVENT
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.PublicationCategory
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/**
 * A `document_distribution:*` pulse reloads the destination it names, only
 * while that destination is on screen — the web screens' silent refetches
 * (`useDocumentDistribution.js:132-138`, `HistoryDrawer.jsx:726-741`) as
 * targeted reloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocDistSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the event → kind map ---------------------------------------------

    @Test
    fun `events name the destination their web page refreshes`() {
        assertEquals(
            DocDistRefresh.Library,
            DOC_DIST_REFRESH_BY_EVENT[SocketEventName("document_distribution:document:added")],
        )
        assertEquals(
            DocDistRefresh.History,
            DOC_DIST_REFRESH_BY_EVENT[SocketEventName("outbound:email:opened")],
            "the email service's open pixel refreshes history",
        )
        assertEquals(
            DocDistRefresh.Lists,
            DOC_DIST_REFRESH_BY_EVENT[SocketEventName("document_distribution:preset:updated")],
        )
        assertEquals(
            DocDistRefresh.Templates,
            DOC_DIST_REFRESH_BY_EVENT[SocketEventName("document_distribution:template:deleted")],
        )
    }

    /**
     * Publications are a dialog, not a page.
     *
     * `alreadyPublished` is read when the publish dialog picks a target and
     * dies with the dialog, so nothing standing goes stale. Neither phone
     * acts on these either — iOS's subject has no subscriber, Android's bus
     * event no collector — so all three clients agree.
     */
    @Test
    fun `publication events stay unsubscribed`() {
        assertEquals(null, DOC_DIST_REFRESH_BY_EVENT[SocketEventName("document_distribution:publication:added")])
        assertEquals(null, DOC_DIST_REFRESH_BY_EVENT[SocketEventName("document_distribution:publication:deleted")])
    }

    // -- the view model ----------------------------------------------------

    private class FakeRepo(refreshes: Flow<DocDistRefresh>) : FakeDocDistRepository(refreshes)

    @Test
    fun `a pulse reloads only the destination on screen`() = runTest(dispatcher) {
        val events = MutableSharedFlow<DocDistRefresh>()
        val repo = FakeRepo(refreshes = events)
        val model = DocDistViewModel(
            repository = repo,
            viewer = { DocDistViewer(userId = "u1", ready = true) },
            today = { LocalDate(2026, 8, 24) },
        )

        model.start()
        runCurrent()
        assertEquals(1, repo.libraryLoads, "start lands on the library")

        events.emit(DocDistRefresh.Library)
        runCurrent()
        assertEquals(2, repo.libraryLoads, "the pulse re-runs exactly one load")

        events.emit(DocDistRefresh.History)
        runCurrent()
        assertEquals(0, repo.historyLoads, "an event for a destination not on screen is ignored")

        model.onEvent(DocDistEvent.Open(DocDistDestination.History))
        runCurrent()
        events.emit(DocDistRefresh.History)
        runCurrent()
        assertEquals(2, repo.historyLoads, "open once, then one socket reload")
    }
}
