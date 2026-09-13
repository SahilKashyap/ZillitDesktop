package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The pages a socket frame asks to reload — the web's `ah:deal_memo:*` keys. */
enum class DealRefreshKey {
    /** All Deals, Overview and Notices — `ah:deal_memo:list`. */
    Registry,

    /** `ah:deal_memo:approval`. */
    Approval,

    /** `ah:deal_memo:my`. */
    Mine,

    /** The open deal — `ah:deal_memo:detail:<id>`. */
    Detail,

    /** The setups list — `ah:deal_memo:template:list`. */
    Templates,
}

data class DealRefresh(val keys: Set<DealRefreshKey>, val dealId: String? = null)

/**
 * Every `/api/v2/deal-memo` route the tool calls, plus the notice template the
 * account-hub service keeps.
 *
 * Writes answer the server's `message` (already a translation key or a
 * sentence) so the screen can toast what the web toasts; a refusal — HTTP or
 * `status: 0` — is a failure.
 */
@Suppress("TooManyFunctions") // One suspend fun per server operation.
interface DealMemoRepository {

    /** Deal announcements from other clients, keyed by the pages they touch. */
    val refreshes: Flow<DealRefresh> get() = emptyFlow()

    // -- reads ---------------------------------------------------------------

    /** `GET /deals` — the whole project, slim rows. */
    suspend fun deals(): ZillitResult<List<DealDoc>>

    suspend fun overview(): ZillitResult<DealOverview>

    /** Deals at this approver's level — a flat list. */
    suspend fun approvalQueue(): ZillitResult<List<DealDoc>>

    /** Approver flags and tier configs. A 200 without `data` is a failure. */
    suspend fun metadata(): ZillitResult<DealMemoMetadata>

    /** The viewer's own deal, or null when none is on file. */
    suspend fun myDeal(): ZillitResult<DealDoc?>

    suspend fun deal(id: String): ZillitResult<DealDoc>

    suspend fun history(id: String): ZillitResult<List<DealHistoryEntry>>

    suspend fun templates(): ZillitResult<List<DealTemplateSummary>>

    /** One setup's stored payload — `template_data`, an object or a JSON string. */
    suspend fun template(id: String): ZillitResult<DealTemplate>

    // -- list actions ----------------------------------------------------------

    suspend fun activate(id: String): ZillitResult<String?>

    /** Nudge the crew member on an issued deal; no body, no status change. */
    suspend fun chase(id: String): ZillitResult<String?>

    suspend fun delete(id: String): ZillitResult<String?>

    /** [lastPayDay] is noon UTC of the chosen day; [content] is the final letter. */
    suspend fun sendNotice(id: String, lastPayDay: Long, content: String): ZillitResult<String?>

    /** [lastPayDate] is UTC midnight of the chosen day. */
    suspend fun deactivate(id: String, lastPayDate: Long): ZillitResult<String?>

    /** The register (PDF/XLSX) or the start-forms ZIP, as bytes. */
    suspend fun export(kind: DealExport): ZillitResult<ByteArray>

    // -- notice template (account hub) ----------------------------------------

    /** The stored template, or null when the production never saved one. */
    suspend fun noticeTemplate(): ZillitResult<String?>

    suspend fun saveNoticeTemplate(value: String): ZillitResult<String?>

    /** Production Setup's saved settings (`GET /account-hub/project-settings` → `data.settings`). */
    suspend fun projectSettings(): ZillitResult<JsonObject>

    // -- the deal page -----------------------------------------------------------

    /** `GET /deals/:id/portal-link` — the token of an external crew member's share link. */
    suspend fun portalLink(id: String): ZillitResult<String?>

    /** `POST /deals/:id/approve` with `{}`; the server decides when the deal activates. */
    suspend fun approve(id: String): ZillitResult<String?>

    /** `POST /deal/reject-crew {reason}` — the caller's own deal, resolved by the server. */
    suspend fun rejectAsCrew(reason: String): ZillitResult<String?>

    /** `POST /deals/:id/send-for-approval`, no body. */
    suspend fun sendForApproval(id: String): ZillitResult<String?>

    /** `POST /deal/acknowledge-amendment {}` — the caller's own deal. */
    suspend fun acknowledgeAmendment(): ZillitResult<String?>

    /** `PATCH /deal/crew-details {crew_details, bank?}` — answers the saved deal when the server echoes it. */
    suspend fun saveCrewDetails(body: JsonObject): ZillitResult<DealWrite>

    /** `PATCH /deals/:id/deal-rules` — amends a live deal's pay rules in place. */
    suspend fun updateDealRules(id: String, body: JsonObject): ZillitResult<String?>

