package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DealPdfKind
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.AdditionalDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewNames
import com.zillit.desktop.feature.dealmemo.domain.preview.DealApproval
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoCard
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.domain.preview.PreviewTab
import com.zillit.desktop.feature.dealmemo.domain.preview.PreviewTabKind
import com.zillit.desktop.feature.dealmemo.domain.preview.SignedPdf
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The files the deal page opens: the deal memo PDF (stored-first), an
 * additional document (signed-first), a passport scan, and the start form.
 */
internal class PreviewDocumentActions(private val vm: DealMemoViewModel, private val page: DealPreviewActions) {

    private var viewerJob: Job? = null

    /**
     * View PDF: the stored copy when there is one — only a signature stores it —
     * else a render for display, which is never kept. The modal is up before
     * the bytes are.
     */
    fun openDealPdf() {
        val deal = vm.ui.preview?.deal ?: return
        val fallback = "Deal-Memo-${deal.reference ?: "DealMemo"}.pdf"
        open(FileViewer(title = fallback, fileName = fallback, kind = FileViewerKind.DealPdf)) {
            val stored = SignedPdf.of(DocRead.obj(deal.json, "deal_pdf")).attachment?.takeIf { it.intact }
            val attachment = stored ?: when (val generated = generate(deal, DealPdfKind.DealMemo)) {
                is ZillitResult.Success -> DealAttachment(generated.data)
                is ZillitResult.Failure -> return@open generated
            }
            val name = attachment.name ?: "${deal.reference ?: "DealMemo"}.pdf"
            fetchPages(attachment).map { (bytes, content) -> Triple(name, bytes, content) }
        }
    }

    /** The Start Form reads as a document of its own; anything else opens its file. */
    fun openTab(tab: PreviewTab) {
        when (tab.kind) {
            PreviewTabKind.Deal -> Unit
            PreviewTabKind.StartForm -> page.updatePreview { copy(startFormOpen = true) }
            PreviewTabKind.Document -> tab.doc?.let(::openDocument)
        }
    }

    /** An additional document — its signed copy first — titled by the accountant, downloaded by its file name. */
    fun openDocument(doc: AdditionalDoc) {
        val shown = doc.shown
        open(
            FileViewer(
                title = doc.label,
                subtitle = doc.description,
                fileName = doc.filename,
                kind = FileViewerKind.Document,
                doc = doc,
            ),
        ) {
            val attachment = shown?.takeIf { it.intact }
                ?: return@open ZillitResult.Failure(ZillitError.Validation("Preview not available for this document."))
            fetchContent(attachment).map { (bytes, content) -> Triple(doc.filename, bytes, content) }
        }
    }

    fun openPassport(attachment: DealAttachment) {
        val title = attachment.name ?: attachment.title ?: "Document"
        open(FileViewer(title = title, fileName = title, kind = FileViewerKind.Passport, attachment = attachment)) {
            fetchContent(attachment).map { (bytes, content) -> Triple(title, bytes, content) }
        }
    }

    fun close() {
        viewerJob?.cancel()
        viewerJob = null
        page.updatePreview { copy(viewer = null) }
    }

    /** Download saves the bytes under the viewer's file name, and opens them. */
    fun download() {
        val viewer = vm.ui.preview?.viewer ?: return
        val bytes = viewer.bytes ?: return
        val files = vm.files ?: return
        vm.work {
            (files.save(viewer.fileName, bytes) as? ZillitResult.Failure)?.let {
                vm.toastError(it.error, "export_failed")
            }
        }
    }

    fun reset() {
        viewerJob?.cancel()
        viewerJob = null
    }

    private fun open(
        viewer: FileViewer,
        resolve: suspend () -> ZillitResult<Triple<String, ByteArray, ViewerContent>>,
    ) {
        viewerJob?.cancel()
        page.updatePreview { copy(viewer = viewer) }
        viewerJob = vm.work {
            when (val result = resolve()) {
                is ZillitResult.Success -> {
                    val (name, bytes, content) = result.data
                    page.updatePreview {
                        val current = this.viewer?.takeIf { it.kind == viewer.kind && it.title == viewer.title }
                            ?: return@updatePreview this
                        val title = if (viewer.kind == FileViewerKind.DealPdf) name else current.title
                        copy(
                            viewer = current.copy(
                                title = title,
                                fileName = name,
                                loading = false,
                                content = content,
                                bytes = bytes,
                            ),
                        )
                    }
                }
                is ZillitResult.Failure -> page.updatePreview {
                    copy(viewer = this.viewer?.takeIf { it.kind == viewer.kind }?.copy(loading = false, failed = true))
                }
            }
        }
    }

