package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderDocuments
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealPayload
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.BuilderFileEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.dealmemo.ui.preview.FileViewer
import com.zillit.desktop.feature.dealmemo.ui.preview.FileViewerKind
import com.zillit.desktop.feature.dealmemo.ui.preview.ViewerContent
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The builder's file work: custom documents picked for a later upload,
 * Production Setup documents attached, the long-form contract uploaded on
 * pick, and the viewer over any of them.
 */
internal class BuilderFiles(private val vm: DealMemoViewModel, private val session: BuilderSession) {

    private var viewerJob: Job? = null

    fun onEvent(event: BuilderFileEvent) {
        when (event) {
            BuilderEvent.PickCustomDocuments -> pickCustomDocuments()
            is BuilderEvent.AttachAgreement -> attach(event.rowId)
            is BuilderEvent.DetachAgreement -> detach(event.rowId)
            is BuilderEvent.SetAgreementSignRequired -> setSignRequired(event.rowId, event.required)
            is BuilderEvent.ViewDocument -> viewDocument(event.docId)
            is BuilderEvent.ViewAgreement -> viewAgreement(event.rowId)
            BuilderEvent.PickLongFormContract -> pickContract()
            BuilderEvent.ViewLongFormContract -> viewContract()
            BuilderEvent.CloseViewer -> {
                viewerJob?.cancel()
                session.update { copy(viewer = null) }
            }
            BuilderEvent.DownloadViewer -> download()
            BuilderEvent.PickPassport -> pickPassport()
            is BuilderEvent.RemovePassport -> session.edit { form ->
                val kept = DealPayload.passports(form["passportAttachment"]).filterIndexed { index, _ ->
                    index != event.index
                }
                form.with("passportAttachment", JsonArray(kept))
            }
            is BuilderEvent.ViewPassport -> viewPassport(event.index)
        }
    }

    /**
     * Upload on pick, at most two in all, PDF / JPG / PNG only; one failed
     * upload adds nothing from that pick.
     */
    private fun pickPassport() {
        val store = vm.store ?: return
        val state = session.state() ?: return
        if (state.passportUploading) return
        val room = PASSPORT_MAX - DealPayload.passports(state.form["passportAttachment"]).size
        if (room <= 0) {
            vm.toast(str(S.desktop_dm_you_can_upload_up_to_2_files), DealToastTone.Error)
            return
        }
        vm.work {
            val picked = store.pickFiles(PASSPORT_TYPES, multiple = true).take(room)
            if (picked.isEmpty()) return@work
            val accepted = picked.filter { file ->
                file.name.substringAfterLast('.', "").lowercase() in PASSPORT_TYPES || file.mime in PASSPORT_MIMES
            }
            if (accepted.isEmpty()) {
                vm.toast(str(S.dm_edit_personal_passport_type), DealToastTone.Error)
                return@work
            }
            session.update { copy(passportUploading = true) }
            val uploaded = accepted.map { file ->
                val attachment = store.upload(file.name, file.mime, file.bytes).getOrNull()
                    ?.takeIf { !it.media.isNullOrEmpty() }
                    ?: return@map null
                buildJsonObject {
                    put("media", attachment.media)
                    put("bucket", attachment.bucket)
                    put("region", attachment.region)
                    put("name", file.name.ifEmpty { attachment.media.orEmpty() })
                    put("content_type", if (file.mime.startsWith("image/")) "image" else "document")
                    put("content_subtype", file.name.substringAfterLast('.', "").lowercase())
                    put("caption", "")
                }
            }
            session.update { copy(passportUploading = false) }
            if (uploaded.any { it == null }) {
                vm.toast(str(S.desktop_dm_couldnt_upload_the_passport_id_please_try), DealToastTone.Error)
                return@work
            }
            session.edit { form ->
                form.with(
                    "passportAttachment",
                    JsonArray(DealPayload.passports(form["passportAttachment"]) + uploaded.filterNotNull()),
                )
            }
        }
    }

