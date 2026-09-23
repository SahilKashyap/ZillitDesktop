package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.preview.isDirtyAgainst
import com.zillit.desktop.feature.dealmemo.domain.preview.passportList
import com.zillit.desktop.feature.dealmemo.domain.preview.payload
import com.zillit.desktop.feature.dealmemo.ui.CrewFormEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The crew member's own details (`CrewDetailsEditorPanel.jsx`, saved by
 * `DMDealPreviewPage.jsx` `saveCrewDraft`). The draft lives here, not in the
 * form, so the memo follows every keystroke; nothing is saved until Save.
 */
internal class CrewFormActions(private val vm: DealMemoViewModel, private val page: DealPreviewActions) {

    /** Opens the form — from My Deal only, and never once the deal is live. */
    fun open() {
        val rules = DealPreviewActions.rulesFor(vm.ui) ?: return
        if (!rules.crewCanEdit) return
        page.updatePreview { copy(crewDraft = crewDraft ?: deal?.let(CrewDraft::seed), crewForm = CrewFormUi()) }
        if (vm.ui.route != DealMemoRoute.CompleteDetails) vm.navigate(DealMemoRoute.CompleteDetails)
    }

    /** A direct load of `/my-deal/complete` seeds the draft once the deal is there. */
    fun attachRoute() {
        val preview = vm.ui.preview ?: return
        val rules = DealPreviewActions.rulesFor(vm.ui) ?: return
        if (!rules.crewCanEdit) {
            vm.navigate(DealMemoRoute.Tab(DealTab.MyDeal))
            return
        }
        if (preview.crewForm == null) {
            page.updatePreview { copy(crewDraft = crewDraft ?: deal?.let(CrewDraft::seed), crewForm = CrewFormUi()) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: CrewFormEvent) {
        val preview = vm.ui.preview ?: return
        val draft = preview.crewDraft ?: return
        when (event) {
            is CrewFormEvent.Crew -> setDraft(draft.withCrew(event.key, event.value))
            is CrewFormEvent.Section -> {
                val section = DocRead.obj(draft.crewDetails, event.section) ?: JsonObject(emptyMap())
                setDraft(draft.withCrew(event.section, JsonObject(section + (event.key to event.value))))
            }
            is CrewFormEvent.Bank -> setDraft(draft.withBank(event.key, event.value))
            is CrewFormEvent.EmergencyName -> setDraft(dualWrite(draft, "emergency_contact_name", "name", event.name))
            is CrewFormEvent.EmergencyNumber ->
                setDraft(dualWrite(draft, "emergency_contact_number", "phone_number", event.number))
            is CrewFormEvent.UkPatch -> {
                val base = CrewDraft.ukFromWire(DocRead.obj(draft.crewDetails, "uk"))
                setDraft(draft.withCrew("uk", JsonObject(base + event.patch)))
            }
            CrewFormEvent.AddPassport -> addPassport()
            is CrewFormEvent.RemovePassport -> {
                val files = passportList(draft.crewDetails["passport_attachment"]).filterIndexed { i, _ ->
                    i != event.index
                }
                setDraft(draft.withCrew("passport_attachment", JsonArray(files.map { it.json })))
            }
            is CrewFormEvent.Touch -> updateForm { copy(touched = touched + event.field) }
            is CrewFormEvent.GoToStep -> updateForm { copy(step = event.index) }
            CrewFormEvent.Continue -> guarded { updateForm { copy(step = step + 1) } }
            CrewFormEvent.StepBack -> updateForm { copy(step = (step - 1).coerceAtLeast(0)) }
            CrewFormEvent.Save -> if (draft.isDirtyAgainst(preview.deal ?: return)) guarded { save() }
            CrewFormEvent.Finish -> guarded { save() }
            CrewFormEvent.Close -> close()
            CrewFormEvent.KeepEditing -> updateForm { copy(discardPrompt = false) }
            CrewFormEvent.DiscardChanges -> {
                page.updatePreview { copy(crewDraft = null, crewForm = null) }
                leave()
            }
            // The prompt's Save still refuses a malformed email or phone — the web's skips it.
            CrewFormEvent.SaveChanges -> guarded { save() }
        }
    }

    private fun dualWrite(draft: CrewDraft, flatKey: String, nestedKey: String, value: String): CrewDraft {
        val emergency = DocRead.obj(draft.crewDetails, "emergency_details") ?: JsonObject(emptyMap())
        return draft.withCrew(flatKey, JsonPrimitive(value))
            .withCrew("emergency_details", JsonObject(emergency + (nestedKey to JsonPrimitive(value))))
    }

    /** Format errors stop the action and reveal themselves; required fields never do. */
    private fun guarded(run: () -> Unit) {
        val preview = vm.ui.preview ?: return
        val deal = preview.deal ?: return
        val draft = preview.crewDraft ?: return
        if (CrewFormRules.formatErrors(deal, draft).isNotEmpty()) {
            updateForm { copy(submitAttempted = true, discardPrompt = false) }
            return
        }
        run()
    }

    private fun close() {
        val preview = vm.ui.preview ?: return
        val deal = preview.deal ?: return
        val form = preview.crewForm ?: return
        if (form.saving) return
        if (preview.crewDraft.isDirtyAgainst(deal)) {
            updateForm { copy(discardPrompt = true) }
        } else {
            page.updatePreview { copy(crewForm = null) }
            leave()
        }
    }

    /** `saveCrewDraft`: success adopts the saved deal, drops the draft and closes; failure keeps both. */
    private fun save() {
        val preview = vm.ui.preview ?: return
        val draft = preview.crewDraft ?: return
        if (preview.crewForm?.saving == true || !page.allows { crewCanEdit }) return
        updateForm { copy(saving = true) }
        vm.work {
            when (val result = vm.repository.saveCrewDetails(draft.payload())) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data.message, "personal_details_saved")
                    val saved = result.data.deal
                    page.updatePreview { copy(deal = saved ?: deal, crewDraft = null, crewForm = null) }
                    if (saved != null) {
                        vm.update { copy(myDeal = myDeal.copy(deal = saved)) }
                    } else {
                        page.refreshNow()
                    }
                    leave()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_save_personal_details")
                    updateForm { copy(saving = false) }
                }
            }
        }
    }

    /**
     * Upload on pick, at most two in all, PDF / JPG / PNG only; one failed upload
     * adds nothing from that pick. The files sit in the draft until Save.
     */
    @Suppress("ReturnCount") // Each refusal is its own toast.
    private fun addPassport() {
        val store = vm.store ?: return
        val preview = vm.ui.preview ?: return
        val draft = preview.crewDraft ?: return
        if (preview.crewForm?.uploading == true) return
        val existing = passportList(draft.crewDetails["passport_attachment"])
        val room = PASSPORT_MAX - existing.size
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
            updateForm { copy(uploading = true) }
            val uploaded = accepted.map { file ->
                store.upload(file.name, file.mime, file.bytes).getOrNull()?.takeIf { !it.media.isNullOrEmpty() }
                    ?.let { attachment ->
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
            }
            updateForm { copy(uploading = false) }
            if (uploaded.any { it == null }) {
                vm.toast(str(S.desktop_dm_couldnt_upload_the_passport_id_please_try), DealToastTone.Error)
                return@work
            }
            val current = vm.ui.preview?.crewDraft ?: return@work
            val list =
                passportList(current.crewDetails["passport_attachment"]).map { it.json } + uploaded.filterNotNull()
            setDraft(current.withCrew("passport_attachment", buildJsonArray { list.forEach(::add) }))
        }
    }

    /** Out of the form, back to My Deal. */
    private fun leave() {
        if (vm.ui.route == DealMemoRoute.CompleteDetails) vm.navigate(DealMemoRoute.Tab(DealTab.MyDeal))
    }

    private fun setDraft(draft: CrewDraft) = page.updatePreview { copy(crewDraft = draft) }

    private fun updateForm(reducer: CrewFormUi.() -> CrewFormUi) =
        page.updatePreview { copy(crewForm = crewForm?.reducer()) }

    private companion object {
        const val PASSPORT_MAX = 2
        val PASSPORT_TYPES = listOf("pdf", "jpg", "jpeg", "png")
        val PASSPORT_MIMES = setOf("application/pdf", "image/jpeg", "image/png")
    }
}
