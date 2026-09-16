package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.sides.domain.ScenePageDraft
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.ui.ConfirmKind
import com.zillit.desktop.feature.sides.ui.DocKind
import com.zillit.desktop.feature.sides.ui.PickPurpose
import com.zillit.desktop.feature.sides.ui.PickedDoc
import com.zillit.desktop.feature.sides.ui.SidesDialog
import com.zillit.desktop.feature.sides.ui.SidesEffect
import com.zillit.desktop.feature.sides.ui.SidesStore

/**
 * The modal forms — Add Script, the page editor, the call-sheet and
 * schedule uploads, and the destructive confirms.
 *
 * Every upload is the same two steps the web takes: the file to project
 * storage first, then the JSON body carrying the stored descriptor. A form
 * stays open on failure so the person can retry; it closes on success and
 * the owning flow refetches.
 */
internal class DialogFlow(
    private val store: SidesStore,
    private val list: ListFlow,
    private val scripts: ScriptsFlow,
    private val auto: AutoFlow,
) {

    private inline fun <reified D : SidesDialog> edit(crossinline change: D.() -> D) {
        store.update { copy(dialog = (dialog as? D)?.change() ?: dialog) }
    }

    fun title(value: String) {
        edit<SidesDialog.AddScript> { copy(title = value) }
        edit<SidesDialog.UploadDoc> { copy(title = value) }
    }

    fun sceneNumber(value: String) = edit<SidesDialog.PageEditor> { copy(sceneNumber = value) }
    fun color(value: String) = edit<SidesDialog.PageEditor> { copy(color = value) }
    fun description(value: String) = edit<SidesDialog.PageEditor> { copy(description = value) }

    fun pickFile() {
        val pdfOnly = store.current.dialog is SidesDialog.UploadDoc
        store.effect(SidesEffect.PickFile(PickPurpose.Dialog, pdfOnly = pdfOnly))
    }

    /** A picked or dropped file lands in whichever form is open. */
    fun fileChosen(file: PickedDoc) {
        val dialog = store.current.dialog ?: return
        val pdfOnly = dialog is SidesDialog.UploadDoc
        val allowed = if (pdfOnly) SidesRules.isPdf(file.name) else SidesRules.isPdfOrFdx(file.name)
        if (!allowed) {
            store.failed(if (pdfOnly) "Only PDF files are allowed" else "Only PDF or .fdx files are allowed")
            return
        }
        // The web defaults the title to the file's name when none was typed.
        edit<SidesDialog.AddScript> {
            copy(file = file, title = title.ifBlank { SidesRules.titleFromFileName(file.name) })
        }
        edit<SidesDialog.UploadDoc> {
            copy(file = file, title = title.ifBlank { SidesRules.titleFromFileName(file.name) })
        }
        edit<SidesDialog.PageEditor> { copy(file = file) }
    }

    fun clearFile() {
        edit<SidesDialog.AddScript> { copy(file = null) }
        edit<SidesDialog.UploadDoc> { copy(file = null) }
        edit<SidesDialog.PageEditor> { copy(file = null) }
    }

    fun dismiss() {
        if (store.current.dialog?.busy == true) return
        store.update { copy(dialog = null) }
    }

    fun submit() {
        when (val dialog = store.current.dialog) {
            is SidesDialog.AddScript -> addScript(dialog)
            is SidesDialog.PageEditor -> savePage(dialog)
            is SidesDialog.UploadDoc -> uploadDoc(dialog)
            is SidesDialog.Confirm -> confirm(dialog)
            null -> Unit
        }
    }

    private fun setBusy(busy: Boolean) {
        edit<SidesDialog.AddScript> { copy(busy = busy) }
        edit<SidesDialog.PageEditor> { copy(busy = busy) }
        edit<SidesDialog.UploadDoc> { copy(busy = busy) }
        edit<SidesDialog.Confirm> { copy(busy = busy) }
    }

    private suspend fun upload(file: PickedDoc): ZillitResult<StoredAttachment> =
        store.transfer.upload(file.name, file.bytes)

    private fun addScript(dialog: SidesDialog.AddScript) {
        val title = dialog.title.trim()
        if (title.isBlank()) return store.failed("Script name is required")
        setBusy(true)
        store.runTask {
            val attachment = dialog.file?.let { file ->
                when (val stored = upload(file)) {
                    is ZillitResult.Success -> stored.data
                    is ZillitResult.Failure -> {
                        setBusy(false)
                        return@runTask store.failed(stored.error)
                    }
                }
            }
            when (val created = store.repository.createScript(title, attachment)) {
                is ZillitResult.Success -> {
                    store.update { copy(dialog = null) }
                    store.notice("Script added")
                    scripts.load()
                }
                is ZillitResult.Failure -> {
                    setBusy(false)
                    store.failed(created.error)
                }
            }
        }
    }

    private fun savePage(dialog: SidesDialog.PageEditor) {
        val sceneNumber = dialog.sceneNumber.trim()
        if (sceneNumber.isBlank()) return store.failed("Scene number (title) is required")
        if (!dialog.isEdit && dialog.file == null) return store.failed("Please attach a PDF or .fdx file")
        setBusy(true)
        store.runTask {
            val attachment = dialog.file?.let { file ->
                when (val stored = upload(file)) {
                    is ZillitResult.Success -> stored.data
                    is ZillitResult.Failure -> {
                        setBusy(false)
                        return@runTask store.failed(stored.error)
                    }
                }
            }
            val draft = ScenePageDraft(sceneNumber, dialog.color, dialog.description.trim(), attachment)
            val saved = dialog.pageId?.let { store.repository.updateScenePage(it, draft) }
                ?: store.repository.createScenePage(dialog.scriptId, draft)
            when (saved) {
                is ZillitResult.Success -> {
                    store.update { copy(dialog = null) }
                    store.notice(if (dialog.isEdit) "Page updated" else "Page added")
                    scripts.reloadPages(dialog.scriptId)
                }
                is ZillitResult.Failure -> {
                    setBusy(false)
                    store.failed(saved.error)
                }
            }
        }
    }

    private fun uploadDoc(dialog: SidesDialog.UploadDoc) {
        val file = dialog.file ?: return
        val scriptId = store.current.auto?.scriptId.orEmpty()
        setBusy(true)
        store.runTask {
            val attachment = when (val stored = upload(file)) {
                is ZillitResult.Success -> stored.data
                is ZillitResult.Failure -> {
                    setBusy(false)
                    return@runTask store.failed(stored.error)
                }
            }
            val title = dialog.title.trim()
            when (dialog.kind) {
                DocKind.CallSheet -> when (val up = store.repository.uploadCallSheet(scriptId, title, attachment)) {
                    is ZillitResult.Success -> {
                        store.update { copy(dialog = null) }
                        store.notice("Call sheet added! ${up.data.sceneCount} scenes found.")
                        auto.callSheetUploaded(up.data.callSheet?.id)
                    }
                    is ZillitResult.Failure -> {
                        setBusy(false)
                        store.failed(up.error)
                    }
                }
                DocKind.Schedule -> when (val up = store.repository.uploadSchedule(scriptId, title, attachment)) {
                    is ZillitResult.Success -> {
                        store.update { copy(dialog = null) }
                        store.notice("Schedule added!")
                        auto.scheduleUploaded(up.data?.id)
                    }
                    is ZillitResult.Failure -> {
                        setBusy(false)
                        store.failed(up.error)
                    }
                }
            }
        }
    }

    private fun confirm(dialog: SidesDialog.Confirm) {
        setBusy(true)
        store.runTask {
            val done = when (dialog.kind) {
                ConfirmKind.Sides -> list.delete(dialog.id)
                ConfirmKind.Script -> scripts.delete(dialog.id)
                ConfirmKind.Page -> scripts.deletePage(dialog.id)
                ConfirmKind.CallSheet -> auto.deleteCallSheet(dialog.id)
                ConfirmKind.Schedule -> auto.deleteSchedule(dialog.id)
            }
            if (done) store.update { copy(dialog = null) } else setBusy(false)
        }
    }
}