    /** Each picked file is checked on its own; the good ones join the list and upload when the deal is saved. */
    private fun pickCustomDocuments() {
        val store = vm.store ?: return
        vm.work {
            val picked = store.pickFiles(listOf("pdf"), multiple = true)
            val accepted = picked.filter { file ->
                val error = BuilderDocuments.customFileError(file.name, file.mime, file.bytes.size)
                error?.let { vm.toast(it, DealToastTone.Error) }
                error == null
            }
            if (accepted.isEmpty()) return@work
            val rows = accepted.map { file ->
                val id = "doc-${vm.clock()}-${randomBase36()}"
                session.pendingFiles[id] = file
                BuilderDocuments.customRow(id, file.name, file.bytes.size, file.mime)
            }
            session.edit { form -> form.with("documents", JsonArray(form.list("documents") + rows)) }
        }
    }

    private fun attach(rowId: String) {
        val row = agreementRow(rowId) ?: return
        val form = session.state()?.form ?: return
        if (BuilderDocuments.isAttached(form.objects("documents"), rowId)) return
        val attached = BuilderDocuments.attachedRow(row, form.obj("psSignRequired")) ?: return
        session.edit { it.with("documents", JsonArray(it.list("documents") + attached)) }
    }

    /** The removed row's sign policy is remembered for a re-attach, without dirtying the page. */
    private fun detach(rowId: String) {
        val form = session.state()?.form ?: return
        val removed = form.objects("documents").firstOrNull {
            it["source"]?.let(Js::text) == BuilderDocuments.SOURCE_PS && it["ps_agreement_id"]?.let(Js::text) == rowId
        } ?: return
        val policy = !(removed["signRequired"].let { it is JsonPrimitive && !it.isString && it.content == "false" })
        session.raw {
            it.with("psSignRequired", JsonObject(it.obj("psSignRequired").orEmpty() + (rowId to JsonPrimitive(policy))))
        }
        session.edit { current ->
            current.with(
                "documents",
                JsonArray(current.list("documents").filterNot { element -> element == removed }),
            )
        }
    }

    private fun setSignRequired(rowId: String, required: Boolean) {
        val form = session.state()?.form ?: return
        if (BuilderDocuments.isAttached(form.objects("documents"), rowId)) {
            session.edit { current ->
                current.with(
                    "documents",
                    JsonArray(
                        current.list("documents").map { element ->
                            val row = element as? JsonObject
                            if (row != null && row["source"]?.let(Js::text) == BuilderDocuments.SOURCE_PS &&
                                row["ps_agreement_id"]?.let(Js::text) == rowId
                            ) {
                                JsonObject(row + ("signRequired" to JsonPrimitive(required)))
                            } else {
                                element
                            }
                        },
                    ),
                )
            }
        } else {
            session.raw {
                it.with(
                    "psSignRequired",
                    JsonObject(it.obj("psSignRequired").orEmpty() + (rowId to JsonPrimitive(required))),
                )
            }
        }
    }

