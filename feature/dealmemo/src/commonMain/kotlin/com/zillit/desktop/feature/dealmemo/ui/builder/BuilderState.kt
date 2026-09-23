package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.AllowanceConversion
import com.zillit.desktop.feature.dealmemo.domain.authoring.BureauRow
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.CoveredDepartment
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRow
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.preview.FileViewer
import com.zillit.desktop.feature.dealmemo.ui.preview.RulesEditorState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Which page the one-page builder is (`DMTemplateBuilderPage.jsx` modes A–E, G).
 *
 * A deal page starts from a Create menu group ([dealGroup]), a setup's Use
 * ([useTemplateId]) or a saved deal ([dealId]); a setup page creates one
 * (optionally locked to [lockedGroup], or [embedded] in the hub's empty tab) or
 * edits [templateId].
 */
data class BuilderMode(
    val deal: Boolean,
    val dealGroup: SetupGroup? = null,
    val useTemplateId: String? = null,
    val dealId: String? = null,
    val templateId: String? = null,
    val lockedGroup: SetupGroup? = null,
    val embedded: Boolean = false,
    /** Where a saved setup goes — the web's `exitTo` prop. */
    val exitTo: DealMemoRoute? = null,
    /** The page Back returns to — the web's history entry before this one. */
    val backTo: DealMemoRoute? = null,
) {
    val setup: Boolean get() = !deal

    /** The record this visit was opened on — never deleted by Leave Without Saving. */
    val preOwnedId: String? get() = if (deal) dealId else templateId

    /** Where a setup's union status can't be flipped: a locked create, or any edit once loaded. */
    fun unionLocked(editLoading: Boolean): Boolean = lockedGroup != null || (templateId != null && !editLoading)
}

/** One section of the memo card: its stable id, label, and how the page treats it. */
data class BuilderSection(
    val id: Int,
    val name: String,
    val collapsible: Boolean = false,
    val noEdit: Boolean = false,
    val note: String? = null,
    /** The band drawn above the group's first section: `nonunion` or `global`. */
    val group: String? = null,
) {
    companion object {
        const val NON_UNION_RULES = 13
        const val SCHEDULE = 5
        const val ALLOWANCES = 7
        const val CONDITIONS = 8
        const val PAYROLL = 11

        /** `DEAL_SECTIONS`, render order — Crew Personal Details sits after Allowances. */
        fun dealSections(accountant: Boolean): List<BuilderSection> = listOfNotNull(
            BuilderSection(DealValidators.TERRITORY, str(S.dm_quick_sec_territory), collapsible = true, noEdit = true),
            BuilderSection(DealValidators.CREW, str(S.dm_crew_step_crew)),
            BuilderSection(DealValidators.EMPLOYMENT, str(S.dm_quick_sec_employment)),
            BuilderSection(DealValidators.DEAL, str(S.dm_ds_title)),
            BuilderSection(DealValidators.RATES, str(S.dm_rates_title)),
            BuilderSection(DealValidators.ALLOWANCES, str(S.dm_allow_title)),
            BuilderSection(
                DealValidators.PERSONAL,
                str(S.dm_quick_sec_personal),
                note = str(S.dm_quick_personal_note),
            ),
            BuilderSection(CONDITIONS, str(S.dm_cond_title)),
            BuilderSection(DealValidators.NOMINAL, str(S.dm_nom_title)).takeIf { accountant },
            BuilderSection(PAYROLL, str(S.dm_pay_title)).takeIf { accountant },
        )

        /** A setup page: Territory & Union, the non-union rules while non-union, then the Global sections. */
        fun setupSections(union: String): List<BuilderSection> = buildList {
            add(BuilderSection(DealValidators.TERRITORY, str(S.dm_quick_sec_territory)))
            if (isNonUnionId(union)) add(
                BuilderSection(NON_UNION_RULES, str(S.dm_builder_band_nonunion), group = "nonunion"),
            )
            add(BuilderSection(SCHEDULE, str(S.desktop_production_schedule), group = "global"))
            add(BuilderSection(ALLOWANCES, str(S.dm_allow_title), group = "global"))
            add(BuilderSection(CONDITIONS, str(S.dm_cond_title), group = "global"))
            add(BuilderSection(PAYROLL, str(S.desktop_payroll_bureau), group = "global"))
        }
    }
}

/** The autosave readout: `Saving…`, `Unsaved changes`, `Not saved — will retry`, `Saved 14:05`. */
enum class AutosaveStatus { Idle, Pending, Saving, Saved, Error }

data class AutosaveUi(val status: AutosaveStatus = AutosaveStatus.Idle, val savedAt: Long? = null)

/** A popup naming what is missing — the shared validation modal. */
data class BuilderValidation(val title: String, val message: String, val fields: List<String>)

enum class NameIntent { Save, Issue }

/** "Name this Deal Memo", before a save or an issue of a nameless deal. */
data class NameCapture(val intent: NameIntent, val name: String = "")

/** A section to bring into view once the layout settles; [nonce] repeats a request for the same section. */
data class ScrollRequest(val sectionId: Int, val nonce: Long)

/**
 * The agreement and rate-card data the page reads for the form as it stands
 * (`useWizardData`, `useTerritoryEmpStatuses`, covered roles and territories).
 */
data class BuilderReference(
    /** The territory [agreements] were read for. */
    val territory: String = "",
    val agreements: List<JsonObject> = emptyList(),
    val territoryEmpStatuses: JsonArray = EMPTY,
    val agreementsLoading: Boolean = false,
    /** The agreement [agreement] is, or is loading for. */
    val agreementId: String = "",
    val agreement: JsonObject? = null,
    val agreementLoading: Boolean = false,
    val rateKey: String? = null,
    val resolvedRates: List<JsonObject> = emptyList(),
    val rateLoading: Boolean = false,
    /** A territory-less deal's entity country, for its employment statuses. */
    val fallbackTerritory: String = "",
    val fallbackEmpStatuses: JsonArray = EMPTY,
    val coveredRolesKey: String? = null,
    val coveredRoles: List<CoveredDepartment> = emptyList(),
    /** Territories with a branch; null until known, and the picker then lists every territory. */
    val coveredTerritories: Set<String>? = null,
) {
    /** The agreement with the territory's employment statuses merged in when it has none of its own. */
    val selectedUnion: JsonObject?
        get() {
            val doc = agreement ?: return null
            val own = doc["emp_statuses"] as? JsonArray
            return if (own != null && own.isNotEmpty()) {
                doc
            } else {
                JsonObject(doc + ("emp_statuses" to territoryEmpStatuses))
            }
        }

    /** `selectedUnionForSteps`: a non-union deal with no territory borrows its entity country's statuses. */
    val selectedUnionForSteps: JsonObject?
        get() {
            val union = selectedUnion ?: return null
            if ((union["emp_statuses"] as? JsonArray)?.isNotEmpty() == true) return union
            if (fallbackEmpStatuses.isEmpty()) return union
            return JsonObject(union + ("emp_statuses" to fallbackEmpStatuses))
        }

    val resolvedRate: JsonObject? get() = resolvedRates.firstOrNull()

    private companion object {
        val EMPTY = JsonArray(emptyList())
    }
}

/** Which rule book the full-page grid writes: this deal's own rules, or the project's non-union pay rules. */
enum class RulesTarget { Deal, Project }

/** A PDF picked for Agreement Documents, waiting for Save all. */
data class PendingDocument(val id: String, val file: PickedDealFile, val title: String, val description: String)

/** A saved agreement document's title and description as edited — committed when the field is left. */
data class DocumentEdit(val title: String, val description: String)

/** "Import rules from a union agreement": the territory, its agreements, and the one picked. */
data class RuleImportState(
    val territory: String = "",
    val agreementId: String = "",
    val agreements: List<AgreementSummary> = emptyList(),
    val agreementsLoading: Boolean = false,
    val agreement: JsonObject? = null,
    val agreementLoading: Boolean = false,
)

/** The project's Day Types as edited; Save Day Types shows while they differ from what was saved. */
data class DayTypesDraft(val rows: List<DayTypeRow>, val baseline: List<DayTypeRow>, val saving: Boolean = false) {
    val dirty: Boolean get() = rows != baseline
}

/** A setup page's direct edits to Production Setup — each saved on its own, never by Save Setup. */
data class SetupPageState(
    /** "Add your first company", before the form on a production with none. */
    val companyNotice: Boolean = false,
    val companyDraft: JsonObject? = null,
    val companySaving: Boolean = false,
    val ruleImport: RuleImportState? = null,
    val dayTypes: DayTypesDraft? = null,
    val documentEdits: Map<String, DocumentEdit> = emptyMap(),
    val pendingDocuments: List<PendingDocument> = emptyList(),
    val documentsBusy: Boolean = false,
)

/**
 * The one-page builder — a deal memo or a Deal Memo Setup, one scroll of
 * sections, each read-only until its Edit opens it.
 */
data class BuilderState(
    /** The visit this state belongs to — work from a page already left never lands on the next. */
    val visit: Long,
    val mode: BuilderMode,
    val form: DealForm = DealForm.INITIAL,
    /** User edits so far; never reset — Update Setup stays once anything changed. */
    val dirtyTick: Int = 0,
    /** Something typed is not on the server yet — the leave guard's question. */
    val dirty: Boolean = false,
    val dealId: String? = null,
    val dealReference: String? = null,
    val dealStatus: String? = null,
    /** The setup the deal was drawn from; the picker can change it. */
    val pickedTemplateId: String? = null,
    /** The setup row this page writes to — the edited one, or the draft autosave created. */
    val autosavedTemplateId: String? = null,
    val templateName: String = "",
    val editLoading: Boolean = false,
    val editingSection: Int? = null,
    /** Collapsible sections the user opened (Territory & Union starts closed). */
    val expanded: Set<Int> = emptySet(),
    /** Sections Issue refused, with the fields each is missing, in walk order. */
    val issueErrors: Map<Int, List<String>> = emptyMap(),
    val showEntitlementErrors: Boolean = false,
    val validation: BuilderValidation? = null,
    /** The issue preview is up; true when nominal codes are still missing. */
    val issuePreview: Boolean? = null,
    val nominalPrompt: Boolean = false,
    val nameCapture: NameCapture? = null,
    /** The "Save {setup}" name dialog. */
    val setupNamePrompt: Boolean = false,
    val leaveGuard: Boolean = false,
    val saving: Boolean = false,
    val submitting: Boolean = false,
    val savingTemplate: Boolean = false,
    val autosave: AutosaveUi = AutosaveUi(),
    val setupBureaus: List<BureauRow> = listOf(BureauRow("bureau-1")),
    /** The edited deal's own custom-day defaults, which Reset restores instead of the project's. */
    val dealDefaultCustomDays: List<JsonElement>? = null,
    /** The rule fields as last loaded or saved — Rates' Reset restores them. */
    val savedRules: Map<String, JsonElement> = emptyMap(),
    val conversion: AllowanceConversion? = null,
    /** Crew who already hold a live deal — the Crew Member picker leaves them out. */
    val takenUserIds: Set<String> = emptySet(),
    val reference: BuilderReference = BuilderReference(),
    val scroll: ScrollRequest? = null,
    /** A document open over the page. */
    val viewer: FileViewer? = null,
    /** The long-form contract is on its way to storage. */
    val contractUploading: Boolean = false,
    val passportUploading: Boolean = false,
    /** A rule book open in the full-page grid, and which one it is. */
    val rules: RulesEditorState? = null,
    val rulesTarget: RulesTarget = RulesTarget.Deal,
    /** "Reset pay rules" is asking. */
    val confirmResetRules: Boolean = false,
    val setupPage: SetupPageState = SetupPageState(),
) {
    /** The memo card's sections — Nominal Coding and Payroll Start Form are an accountant's. */
    fun sections(accountant: Boolean): List<BuilderSection> =
        if (mode.deal) BuilderSection.dealSections(accountant) else BuilderSection.setupSections(form.text("union"))

    /** The Crew Name the deal saves under: the legal name, else an external crew member's typed name. */
    val crewName: String
        get() {
            val legal = form.text("fullLegalName").trim()
            if (legal.isNotEmpty()) return legal
            return if (form.flag("isExternal")) form.text("crewName").trim() else ""
        }

    val busy: Boolean get() = saving || submitting || savingTemplate

    fun rule(key: String): JsonElement = savedRules[key] ?: JsonNull
}
