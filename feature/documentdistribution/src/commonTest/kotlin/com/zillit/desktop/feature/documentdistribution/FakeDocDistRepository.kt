package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.HistoryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.PublicationCategory
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.ZipRecipient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A repository that answers everything with an empty success, for tests to
 * override the one or two calls they care about.
 */
@Suppress("TooManyFunctions") // One override per repository operation.
internal open class FakeDocDistRepository(
    override val refreshes: Flow<DocDistRefresh> = emptyFlow(),
) : DocDistRepository {
    var libraryLoads = 0
    var historyLoads = 0

    override suspend fun folders(): ZillitResult<List<LibraryFolder>> {
        libraryLoads++
        return ZillitResult.Success(emptyList())
    }
    override suspend fun createFolder(
        name: String,
        parentId: String?,
        description: String,
        folderDate: String?,
    ): ZillitResult<Unit> =
        ZillitResult.Success(Unit)
    override suspend fun updateFolder(folderId: String, name: String, description: String): ZillitResult<Unit> =
        ZillitResult.Success(
        Unit,
    )
    override suspend fun deleteFolder(folderId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun moveFolders(folderIds: List<String>, parentId: String?): ZillitResult<Unit> =
        ZillitResult.Success(
        Unit,
    )
    override suspend fun documents(query: LibraryQuery): ZillitResult<LibraryPage> =
        ZillitResult.Success(LibraryPage(emptyList(), total = 0))
    override suspend fun documentsInFolders(folderIds: Collection<String>): ZillitResult<List<LibraryDocument>> =
        ZillitResult.Success(emptyList())
    override suspend fun allDocuments(): ZillitResult<List<LibraryDocument>> = ZillitResult.Success(emptyList())
    override suspend fun documentsByIds(ids: List<String>): ZillitResult<List<LibraryDocument>> = ZillitResult.Success(
        emptyList(),
    )
    override suspend fun ephemeralByIds(ids: List<String>): ZillitResult<List<LibraryDocument>> = ZillitResult.Success(
        emptyList(),
    )
    override suspend fun uploadDocument(
        file: LocalFile,
        folderId: String?,
        documentDate: String?,
    ): ZillitResult<LibraryDocument> =
        ZillitResult.Failure(ZillitError.Unknown("unused"))
    override suspend fun uploadEphemeral(file: LocalFile): ZillitResult<LibraryDocument> =
        ZillitResult.Failure(ZillitError.Unknown("unused"))
    override suspend fun deleteEphemeral(attachmentId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun documentBytes(document: LibraryDocument): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("unused"))
    override suspend fun watermarkedCopy(
        documentId: String,
        text: String,
        style: WatermarkStyle,
    ): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("unused"))
    override suspend fun watermarkedZip(
        documentIds: List<String>,
        recipients: List<ZipRecipient>,
        style: WatermarkStyle,
    ): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("unused"))
    override suspend fun deleteDocument(documentId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun moveDocuments(documentIds: List<String>, folderId: String?): ZillitResult<Unit> =
        ZillitResult.Success(
        Unit,
    )
    override suspend fun documentUrl(document: LibraryDocument): ZillitResult<String> = ZillitResult.Success("url")
    override suspend fun lists(): ZillitResult<List<DistributionList>> = ZillitResult.Success(
        emptyList<DistributionList>(),
    )
    override suspend fun createList(
        name: String,
        recipients: List<Recipient>,
        description: String,
    ): ZillitResult<DistributionList> =
        ZillitResult.Success(DistributionList(id = "new", name = name, recipients = recipients))
    override suspend fun updateList(
        listId: String,
        name: String?,
        recipients: List<Recipient>,
        description: String?,
    ): ZillitResult<Unit> =
        ZillitResult.Success(Unit)
    override suspend fun deleteList(listId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun exportList(listId: String): ZillitResult<ByteArray> = ZillitResult.Failure(
        ZillitError.Unknown("unused"),
    )
    override suspend fun contacts(): ZillitResult<List<Contact>> = ZillitResult.Success(emptyList<Contact>())
    override suspend fun saveContact(contact: Contact): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun deleteContact(email: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun templates(): ZillitResult<List<EmailTemplate>> = ZillitResult.Success(
        emptyList<EmailTemplate>(),
    )
    override suspend fun saveTemplate(template: EmailTemplate): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun deleteTemplate(templateId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun send(distribution: NewDistribution): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun senders(): ZillitResult<List<DistributionSender>> = ZillitResult.Success(emptyList())
    override suspend fun history(
        page: Int,
        search: String,
        senderIds: Set<String>,
        limit: Int,
    ): ZillitResult<HistoryPage> {
        historyLoads++
        return ZillitResult.Success(HistoryPage(emptyList(), 0))
    }
    override suspend fun distribution(id: String): ZillitResult<Distribution> = ZillitResult.Failure(
        ZillitError.Unknown("unused"),
    )
    override suspend fun openStatus(uniqueIds: List<String>): ZillitResult<Map<String, DeliveryStatus>> =
        ZillitResult.Success(
        emptyMap<String,
        DeliveryStatus>(),
    )
    override suspend fun publicationCategories(): ZillitResult<List<PublicationCategory>> = ZillitResult.Success(
        emptyList<PublicationCategory>(),
    )
    override suspend fun publishedFiles(category: String): ZillitResult<List<PublishedFile>> = ZillitResult.Success(
        emptyList(),
    )
    override suspend fun publish(category: String, draft: PublishDraft): ZillitResult<Unit> = ZillitResult.Success(Unit)
}