    /** A PDF's bytes and its pages. */
    private suspend fun fetchPages(attachment: DealAttachment): ZillitResult<Pair<ByteArray, ViewerContent>> {
        val store = vm.store ?: return noStore()
        val bytes = when (val fetched = store.fetch(attachment)) {
            is ZillitResult.Success -> fetched.data
            is ZillitResult.Failure -> return fetched
        }
        return withContext(vm.workDispatcher) { vm.pdf.pages(bytes, VIEWER_WIDTH_PX) }
            .map { pages -> bytes to ViewerContent.Pages(pages) }
    }

    /** Whatever the file is: pages for a PDF, a picture for an image, otherwise a download. */
    @Suppress("ReturnCount") // One return per place a document can come from.
    private suspend fun fetchContent(attachment: DealAttachment): ZillitResult<Pair<ByteArray, ViewerContent>> {
        if (attachment.isPdf || attachment.mime.contains("pdf")) return fetchPages(attachment)
        val store = vm.store ?: return noStore()
        val bytes = when (val fetched = store.fetch(attachment)) {
            is ZillitResult.Success -> fetched.data
            is ZillitResult.Failure -> return fetched
        }
        if (!attachment.isImage) return ZillitResult.Success(bytes to ViewerContent.Unsupported)
        val image = withContext(vm.workDispatcher) { vm.pdf.image(bytes) }
        return ZillitResult.Success(bytes to (image?.let(ViewerContent::Picture) ?: ViewerContent.Unsupported))
    }

    private fun <T> noStore(): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Validation("Documents can't be opened here."))

    /** `POST /deals/:id/pdf` — or the start form's — with the labels the page resolved. */
    suspend fun generate(deal: DealDoc, kind: DealPdfKind): ZillitResult<JsonObject> =
        vm.repository.generatePdf(deal.id, kind, displayContext(deal, kind))

    /**
     * The display context the server bakes into a render
     * (`DMDealPreviewPage.jsx:1964-1996`): the start form takes the first four.
     */
    private suspend fun displayContext(deal: DealDoc, kind: DealPdfKind): JsonObject {
        val state = vm.ui
        val context = DealPreviewActions.memoContext(state)
        val names = CrewNames.of(deal, context)
        val project = state.production.project
        val cd = deal.crew
        val empStatuses = if (kind == DealPdfKind.DealMemo) territoryStatuses(deal) else emptyList()
        return buildJsonObject {
            put("projectName", project.projectName)
            put("companyName", project.companyName)
            put("departmentLabel", names.department)
            put("designationLabel", names.role)
            if (kind == DealPdfKind.StartForm) {
                put("crewStartForm", JsonObject(emptyMap()))
                return@buildJsonObject
            }
            val union = DocRead.obj(deal.json, "territory_union")
            put("productionEntityLabel", MemoCard.entityLabel(DocRead.text(union, "prod_entity"), context))
            put(
                "crewLabel",
                context.labels.person(deal.userId)?.fullName?.takeIf { it.isNotEmpty() } ?: deal.crewName.orEmpty(),
            )
            val status = DocRead.text(cd, "emp_status")
            put(
                "empStatusLabel",
                status?.let { id -> empStatuses.firstOrNull { it.id == id }?.label ?: DealLabels.formatLabel(id) }
                    ?: MemoFormat.DASH,
            )
            val approvers = DealApproval.listOf(deal).mapNotNull { it.userId }.distinct()
                .mapNotNull { id -> context.labels.person(id)?.let { id to it } }
            put("approverNameById", buildJsonObject { approvers.forEach { (id, person) -> put(id, person.fullName) } })
            put(
                "approverRoleById",
                buildJsonObject {
                    approvers.forEach { (id, person) -> put(id, DealLabels.formatLabel(person.designationName)) }
                },
            )
        }
    }

    /** The territory's employment statuses, read once per territory. */
    private suspend fun territoryStatuses(deal: DealDoc): List<EmpStatus> {
        val union = DocRead.obj(deal.json, "territory_union")
        val territory = DocRead.text(union, "territory_code") ?: DocRead.text(union, "union") ?: return emptyList()
        vm.ui.production.empStatuses[territory]?.let { return it }
        val statuses = vm.reference.agreements(territory = territory).getOrNull()?.empStatuses.orEmpty()
        vm.update { copy(production = production.copy(empStatuses = production.empStatuses + (territory to statuses))) }
        return statuses
    }

    private companion object {
        /** Renders crisp at the viewer's widest, 1100 wide at 1.5×. */
        const val VIEWER_WIDTH_PX = 1600
    }
}
