package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.SavedRecord
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealHydration
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealPayload
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.authoring.ProjectSettingsView
import com.zillit.desktop.feature.dealmemo.domain.authoring.SeedMode
import com.zillit.desktop.feature.dealmemo.domain.authoring.SetupSection
import com.zillit.desktop.feature.dealmemo.domain.authoring.SetupSeeding
import com.zillit.desktop.feature.dealmemo.domain.isDuplicateRecord
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.TemplatePatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The builder's writes (`DMTemplateBuilderPage.jsx` B:2699-3600): the payload,
 * Save and Issue for a deal, Save Setup and its project seeding for a setup,
 * the autosave's create and update, and leaving the page.
 */
@Suppress("TooManyFunctions") // One function per web handler, named after it.
internal class BuilderSaves(private val vm: DealMemoViewModel, private val session: BuilderSession) {

    private val mode get() = session.mode

    @Suppress("CyclomaticComplexMethod") // One branch per dialog button.
    fun onEvent(event: BuilderEvent) {
        when (event) {
            BuilderEvent.Back -> requestExit()
            BuilderEvent.Save -> save()
            BuilderEvent.Issue -> issue()
            BuilderEvent.SaveSetup -> saveSetup()
            BuilderEvent.CloseValidation -> session.update { copy(validation = null) }
            BuilderEvent.ConfirmIssuePreview -> confirmIssuePreview()
            BuilderEvent.CloseIssuePreview -> session.update { if (submitting) this else copy(issuePreview = null) }
            BuilderEvent.NominalsNow -> nominalsNow()
            BuilderEvent.NominalsLater -> {
                session.update { copy(nominalPrompt = false) }
                proceedToIssue()
            }
            BuilderEvent.CloseNominalPrompt -> session.update { copy(nominalPrompt = false) }
            is BuilderEvent.EditName -> session.update { copy(nameCapture = nameCapture?.copy(name = event.name)) }
            BuilderEvent.ConfirmName -> confirmName()
            BuilderEvent.CancelName -> session.update { copy(nameCapture = null) }
            is BuilderEvent.EditSetupName -> session.update { copy(templateName = event.name) }
            BuilderEvent.ConfirmSetupName -> confirmSetupName()
            BuilderEvent.CloseSetupName -> session.update {
                if (savingTemplate) this else copy(setupNamePrompt = false)
            }
            BuilderEvent.KeepEditing -> session.update { copy(leaveGuard = false) }
            BuilderEvent.LeaveAndSave -> leaveAndSave()
            BuilderEvent.LeaveWithoutSaving -> leaveWithoutSaving()
            else -> Unit
        }
    }

    // -- the payload ------------------------------------------------------------------------------

    /**
     * `buildPayload`: the deal payload, with the minted `_id` until the server
     * knows the record, a `DM-DRFT-` crew name on a nameless deal's autosave,
     * and `notify` off for autosaves.
     */
    fun payload(state: BuilderState, form: DealForm, autosave: Boolean): JsonObject {
        val body = DealPayload.build(form, payloadContext(vm.ui, state)).toMutableMap()
        if (state.dealId == null) body["_id"] = JsonPrimitive(session.clientDealId)
        if (autosave && mode.deal && state.crewName.isEmpty()) {
            body["crew_name"] = JsonPrimitive(DEAL_DRAFT_PREFIX + session.clientDealId.take(DRAFT_SUFFIX).uppercase())
        }
        body["notify"] = JsonPrimitive(!autosave)
        return JsonObject(body)
    }

    /**
     * `ensureDocumentsUploaded`: rows already stored keep their attachment;
     * picked files upload now; a row with neither is dropped.
     */
    private suspend fun uploadDocuments(docs: List<JsonElement>): ZillitResult<List<JsonObject>> {
        val out = mutableListOf<JsonObject>()
        for (doc in docs.filterIsInstance<JsonObject>()) {
            val rowId = doc["id"]?.takeIf(Js::truthy)?.let(Js::text)
            val file = rowId?.let { session.pendingFiles[it] }
            when {
                Js.truthy(doc["attachment"]) -> out += JsonObject(doc - FILE_KEY)
                rowId != null && file != null -> when (val uploaded = uploadDocument(doc, file)) {
                    is ZillitResult.Success -> {
                        session.pendingFiles.remove(rowId)
                        out += JsonObject((doc - FILE_KEY) + ("attachment" to uploaded.data))
                    }
                    is ZillitResult.Failure -> return uploaded
                }
            }
        }
        return ZillitResult.Success(out)
    }