    /** PDF, DOC or DOCX, uploaded at once — the web's eager contract upload. */
    private fun pickContract() {
        val store = vm.store ?: return
        vm.work {
            val file = store.pickFiles(listOf("pdf", "doc", "docx"), multiple = false).firstOrNull() ?: return@work
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext !in CONTRACT_EXTENSIONS) {
                vm.toast(str(S.desktop_dm_please_upload_a_pdf_or_doc_docx), DealToastTone.Error)
                return@work
            }
            session.update { copy(contractUploading = true) }
            when (val uploaded = store.upload(file.name, file.mime, file.bytes)) {
                is ZillitResult.Success -> {
                    val attachment = JsonObject(
                        uploaded.data.json + mapOf(
                            "content_type" to JsonPrimitive("document"),
                            "content_subtype" to JsonPrimitive(ext),
                            "caption" to JsonPrimitive(""),
                        ),
                    )
                    session.edit { it.with("longFormContract", attachment) }
                }
                is ZillitResult.Failure ->
                    vm.toast(str(S.desktop_dm_couldnt_upload_the_contract_please_try_again), DealToastTone.Error)
            }
            session.update { copy(contractUploading = false) }
        }
    }

    private fun viewPassport(index: Int) {
        val form = session.state()?.form ?: return
        val attachment = DealPayload.passports(form["passportAttachment"]).getOrNull(index) as? JsonObject ?: return
        open(attachment["name"]?.let(Js::text) ?: str(S.dm_step2_passport), DealAttachment(attachment))
    }

    private fun viewContract() {
        val attachment = session.state()?.form?.obj("longFormContract") ?: return
        val name = attachment["name"]?.let(Js::text) ?: str(S.contract_text)
        open(name, DealAttachment(attachment))
    }

    /** A custom document: its picked bytes before the save, its stored copy after. */
    private fun viewDocument(docId: String) {
        val row =
            session.state()?.form?.objects("documents")?.firstOrNull { it["id"]?.let(Js::text) == docId } ?: return
        val attachment = row["attachment"] as? JsonObject
        val pending = session.pendingFiles[docId]
        val title = (row["file"] as? JsonObject)?.get("name")?.let(Js::text)
            ?: attachment?.get("name")?.let(Js::text) ?: attachment?.get("title")?.let(Js::text) ?: str(S.document)
        when {
            pending != null -> openBytes(title, pending)
            attachment != null -> open(title, DealAttachment(attachment))
        }
    }

    private fun viewAgreement(rowId: String) {
        val row = agreementRow(rowId) ?: return
        val flat = BuilderDocuments.flatten(row)
        if (flat["media"] == null) return
        open(BuilderDocuments.filename(row).ifEmpty { str(S.document) }, DealAttachment(flat))
    }

    private fun agreementRow(rowId: String): JsonObject? =
        vm.ui.projectSettings.view.agreementDocuments.firstOrNull { BuilderDocuments.agreementId(it) == rowId }

    private fun open(title: String, attachment: DealAttachment) {
        show(title) {
            val store =
                vm.store ?: return@show ZillitResult.Failure(
                    ZillitError.Validation(str(S.desktop_dm_documents_cant_be_opened_here)),
                )
            when (val fetched = store.fetch(attachment)) {
                is ZillitResult.Success -> {
                    val pdf = attachment.isPdf || attachment.mime.contains("pdf")
                    ZillitResult.Success(fetched.data to render(fetched.data, pdf, attachment.isImage))
                }
                is ZillitResult.Failure -> fetched
            }
        }
    }

    private fun openBytes(title: String, file: PickedDealFile) {
        show(title) {
            val pdf = file.mime.contains("pdf") || file.name.lowercase().endsWith(".pdf")
            ZillitResult.Success(file.bytes to render(file.bytes, pdf, file.mime.startsWith("image/")))
        }
    }

    private fun show(title: String, resolve: suspend () -> ZillitResult<Pair<ByteArray, ViewerContent>>) {
        viewerJob?.cancel()
        session.update { copy(viewer = FileViewer(title = title, fileName = title, kind = FileViewerKind.Document)) }
        viewerJob = vm.work {
            when (val result = resolve()) {
                is ZillitResult.Success -> session.update {
                    copy(
                        viewer = viewer?.copy(loading = false, content = result.data.second, bytes = result.data.first),
                    )
                }
                is ZillitResult.Failure -> session.update {
                    copy(viewer = viewer?.copy(loading = false, failed = true))
                }
            }
        }
    }

    private suspend fun render(
        bytes: ByteArray,
        pdf: Boolean,
        image: Boolean,
    ): ViewerContent = withContext(vm.workDispatcher) {
        when {
            pdf ->
                vm.pdf.pages(bytes, VIEWER_WIDTH_PX).getOrNull()?.let(ViewerContent::Pages) ?: ViewerContent.Unsupported
            image -> vm.pdf.image(bytes)?.let(ViewerContent::Picture) ?: ViewerContent.Unsupported
            else -> ViewerContent.Unsupported
        }
    }

    private fun download() {
        val viewer = session.state()?.viewer ?: return
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
    }

    private fun randomBase36(): String =
        (1..RANDOM_CHARS).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    private companion object {
        const val VIEWER_WIDTH_PX = 1600
        const val RANDOM_CHARS = 6
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val PASSPORT_MAX = 2
        val CONTRACT_EXTENSIONS = setOf("pdf", "doc", "docx")
        val PASSPORT_TYPES = listOf("pdf", "jpg", "jpeg", "png")
        val PASSPORT_MIMES = setOf("application/pdf", "image/jpeg", "image/png")
    }
}
