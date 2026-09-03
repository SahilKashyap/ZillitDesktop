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

    // -- the view model ----------------------------------------------------

    @Suppress("TooManyFunctions") // One override per repository operation.
    private class FakeRepo(override val refreshes: Flow<DocDistRefresh>) : DocDistRepository {
        var libraryLoads = 0
        var historyLoads = 0
        override suspend fun folders(): ZillitResult<List<LibraryFolder>> {
            libraryLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun createFolder(name: String, parentId: String?) = ZillitResult.Success(Unit)
        override suspend fun renameFolder(folderId: String, name: String) = ZillitResult.Success(Unit)
        override suspend fun deleteFolder(folderId: String) = ZillitResult.Success(Unit)
        override suspend fun moveFolders(folderIds: List<String>, parentId: String?) =
            ZillitResult.Success(Unit)
        override suspend fun documents(query: LibraryQuery) =
            ZillitResult.Success(LibraryPage(emptyList(), total = 0))
        override suspend fun deleteDocument(documentId: String) = ZillitResult.Success(Unit)
        override suspend fun moveDocuments(documentIds: List<String>, folderId: String?) =
            ZillitResult.Success(Unit)
        override suspend fun documentUrl(document: LibraryDocument) = ZillitResult.Success("url")
        override suspend fun lists() = ZillitResult.Success(emptyList<DistributionList>())
        override suspend fun createList(name: String, recipients: List<Recipient>) =
            ZillitResult.Success(Unit)
        override suspend fun updateList(listId: String, name: String, recipients: List<Recipient>) =
            ZillitResult.Success(Unit)
        override suspend fun deleteList(listId: String) = ZillitResult.Success(Unit)
        override suspend fun contacts() = ZillitResult.Success(emptyList<Contact>())
        override suspend fun saveContact(contact: Contact) = ZillitResult.Success(Unit)
        override suspend fun deleteContact(email: String) = ZillitResult.Success(Unit)
        override suspend fun templates() = ZillitResult.Success(emptyList<EmailTemplate>())
        override suspend fun saveTemplate(template: EmailTemplate) = ZillitResult.Success(Unit)
        override suspend fun deleteTemplate(templateId: String) = ZillitResult.Success(Unit)
        override suspend fun send(distribution: NewDistribution) = ZillitResult.Success(Unit)
        override suspend fun senders(): ZillitResult<List<DistributionSender>> =
            ZillitResult.Success(emptyList())

        override suspend fun history(
            page: Int,
            search: String,
            senderIds: Set<String>,
        ): ZillitResult<List<Distribution>> {
            historyLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun distribution(id: String): ZillitResult<Distribution> =
            ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun openStatus(uniqueIds: List<String>) =
            ZillitResult.Success(emptyMap<String, DeliveryStatus>())
        override suspend fun publicationCategories() =
            ZillitResult.Success(emptyList<PublicationCategory>())
        override suspend fun publishedFiles(category: String) =
            ZillitResult.Success(emptyList<PublishedFile>())
        override suspend fun publish(category: String, draft: PublishDraft) =
            ZillitResult.Success(Unit)
    }

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
