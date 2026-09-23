package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.ProjectSection
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderDocuments
import com.zillit.desktop.feature.dealmemo.domain.authoring.CompanyDraft
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rules.AgreementRuleImport
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRows
import com.zillit.desktop.feature.dealmemo.domain.rules.NonUnionPayBreakdown
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleList
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleLists
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.SetupPageEvent
import com.zillit.desktop.feature.dealmemo.ui.preview.RulesEditorState
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A setup page's writes to Production Setup (`DMTemplateBuilderPage.jsx`,
 * `Step1Territory.jsx`): a new company, the non-union pay rules and their
 * import, the Day Types, and the agreement documents. Each is saved at once,
 * on its own — Save Setup never carries them.
 */
@Suppress("TooManyFunctions") // One function per write the page offers.
internal class BuilderSetup(private val vm: DealMemoViewModel, private val session: BuilderSession) {

    private var importListingJob: Job? = null
    private var importAgreementJob: Job? = null
    private var dayTypesFor: JsonObject? = null
    private var importSequence = 0

    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: SetupPageEvent) {
        when (event) {
            BuilderEvent.AddCompany -> addCompany()
            is BuilderEvent.CloseCompanyNotice -> page {
                copy(companyNotice = false, companyDraft = if (event.proceed) newCompany() else companyDraft)
            }
            is BuilderEvent.EditCompany -> page {
                copy(companyDraft = companyDraft?.let { JsonObject(it + event.values) })
            }
            BuilderEvent.SaveCompany -> saveCompany()
            BuilderEvent.CloseCompany -> page { if (companySaving) this else copy(companyDraft = null) }
            BuilderEvent.OpenProjectRules -> openProjectRules()
            is BuilderEvent.PickImportTerritory -> pickImportTerritory(event.territory.orEmpty())
            is BuilderEvent.PickImportAgreement -> pickImportAgreement(event.agreementId.orEmpty())
            BuilderEvent.ConfirmRuleImport -> confirmRuleImport()
            BuilderEvent.CloseRuleImport -> page { copy(ruleImport = null) }
            is BuilderEvent.EditDayType -> dayTypes {
                copy(rows = rows.map { if (it.id == event.row.id) event.row else it })
            }
            BuilderEvent.AddDayType -> addDayType()
            is BuilderEvent.RemoveDayType -> dayTypes { copy(rows = rows.filterNot { it.id == event.id }) }
            BuilderEvent.SaveDayTypes -> saveDayTypes()
            BuilderEvent.QueueAgreementDocuments -> queueDocuments()
            is BuilderEvent.EditPendingDocument -> page {
                copy(
                    pendingDocuments = pendingDocuments.map {
                        if (it.id == event.id) it.copy(title = event.title, description = event.description) else it
                    },
                )
            }
            is BuilderEvent.RemovePendingDocument -> page {
                copy(pendingDocuments = pendingDocuments.filterNot { it.id == event.id })
            }
            BuilderEvent.SaveAgreementDocuments -> saveDocuments()
            is BuilderEvent.EditAgreementDocument -> page {
                copy(documentEdits = documentEdits + (event.id to DocumentEdit(event.title, event.description)))
            }
            is BuilderEvent.CommitAgreementDocument -> commitDocument(event.id)
            is BuilderEvent.DeleteAgreementDocument -> deleteDocument(event.id)
        }
    }

    /** The Day Types draft follows the project's saved list until someone edits it. */
    fun reconcile() {
        val settings = vm.ui.projectSettings
        if (!settings.loaded) return
        val json = settings.view.json
        val draft = session.state()?.setupPage?.dayTypes
        if (draft != null && (draft.dirty || dayTypesFor === json)) return
        dayTypesFor = json
        val rows =
            DayTypeRows.rows((json?.get("day_types") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }, ::rowId)
        page { copy(dayTypes = DayTypesDraft(rows = rows, baseline = rows)) }
    }

    // -- companies ---------------------------------------------------------------------------------

    private fun addCompany() {
        val settings = vm.ui.projectSettings
        page {
            if (settings.loaded && settings.view.companies.isEmpty()) {
                copy(companyNotice = true)
            } else {
                copy(companyDraft = newCompany())
            }
        }
    }

    private fun newCompany(): JsonObject = CompanyDraft.blank("co-${vm.clock()}-${Random.nextInt(ID_RANGE)}")

    /** The whole companies list with the new one on the end; the new company becomes the setup's entity. */
    private fun saveCompany() {
        val state = session.state() ?: return
        val draft = state.setupPage.companyDraft ?: return
        if (state.setupPage.companySaving || !CompanyDraft.ready(draft)) return
        page { copy(companySaving = true) }
        vm.work {
            val companies = vm.ui.projectSettings.view.companies
            val claimed = (draft["bank_ids"] as? JsonArray).orEmpty().map(Js::text).toSet()
            val others = companies.map { company ->
                val banks = (company["bank_ids"] as? JsonArray).orEmpty().filterNot { Js.text(it) in claimed }
                JsonObject(company + ("bank_ids" to JsonArray(banks)))
            }
            when (val result = vm.repository.writeProjectSection(ProjectSection.Companies, JsonArray(others + draft))) {
                is ZillitResult.Success -> {
                    result.data?.takeIf { it.isNotBlank() }?.let { vm.toast(it, DealToastTone.Success) }
                    vm.refreshProjectSettings()
                    page { copy(companyDraft = null, companySaving = false) }
                    session.edit { it.with("productionEntity", Js.text(draft["id"])) }
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    page { copy(companySaving = false) }
                }
            }
        }
    }

    // -- non-union pay rules ---------------------------------------------------------------------

    private fun openProjectRules() {
        val lists = NonUnionPayBreakdown.lists(projectBreakdown(), vm.newId)
        val rows = BulkRules.rows(lists, vm.newId).ifEmpty { listOf(BulkRules.blank(vm.newId())) }
        vm.ensureCoa()
        session.update {
            copy(rules = RulesEditorState(rows = rows, agreementImport = true), rulesTarget = RulesTarget.Project)
        }
    }

    /** The whole slice back, the grid's lists over it; the grid stays open until the write lands. */
    fun commitProjectRules(committed: RuleLists) {
        val editor = session.state()?.rules ?: return
        if (editor.saving) return
        session.update { copy(rules = rules?.copy(saving = true)) }
        vm.work {
            val body = buildJsonObject {
                normalizedBreakdown().forEach { (key, value) -> put(key, value) }
                committed.lists.forEach { (list, rows) -> put(list.wire, JsonArray(rows)) }
            }
            when (val result = vm.repository.writeProjectSection(ProjectSection.NonUnionPaybreakdown, body)) {
                is ZillitResult.Success -> {
                    session.update { copy(rules = null) }
                    result.data?.takeIf { it.isNotBlank() }?.let { vm.toast(it, DealToastTone.Success) }
                    vm.refreshProjectSettings()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    session.update { copy(rules = rules?.copy(saving = false)) }
                }
            }
        }
    }

    fun openRuleImport() {
        session.actions.references.loadCoveredTerritories()
        page { copy(ruleImport = RuleImportState()) }
    }

    private fun pickImportTerritory(territory: String) {
        importListingJob?.cancel()
        importAgreementJob?.cancel()
        page {
            copy(
                ruleImport = ruleImport?.copy(
                    territory = territory,
                    agreementId = "",
                    agreements = emptyList(),
                    agreementsLoading = territory.isNotEmpty(),
                    agreement = null,
                    agreementLoading = false,
                ),
            )
        }
        if (territory.isEmpty()) return
        importListingJob = vm.work {
            val listing = session.actions.references.listing(territory)
            page {
                val current = ruleImport
                if (current?.territory == territory) {
                    copy(ruleImport = current.copy(agreements = listing.agreements, agreementsLoading = false))
                } else {
                    this
                }
            }
        }
    }

    private fun pickImportAgreement(agreementId: String) {
        importAgreementJob?.cancel()
        page {
            copy(
                ruleImport = ruleImport?.copy(
                    agreementId = agreementId,
                    agreement = null,
                    agreementLoading = agreementId.isNotEmpty(),
                ),
            )
        }
        if (agreementId.isEmpty()) return
        importAgreementJob = vm.work {
            val doc = session.actions.references.agreementDocument(agreementId)
            page {
                val current = ruleImport
                if (current?.agreementId == agreementId) {
                    copy(ruleImport = current.copy(agreement = doc, agreementLoading = false))
                } else {
                    this
                }
            }
        }
    }

    /** The agreement's rules projected and appended; untouched blank rows make room. */
    private fun confirmRuleImport() {
        val state = session.state() ?: return
        val doc = state.setupPage.ruleImport?.agreement ?: return
        val projected = AgreementRuleImport.project(doc, ::importId)
        if (AgreementRuleImport.count(projected) == 0) return
        val imported = BulkRules.rows(projected, vm.newId)
        session.update {
            copy(
                rules = rules?.copy(
                    rows = rules.rows.filterNot { it.pristine } + imported,
                    importNote = imported.size to 0,
                ),
                setupPage = setupPage.copy(ruleImport = null),
            )
        }
    }

    private fun importId(list: RuleList): String {
        importSequence += 1
        val stamp = vm.clock().toString(RADIX)
        return "nu-imp-$stamp-${list.wire}-$importSequence-${randomSuffix(IMPORT_SUFFIX)}"
    }

    /** `normalizeNonUnionValue` of the project's slice, as the write's base. */
    private fun normalizedBreakdown(): Map<String, JsonElement> {
        val raw = projectBreakdown()
        val lists = NonUnionPayBreakdown.lists(raw, vm.newId)
        val source = raw as? JsonObject
        return buildMap {
            listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Penalties).forEach { list ->
                put(list.wire, JsonArray(lists.lists[list].orEmpty()))
            }
            put("apply_mode", source?.get("apply_mode") ?: JsonNull)
            put("department_ids", source?.get("department_ids") as? JsonArray ?: JsonArray(emptyList()))
        }
    }

    private fun projectBreakdown(): JsonElement? = NonUnionPayBreakdown.section(vm.ui.projectSettings.view.json)

    // -- day types -------------------------------------------------------------------------------

    private fun addDayType() {
        val draft = session.state()?.setupPage?.dayTypes ?: return
        if (draft.rows.size >= DayTypeRows.MAX_ROWS) {
            vm.toast(DayTypeRows.TOO_MANY, DealToastTone.Error)
            return
        }
        dayTypes { copy(rows = rows + DayTypeRows.blank(rowId())) }
    }

    private fun saveDayTypes() {
        val draft = session.state()?.setupPage?.dayTypes ?: return
        if (draft.saving) return
        DayTypeRows.validate(draft.rows)?.let { problem ->
            vm.toast(problem, DealToastTone.Error)
            return
        }
        val body = DayTypeRows.wire(draft.rows)
        dayTypes { copy(saving = true) }
        vm.work {
            when (val result = vm.repository.writeProjectSection(ProjectSection.DayTypes, body)) {
                is ZillitResult.Success -> {
                    result.data?.takeIf { it.isNotBlank() }?.let { vm.toast(it, DealToastTone.Success) }
                    val saved = DayTypeRows.rows(body.mapNotNull { it as? JsonObject }, ::rowId)
                    dayTypes { DayTypesDraft(rows = saved, baseline = saved) }
                    vm.refreshProjectSettings()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    dayTypes { copy(saving = false) }
                }
            }
        }
    }

    // -- agreement documents ---------------------------------------------------------------------

    /** PDFs only, 20 MB at most — each refused file named in its own toast. */
    private fun queueDocuments() {
        val store = vm.store ?: return
        if (session.state()?.setupPage?.documentsBusy == true) return
        vm.work {
            val picked = store.pickFiles(listOf("pdf"), multiple = true)
            val rows = picked.mapIndexedNotNull { index, file ->
                val pdf = file.name.lowercase().endsWith(".pdf") && (file.mime.isEmpty() || file.mime == PDF_MIME)
                when {
                    !pdf -> null.also { vm.toast(str(S.desktop_dm_file_pdf_only, file.name), DealToastTone.Error) }
                    file.bytes.size > BuilderDocuments.MAX_CUSTOM_BYTES -> null.also {
                        vm.toast(str(S.desktop_dm_file_over_20_mb, file.name), DealToastTone.Error)
                    }
                    else -> PendingDocument(
                        id = "pending-${vm.clock()}-$index",
                        file = file,
                        title = file.name.substringBeforeLast('.').takeIf { file.name.lastIndexOf('.') > 0 }
                            ?: file.name,
                        description = "",
                    )
                }
            }
            if (rows.isNotEmpty()) page { copy(pendingDocuments = pendingDocuments + rows) }
        }
    }

    /** What the toast says once the queue drains: how many landed, and how many did not. */
    private fun uploadSummary(count: Int, failed: Int): String = when {
        failed > 0 -> str(S.desktop_dm_uploaded_count_failed, count, failed)
        count > 1 -> str(S.desktop_dm_uploaded_n_documents, count)
        else -> str(S.desktop_dm_uploaded_one_document)
    }

    /** Each file uploaded in turn, then one add for all that made it; a failed add keeps the queue. */
    private fun saveDocuments() {
        val store = vm.store ?: return
        val state = session.state() ?: return
        val pending = state.setupPage.pendingDocuments
        if (pending.isEmpty() || state.setupPage.documentsBusy) return
        page { copy(documentsBusy = true) }
        vm.work {
            val attachments = mutableListOf<JsonObject>()
            var failed = 0
            pending.forEach { row ->
                when (val uploaded = store.upload(row.file.name, row.file.mime.ifEmpty { PDF_MIME }, row.file.bytes)) {
                    is ZillitResult.Success -> attachments += buildJsonObject {
                        put("media", uploaded.data.media)
                        put("bucket", uploaded.data.bucket)
                        put("region", uploaded.data.region)
                        put("name", row.file.name)
                        put("content_type", "document")
                        put("content_subtype", "pdf")
                        put("caption", row.description.trim())
                        put("title", row.title.trim().ifEmpty { row.file.name })
                        put("description", row.description.trim())
                        put("file_size", row.file.bytes.size)
                    }
                    is ZillitResult.Failure -> {
                        failed += 1
                        vm.toast(str(S.desktop_dm_failed_upload_error, row.file.name), DealToastTone.Error)
                    }
                }
            }
            val body = JsonArray(attachments)
            val added = attachments.isEmpty() ||
                when (val result = vm.repository.writeProjectSection(ProjectSection.AgreementsDocuments, body)) {
                    is ZillitResult.Success -> {
                        vm.reloadProjectSettings()
                        val count = attachments.size
                        vm.toast(
                            uploadSummary(count, failed),
                            DealToastTone.Success,
                        )
                        true
                    }
                    is ZillitResult.Failure -> {
                        vm.toastError(result.error, "document_upload_failed")
                        false
                    }
                }
            page { copy(documentsBusy = false, pendingDocuments = if (added) emptyList() else pendingDocuments) }
        }
    }

    /** No update endpoint: the edited copy is added first, then the original deleted — a failed add loses nothing. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Each guard is one of the web's bail-outs.
    private fun commitDocument(id: String) {
        val state = session.state() ?: return
        val edit = state.setupPage.documentEdits[id] ?: return
        if (state.setupPage.documentsBusy) return
        val row = vm.ui.projectSettings.view.agreementDocuments.firstOrNull { BuilderDocuments.agreementId(it) == id }
            ?: return
        val flat = BuilderDocuments.flatten(row)
        val name = flat["name"]?.takeIf(Js::truthy)?.let(Js::text)
        val currentTitle =
            (row["title"] ?: (row["document"] as? JsonObject)?.get("title"))?.takeIf(Js::truthy)?.let(Js::text)
            ?: name.orEmpty()
        val currentDescription = (row["description"] ?: (row["document"] as? JsonObject)?.get("description"))
            ?.takeIf(Js::truthy)?.let(Js::text).orEmpty()
        if (edit.title.trim() == currentTitle && edit.description.trim() == currentDescription) return
        page { copy(documentsBusy = true) }
        vm.work {
            val edited = buildJsonObject {
                listOf(
                    "name",
                    "media",
                    "bucket",
                    "region",
                    "content_type",
                    "content_subtype",
                    "file_size",
                ).forEach { key ->
                    flat[key]?.let { put(key, it) }
                }
                put("caption", edit.description.trim())
                put("title", edit.title.trim().ifEmpty { name ?: str(S.document) })
                put("description", edit.description.trim())
            }
            val added =
                vm.repository.writeProjectSection(ProjectSection.AgreementsDocuments, buildJsonArray { add(edited) })
            val removed = if (added is ZillitResult.Success) vm.repository.deleteAgreementDocument(id) else added
            when (removed) {
                is ZillitResult.Success -> {
                    vm.reloadProjectSettings()
                    page { copy(documentEdits = documentEdits - id, documentsBusy = false) }
                }
                is ZillitResult.Failure -> {
                    vm.toastError(removed.error, "document_update_failed")
                    page { copy(documentsBusy = false) }
                }
            }
        }
    }

    private fun deleteDocument(id: String) {
        if (session.state()?.setupPage?.documentsBusy == true) return
        page { copy(documentsBusy = true) }
        vm.work {
            when (val result = vm.repository.deleteAgreementDocument(id)) {
                is ZillitResult.Success -> vm.reloadProjectSettings()
                is ZillitResult.Failure -> vm.toastError(result.error, "document_delete_failed")
            }
            page { copy(documentsBusy = false, documentEdits = documentEdits - id) }
        }
    }

    fun reset() {
        importListingJob?.cancel()
        importAgreementJob?.cancel()
    }

    // -- helpers ---------------------------------------------------------------------------------

    private fun page(reducer: SetupPageState.() -> SetupPageState) = session.update {
        copy(setupPage = setupPage.reducer())
    }

    private fun dayTypes(reducer: DayTypesDraft.() -> DayTypesDraft) = page { copy(dayTypes = dayTypes?.reducer()) }

    private fun rowId(): String = "dt-${vm.clock()}-${randomSuffix(ROW_SUFFIX)}"

    private fun randomSuffix(length: Int): String =
        (1..length).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    private companion object {
        const val PDF_MIME = "application/pdf"
        const val RADIX = 36
        const val ID_RANGE = 1_000_000
        const val IMPORT_SUFFIX = 4
        const val ROW_SUFFIX = 6
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}

/** A PDF's size as the queue prints it: `2.4 MB`. */
internal fun megabytes(bytes: Int): String = "${Js.toFixed(bytes / (BYTES_PER_KB * BYTES_PER_KB), 1)} MB"

private const val BYTES_PER_KB = 1024.0