    /** `PATCH /deals/:id/nominal-codes` — re-codes the deal's pay lines. */
    suspend fun updateNominalCodes(id: String, body: JsonObject): ZillitResult<String?>

    /**
     * `POST /deals/:id/pdf` (or the start form's) with the display context —
     * a render for display that is never stored on the deal.
     */
    suspend fun generatePdf(id: String, kind: DealPdfKind, context: JsonObject): ZillitResult<JsonObject>

    /** One POST that stores the signed copy and appends the signer row. */
    suspend fun sign(id: String, target: DealSignTarget, attachment: JsonObject): ZillitResult<String?>

    // -- authoring: the builder and its setups ---------------------------------------------

    /** `POST /deals?notify=` — the body carries the client-minted `_id` and `status: "draft"`. */
    suspend fun createDeal(body: JsonObject, notify: Boolean): ZillitResult<SavedRecord>

    /** `PATCH /deals/:id?notify=` — never with `status`; on a live deal the server rewinds it to issued. */
    suspend fun updateDeal(id: String, body: JsonObject, notify: Boolean): ZillitResult<SavedRecord>

    /** `POST /deals/:id/submit`, no body — Issue. */
    suspend fun submitDeal(id: String): ZillitResult<String?>

    /** `POST /templates` — `{...payload, name}`, with `_id` on a create. */
    suspend fun createTemplate(body: JsonObject): ZillitResult<SavedRecord>

    /** `PATCH /templates/:id`. */
    suspend fun updateTemplate(id: String, body: JsonObject): ZillitResult<SavedRecord>

    suspend fun deleteTemplate(id: String): ZillitResult<String?>

    /** A Deal Memo Setup section on the project, written the way Production Setup writes it. */
    suspend fun writeProjectSection(section: ProjectSection, body: JsonElement): ZillitResult<String?>

    /** `DELETE /account-hub/project-settings/agreements-documents/:id` — there is no update. */
    suspend fun deleteAgreementDocument(id: String): ZillitResult<String?>

    /** The Account Hub bank account a deal links to by `bank_acc_id`. */
    suspend fun bankAccount(id: String): ZillitResult<JsonObject>

    suspend fun updateBankAccount(id: String, bank: JsonObject): ZillitResult<String?>
}

/** A created or updated deal or setup: its server id and reference when echoed, and the message. */
data class SavedRecord(val id: String?, val reference: String?, val message: String?, val data: JsonObject? = null)

/** The project-settings sections the builder writes (`firstRunSetup.js`, Production Setup's writers). */
enum class ProjectSection(val path: String, val post: Boolean = false) {
    AllowancesRentals("allowances-rentals"),
    StandardDealConditions("standard-deal-conditions"),
    PayrollBureau("payroll-bureau"),
    ProductionSchedule("production-schedule", post = true),
    AgreementsDocuments("agreements-documents", post = true),
    NonUnionPaybreakdown("non-union-paybreakdown"),
    DayTypes("day-types"),
    Companies("companies"),
}

/** `isDuplicateDealId`: a 409, or the server's DUPLICATE_KEY — the other create path won. */
fun ZillitError.isDuplicateRecord(): Boolean =
    this is ZillitError.Http &&
        (status == HTTP_CONFLICT || serverMessage?.contains("DUPLICATE_KEY", ignoreCase = true) == true)

private const val HTTP_CONFLICT = 409

/** A write that may answer the saved document. */
data class DealWrite(val message: String?, val deal: DealDoc?)

/** The two documents the server renders. */
enum class DealPdfKind { DealMemo, StartForm }

/** Where a signed copy is posted. */
sealed interface DealSignTarget {
    data object DealMemo : DealSignTarget

    data object StartForm : DealSignTarget

    data class Document(val docId: String) : DealSignTarget
}

/** A setup's detail: its name and the deal payload it seeds. */
data class DealTemplate(
    val id: String,
    val name: String,
    val form: JsonObject?,
    val createdBy: String? = null,
    val createdAt: Long? = null,
) {
    /** `isNonUnionId(territory_union.agreement_identifier ?? union)`; unreadable counts as Union. */
    val nonUnion: Boolean
        get() {
            val union = DocRead.obj(form, "territory_union")
            val agreement = union?.let { DocRead.text(it, "agreement_identifier") } ?: DocRead.text(form, "union")
            return isNonUnionId(agreement)
        }
}

/** `isNonUnionId`: trimmed, lower-cased, spaces and hyphens to underscores, compared to `non_union`. */
fun isNonUnionId(value: String?): Boolean =
    value?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_") == "non_union"