    /** One picked file stored, as the attachment its row will carry. */
    private suspend fun uploadDocument(doc: JsonObject, file: PickedDealFile): ZillitResult<JsonObject> {
        val store = vm.store ?: return ZillitResult.Failure(
            ZillitError.Validation(str(S.desktop_dm_documents_cant_be_uploaded_here)),
        )
        return store.upload(file.name, file.mime, file.bytes).map { stored ->
            JsonObject(
                stored.json + mapOf(
                    "content_type" to JsonPrimitive(if (file.mime.startsWith("image/")) "image" else "document"),
                    "content_subtype" to JsonPrimitive(file.name.substringAfterLast('.', "").lowercase()),
                    "file_size" to JsonPrimitive(file.bytes.size),
                    "caption" to JsonPrimitive(doc["description"]?.takeIf(Js::truthy)?.let(Js::text).orEmpty()),
                ),
            )
        }
    }

    /** Uploads the form's documents and keeps the stored rows on the form, as the web's `setForm` does. */
    private suspend fun storeDocuments(): ZillitResult<List<JsonObject>> {
        val state = session.state() ?: return ZillitResult.Success(emptyList())
        val uploaded = uploadDocuments(state.form.list("documents"))
        if (uploaded is ZillitResult.Success) session.raw { it.with("documents", JsonArray(uploaded.data)) }
        return uploaded
    }

    /** Deals take `notify` as a query parameter; the body goes without it. */
    private suspend fun createDeal(body: JsonObject): ZillitResult<SavedRecord> =
        vm.repository.createDeal(JsonObject(body - NOTIFY), notify = body.notify())

    private suspend fun updateDeal(id: String, body: JsonObject): ZillitResult<SavedRecord> =
        vm.repository.updateDeal(id, JsonObject(body - NOTIFY - STATUS), notify = body.notify())

    private fun JsonObject.notify(): Boolean = (this[NOTIFY] as? JsonPrimitive)?.content == "true"

    /** The linked bank account follows the deal's bank; a failure never fails the save. */
    private suspend fun writeBankThrough(state: BuilderState, bank: JsonElement?, silent: Boolean) {
        val accountId = state.form.text("bankAccId")
        if (accountId.isEmpty() || bank !is JsonObject) return
        val result = vm.repository.updateBankAccount(accountId, bank)
        if (result is ZillitResult.Failure && !silent) vm.toastError(result.error, "deal_bank_account_not_updated")
    }

    private fun adopt(record: SavedRecord) {
        record.reference?.let { reference -> session.update { copy(dealReference = reference) } }
    }

    // -- deal: save ---------------------------------------------------------------------------------

    /** `handleSave`: a nameless deal asks for the crew member's name first. */
    private fun save() {
        val state = session.state() ?: return
        if (state.crewName.isEmpty()) {
            session.update { copy(nameCapture = NameCapture(NameIntent.Save)) }
            return
        }
        vm.work { performSave(null) }
    }

    /**
     * `performSave`: today's memo date on every explicit save, a PATCH once the
     * deal exists (then the bank write-through), a POST with the minted id
     * before — adopting that id when the autosave's create already won.
     */
    suspend fun performSave(nameOverride: String?): Boolean {
        val start = session.state() ?: return false
        if (start.saving) return false
        session.update { copy(saving = true) }
        return try {
            saveDeal(nameOverride)
        } finally {
            session.update { copy(saving = false) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Each guard is one of the web's bail-outs.
    private suspend fun saveDeal(nameOverride: String?): Boolean {
        nameOverride?.let { name -> session.raw { it.with("fullLegalName", name) } }
        val docs = when (val uploaded = storeDocuments()) {
            is ZillitResult.Success -> uploaded.data
            is ZillitResult.Failure -> return failed(uploaded.error)
        }
        session.raw { it.with("dealMemoDate", today()) }
        val state = session.state() ?: return false
        val form = withName(state.form, nameOverride).with("documents", JsonArray(docs)).with("dealMemoDate", today())
        val body = payload(state, form, autosave = false)
        val dealId = state.dealId
        val record = if (dealId != null) {
            when (val result = updateDeal(dealId, body)) {
                is ZillitResult.Success -> result.data.also { writeBankThrough(state, body["bank"], silent = false) }
                is ZillitResult.Failure -> return failed(result.error)
            }
        } else {
            val draft = JsonObject(body + (STATUS to JsonPrimitive(DRAFT)))
            when (val created = createDeal(draft)) {
                is ZillitResult.Success -> created.data
                is ZillitResult.Failure -> {
                    if (!created.error.isDuplicateRecord()) return failed(created.error)
                    session.update { copy(dealId = session.clientDealId) }
                    when (val patched = updateDeal(session.clientDealId, body)) {
                        is ZillitResult.Success -> patched.data
                        is ZillitResult.Failure -> return failed(patched.error)
                    }
                }
            }.also { record -> record.id?.let { id -> session.update { copy(dealId = id) } } }
        }
        adopt(record)
        vm.toastSuccess(record.message, "deal_saved")
        session.userPersisted = true
        session.update { copy(dirty = false, savedRules = DealHydration.savedRules(form)) }
        return true
    }

    private fun failed(error: ZillitError): Boolean {
        vm.toastError(error)
        return false
    }

    private fun withName(form: DealForm, name: String?): DealForm = name?.let { form.with("fullLegalName", it) } ?: form

    // -- deal: issue ----------------------------------------------------------------------------------

    /**
     * `handleIssue`: every gated section is checked at once; failures flag
     * their sections, name them in one popup, and open the first. A clean walk
     * opens the preview.
     */
    private fun issue() {
        val state = session.state() ?: return
        val accountant = vm.ui.viewer.isAccountant
        val union = state.reference.selectedUnion
        val failures = LinkedHashMap<Int, List<String>>()
        GATED.forEach { sectionId ->
            DealValidators.section(sectionId, state.form, union)?.let { failures[sectionId] = it.fields }
        }
        val nominalsMissing = accountant &&
            DealValidators.missingNominals(state.form, union, vm.ui.projectSettings.view.nonUnionPaybreakdown)
                .isNotEmpty()
        if (failures.isNotEmpty()) {
            val names = BuilderSection.dealSections(accountant = true).associate { it.id to it.name }
            val first = failures.keys.first()
            session.update {
                copy(
                    issueErrors = failures,
                    showEntitlementErrors = showEntitlementErrors || DealValidators.ALLOWANCES in failures,
                    validation = BuilderValidation(
                        title = str(S.dm_quick_validation_title),
                        message = str(S.desktop_dm_each_section_below_is_highlighted_on_the),
                        fields = failures.map { (sectionId, fields) ->
                            (names[sectionId] ?: str(S.desktop_dm_section_n, sectionId)) + if (fields.isEmpty()) {
                                ""
                            } else {
                                " — ${fields.joinToString(", ")}"
                            }
                        },
                    ),
                    editingSection = first,
                )
            }
            session.requestScroll(first)
            return
        }
        session.update { copy(issueErrors = emptyMap(), showEntitlementErrors = false, issuePreview = nominalsMissing) }
    }

    private fun confirmIssuePreview() {
        val nominalsMissing = session.state()?.issuePreview ?: return
        session.update { copy(issuePreview = null) }
        if (nominalsMissing) session.update { copy(nominalPrompt = true) } else proceedToIssue()
    }

    private fun nominalsNow() {
        session.update { copy(nominalPrompt = false, editingSection = DealValidators.NOMINAL) }
        session.requestScroll(DealValidators.NOMINAL)
    }

    private fun proceedToIssue() {
        val state = session.state() ?: return
        if (state.crewName.isEmpty()) {
            session.update { copy(nameCapture = NameCapture(NameIntent.Issue)) }
            return
        }
        vm.work { performIssue(null) }
    }

    private fun confirmName() {
        val capture = session.state()?.nameCapture ?: return
        val name = capture.name.trim()
        if (name.isEmpty()) return
        session.update { copy(nameCapture = null) }
        vm.work { if (capture.intent == NameIntent.Issue) performIssue(name) else performSave(name) }
    }

    /**
     * `performIssue`: save the deal, then submit it; a refused submit leaves
     * a saved draft. Either way the page moves on to the deal.
     */
    suspend fun performIssue(nameOverride: String?) {
        val start = session.state() ?: return
        if (start.submitting) return
        session.update { copy(submitting = true) }
        try {
            issueDeal(nameOverride)
        } finally {
            session.update { copy(submitting = false) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Each guard is one of the web's bail-outs.
    private suspend fun issueDeal(nameOverride: String?) {
        nameOverride?.let { name -> session.raw { it.with("fullLegalName", name) } }
        val docs = when (val uploaded = storeDocuments()) {
            is ZillitResult.Success -> uploaded.data
            is ZillitResult.Failure -> {
                failed(uploaded.error)
                return
            }
        }
        session.raw { it.with("dealMemoDate", today()) }
        val state = session.state() ?: return
        val form = withName(state.form, nameOverride).with("documents", JsonArray(docs)).with("dealMemoDate", today())
        val body = payload(state, form, autosave = false)
        session.userPersisted = true
        val existing = state.dealId
        val targetId = if (existing != null) {
            when (val result = updateDeal(existing, body)) {
                is ZillitResult.Success -> existing.also { adopt(result.data) }
                is ZillitResult.Failure -> {
                    failed(result.error)
                    return
                }
            }
        } else {
            when (val result = createDeal(JsonObject(body + (STATUS to JsonPrimitive(DRAFT))))) {
                is ZillitResult.Success -> result.data.id.also { id ->
                    id?.let { session.update { copy(dealId = it) } }
                    adopt(result.data)
                }
                is ZillitResult.Failure -> {
                    failed(result.error)
                    return
                }
            }
        }
        // The page is leaving for the deal: nothing it holds needs saving again.
        session.autosave.disarm()
        if (targetId == null) {
            vm.navigate(DealMemoRoute.Tab(DealTab.Deals))
            return
        }
        when (val submitted = vm.repository.submitDeal(targetId)) {
            is ZillitResult.Success -> vm.toastSuccess(submitted.data, "deal_issued")
            is ZillitResult.Failure -> vm.toastError(submitted.error)
        }
        vm.navigate(DealMemoRoute.Deal(targetId))
    }

    // -- autosave ------------------------------------------------------------------------------------

    /** A discarded page never creates; a setup creates its `TPL-DRFT-` row, a deal its draft. */
    @Suppress("ReturnCount") // Each guard is one of the web's bail-outs.
    suspend fun autosaveCreate(): ZillitResult<SavedRecord?> {
        if (session.discarded) return ZillitResult.Success(null)
        val state = session.state() ?: return ZillitResult.Success(null)
        val docs = when (val uploaded = storeDocuments()) {
            is ZillitResult.Success -> uploaded.data
            is ZillitResult.Failure -> return uploaded
        }
        val fresh = payload(state, state.form.with("documents", JsonArray(docs)), autosave = true)
        if (mode.setup) return createTemplateDraft(fresh)
        return when (val created = createDeal(JsonObject(fresh + (STATUS to JsonPrimitive(DRAFT))))) {
            is ZillitResult.Success -> {
                val record = created.data
                session.update { copy(dealId = record.id ?: dealId, dirty = false) }
                adopt(record)
                ZillitResult.Success(record)
            }
            is ZillitResult.Failure -> if (created.error.isDuplicateRecord()) {
                session.update { copy(dealId = session.clientDealId, dirty = false) }
                ZillitResult.Success(SavedRecord(session.clientDealId, null, null))
            } else {
                created
            }
        }
    }

    private suspend fun createTemplateDraft(fresh: JsonObject): ZillitResult<SavedRecord?> {
        val draftName = TEMPLATE_DRAFT_PREFIX + session.clientTemplateId.take(DRAFT_SUFFIX).uppercase()
        val body = JsonObject(
            fresh + mapOf("_id" to JsonPrimitive(session.clientTemplateId), "name" to JsonPrimitive(draftName)),
        )
        return when (val created = vm.repository.createTemplate(body)) {
            is ZillitResult.Success -> {
                val id = created.data.id ?: session.clientTemplateId
                session.update { copy(autosavedTemplateId = id, dirty = false) }
                vm.templateStore.upsert(
                    id,
                    TemplatePatch(name = draftName, form = fresh, createdBy = vm.ui.viewer.userId),
                )
                ZillitResult.Success(created.data)
            }
            // The other create path won: adopt the minted id, with no store write.
            is ZillitResult.Failure -> if (created.error.isDuplicateRecord()) {
                session.update { copy(autosavedTemplateId = session.clientTemplateId, dirty = false) }
                ZillitResult.Success(SavedRecord(session.clientTemplateId, null, null))
            } else {
                created
            }
        }
    }

    /** A setup's PATCH never sends a name — it would stomp one being typed. */
    @Suppress("ReturnCount") // Each guard is one of the web's bail-outs.
    suspend fun autosaveUpdate(): ZillitResult<Unit> {
        if (session.discarded) return ZillitResult.Success(Unit)
        val state = session.state() ?: return ZillitResult.Success(Unit)
        val docs = when (val uploaded = storeDocuments()) {
            is ZillitResult.Success -> uploaded.data
            is ZillitResult.Failure -> return uploaded
        }
        val fresh = payload(state, state.form.with("documents", JsonArray(docs)), autosave = true)
        if (mode.setup) {
            val templateId = state.autosavedTemplateId ?: return ZillitResult.Success(Unit)
            return when (val result = vm.repository.updateTemplate(templateId, fresh)) {
                is ZillitResult.Success -> {
                    vm.templateStore.upsert(templateId, TemplatePatch(form = fresh))
                    session.update { copy(dirty = false) }
                    ZillitResult.Success(Unit)
                }
                is ZillitResult.Failure -> result
            }
        }
        val dealId = state.dealId ?: return ZillitResult.Success(Unit)
        return when (val result = updateDeal(dealId, fresh)) {
            is ZillitResult.Success -> {
                writeBankThrough(state, fresh["bank"], silent = true)
                adopt(result.data)
                session.update { copy(dirty = false) }
                ZillitResult.Success(Unit)
            }
            is ZillitResult.Failure -> result
        }
    }

    // -- setup: save ------------------------------------------------------------------------------------

    /** `handleSaveAsTemplate`: the setup gate, then the name — or straight through for a named setup being updated. */
    private fun saveSetup() {
        val state = session.state() ?: return
        val missing = BuilderSeeds.missingSetupFields(state.form, vm.ui.projectSettings.view)
        if (missing.isNotEmpty()) {
            session.update {
                copy(
                    validation = BuilderValidation(
                        title = str(S.desktop_dm_kind_is_incomplete, BuilderSeeds.setupKindLabel(form)),
                        message = if (isNonUnionId(form.text("union"))) {
                            str(S.dm_builder_incomplete_non_union)
                        } else {
                            str(S.dm_builder_incomplete_union)
                        },
                        fields = missing,
                    ),
                )
            }
            return
        }
        if (mode.templateId != null && state.templateName.isNotBlank()) {
            confirmSetupName()
            return
        }
        session.update {
            copy(templateName = if (mode.templateId == null) "" else templateName, setupNamePrompt = true)
        }
    }

    /**
     * `confirmSaveTemplate`: a rename-PATCH of the row autosave created (or the
     * one being edited), otherwise a create with the minted id; then the lists,
     * the project's empty sections seeded, its Global sections synced, and the
     * hub.
     */
    private fun confirmSetupName() {
        val state = session.state() ?: return
        val name = state.templateName.trim()
        if (name.isEmpty() || state.savingTemplate) return
        session.update { copy(savingTemplate = true) }
        vm.work {
            try {
                saveTemplate(name)
            } finally {
                session.update { copy(savingTemplate = false) }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun saveTemplate(name: String) {
        val state = session.state() ?: return
        val body = payload(state, state.form, autosave = false)
        val named = JsonObject(body + ("name" to JsonPrimitive(name)))
        val autosavedId = state.autosavedTemplateId
        val result = if (autosavedId != null) {
            vm.repository.updateTemplate(autosavedId, named)
        } else {
            val createBody = JsonObject(
                body + mapOf("_id" to JsonPrimitive(session.clientTemplateId), "name" to JsonPrimitive(name)),
            )
            when (val created = vm.repository.createTemplate(createBody)) {
                is ZillitResult.Success -> created.also {
                    session.update { copy(autosavedTemplateId = created.data.id ?: session.clientTemplateId) }
                }
                is ZillitResult.Failure -> if (created.error.isDuplicateRecord()) {
                    session.update { copy(autosavedTemplateId = session.clientTemplateId) }
                    vm.repository.updateTemplate(session.clientTemplateId, named)
                } else {
                    created
                }
            }
        }
        val record = when (result) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> {
                vm.toastError(result.error, "template_save_failed")
                return
            }
        }
        val savedId = record.id ?: autosavedId ?: session.clientTemplateId
        val local = TemplatePatch(name = name, form = body, createdBy = vm.ui.viewer.userId)
        vm.templateStore.upsert(savedId, local)
        vm.templateStore.refresh(savedId, local)
        session.userPersisted = true
        vm.toastSuccess(record.message, if (mode.templateId != null) "template_updated" else "template_saved")
        session.update { copy(setupNamePrompt = false) }
        seedSetup(state)
        syncGlobalSections(state)
        session.update { copy(templateName = if (mode.templateId == null) "" else templateName, dirty = false) }
        session.autosave.disarm()
        val agreement = (body["territory_union"] as? JsonObject)?.get("agreement_identifier")
            ?.takeUnless { it is JsonNull }?.let(Js::text) ?: state.form.text("union")
        val group = if (isNonUnionId(agreement)) SetupGroup.NonUnion else SetupGroup.Union
        vm.navigate(mode.exitTo ?: DealMemoRoute.SetupHub(group))
    }

    /**
     * `seedSetupFromTemplate`: the project's still-empty Deal Memo Setup
     * sections take this setup's values, judged against a fresh read. Never
     * fails the save it follows.
     */
    private suspend fun seedSetup(state: BuilderState) {
        val docs = uploadDocuments(state.form.list("documents")).getOrNull() ?: state.form.objects("documents")
        val fresh =
            vm.repository.projectSettings().getOrNull()?.let(::ProjectSettingsView) ?: vm.ui.projectSettings.view
        val patches = SetupSeeding.patches(state.form, fresh, docs, state.setupBureaus, SeedMode.Seed, vm.clock())
        if (patches.isEmpty()) return
        val (written, failed) = writeSections(patches)
        if (written.isNotEmpty()) {
            vm.toast(
                str(S.desktop_dm_setup_started_from_template, SetupSeeding.labels(written)),
                DealToastTone.Success,
            )
            vm.refreshProjectSettings()
        }
        if (failed.isNotEmpty()) {
            vm.toast(
                str(S.desktop_dm_saved_template_but_couldnt_add, SetupSeeding.labels(failed)),
                DealToastTone.Error,
            )
        }
    }

    /**
     * `syncGlobalSetupSections`: every Global section whose value differs
     * from the project's is written in full. Compared against a fresh read, so
     * a section the seed just wrote isn't written — and announced — twice.
     */
    private suspend fun syncGlobalSections(state: BuilderState) {
        val settings =
            vm.repository.projectSettings().getOrNull()?.let(::ProjectSettingsView) ?: vm.ui.projectSettings.view
        val patches = SetupSeeding.patches(state.form, settings, null, state.setupBureaus, SeedMode.Replace, vm.clock())
        val changed = SetupSection.GLOBAL.map { it.second }
            .filter { section -> SetupSeeding.sectionChanged(patches[section], settings.slice(section.key)) }
            .associateWith { patches.getValue(it) }
        if (changed.isEmpty()) return
        val (written, failed) = writeSections(changed)
        if (written.isNotEmpty()) {
            vm.toast(str(S.desktop_dm_setup_updated_sections, SetupSeeding.labels(written)), DealToastTone.Success)
        }
        if (failed.isNotEmpty()) {
            vm.toast(
                str(S.desktop_dm_saved_setup_but_couldnt_update, SetupSeeding.labels(failed)),
                DealToastTone.Error,
            )
        }
        vm.refreshProjectSettings()
    }

    /** Each section on its own; one failing never costs the others. */
    private suspend fun writeSections(
        patches: Map<SetupSection, JsonElement>,
    ): Pair<List<SetupSection>, List<SetupSection>> {
        val results = coroutineScope {
            patches.map { (section, body) ->
                async { section to (vm.repository.writeProjectSection(section.project, body) is ZillitResult.Success) }
            }.awaitAll()
        }
        return results.filter { it.second }.map { it.first } to results.filterNot { it.second }.map { it.first }
    }

    // -- leaving ------------------------------------------------------------------------------------------

    /** Back: the leave guard while something is unsaved, else straight out. */
    private fun requestExit() {
        val state = session.state() ?: return
        if (state.dirty) session.update { copy(leaveGuard = true) } else exit()
    }

    private fun exit() = vm.navigate(mode.backTo ?: mode.exitTo ?: BuilderActions.fallbackExit(mode))

    /** A setup goes through Save Setup; a deal saves and leaves only when the save landed. */
    private fun leaveAndSave() {
        if (mode.setup) {
            session.update { copy(leaveGuard = false) }
            saveSetup()
            return
        }
        vm.work {
            val saved = performSave(null)
            session.update { copy(leaveGuard = false) }
            if (saved) exit()
        }
    }

    /**
     * Leave Without Saving: autosave stops before anything else, the create
     * already on the wire is waited for, and only a draft this visit's
     * autosave made is deleted. The page is left whatever the delete says.
     */
    private fun leaveWithoutSaving() {
        session.discarded = true
        session.autosave.disarm()
        session.update { copy(leaveGuard = false) }
        vm.work {
            val created = session.autosave.settleCreate()
            val minted = if (mode.setup) session.clientTemplateId else session.clientDealId
            val settledId = created?.id ?: minted.takeIf { created != null }
            val state = session.state()
            val autosavedId = if (mode.setup) state?.autosavedTemplateId ?: settledId else state?.dealId ?: settledId
            BuilderSeeds.discardTarget(mode.setup, mode.preOwnedId, autosavedId, session.userPersisted)?.let { target ->
                if (target.template) {
                    if (vm.repository.deleteTemplate(target.id) is ZillitResult.Success) {
                        vm.templateStore.remove(target.id)
                    }
                } else {
                    vm.repository.delete(target.id)
                }
            }
            exit()
        }
    }

    private fun today(): String = PayloadParts.fromEpoch(JsonPrimitive(vm.clock()))

    private companion object {
        const val DEAL_DRAFT_PREFIX = "DM-DRFT-"
        const val TEMPLATE_DRAFT_PREFIX = "TPL-DRFT-"
        const val DRAFT_SUFFIX = 4
        const val NOTIFY = "notify"
        const val STATUS = "status"
        const val DRAFT = "draft"
        const val FILE_KEY = "file"

        /** Issue's walk, in its order; Nominal Coding only advises, so it never fails here. */
        val GATED = listOf(
            DealValidators.TERRITORY,
            DealValidators.CREW,
            DealValidators.PERSONAL,
            DealValidators.EMPLOYMENT,
            DealValidators.DEAL,
            DealValidators.RATES,
            DealValidators.ALLOWANCES,
        )
    }
}
