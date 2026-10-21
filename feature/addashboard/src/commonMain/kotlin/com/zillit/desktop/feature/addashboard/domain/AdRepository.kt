package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The AD department's side of supporting artistes.
 *
 * Scoped to the open production by the usual project/user module data. The
 * shoot day is addressed by **date** rather than by id on the `today` routes,
 * because the AD works in days, not records — and every date sent is UTC
 * midnight ([AdDates]). Letting the server resolve "today" from the request's
 * timezone header gives a different epoch: the lookup misses and a second day
 * header gets created for the same calendar day.
 */
@Suppress("TooManyFunctions") // One call per endpoint; the service has sixty.
interface AdRepository {

    // -- the register ---------------------------------------------------------

    suspend fun artistes(): ZillitResult<List<Artiste>>

    suspend fun artiste(artisteId: String): ZillitResult<Artiste>

    suspend fun createArtiste(draft: ArtisteDraft): ZillitResult<String>

    suspend fun updateArtiste(artisteId: String, draft: ArtisteDraft): ZillitResult<Unit>

    suspend fun verify(artisteId: String): ZillitResult<Unit>

    suspend fun block(artisteId: String, reason: String?): ZillitResult<Unit>

    suspend fun unblock(artisteId: String): ZillitResult<Unit>

    /** Refused by the server for a verified artiste; see [Artiste.deletable]. */
    suspend fun deleteArtiste(artisteId: String): ZillitResult<Unit>

    /**
     * Mints the external artiste's portal token.
     *
     * The app never e-mails it — the AD copies the link. Returns the token
     * itself, which the caller turns into `<origin>/extras-portal/<token>`.
     */
    suspend fun portalLink(artisteId: String): ZillitResult<String>

    // -- the agency-list import ----------------------------------------------

    /**
     * Uploads a spreadsheet and asks the server to read it.
     *
     * The parsing, and the match from each row's agency name to a registered
     * vendor, are the server's work: the client only sends the file and
     * re-checks names and e-mail addresses.
     */
    suspend fun importPreview(fileName: String, bytes: ByteArray): ZillitResult<ImportPreview>

    suspend fun importCommit(rows: List<ImportRow>): ZillitResult<Int>

    /** As [importCommit], and onto the day's list as well. */
    suspend fun importCommitToday(rows: List<ImportRow>, shootDate: Long): ZillitResult<Int>

    // -- the day --------------------------------------------------------------

    /**
     * The shoot day header for [shootDate].
     *
     * Answers `null` until the day has one: the server creates it when the
     * first extra is added to a date.
     */
    suspend fun today(shootDate: Long): ZillitResult<AdShootDay?>

    suspend fun shootDays(): ZillitResult<List<AdShootDay>>

    suspend fun dayNumber(shootDate: Long): ZillitResult<Int?>

    /** Everyone on that day's list. */
    suspend fun dayList(shootDate: Long): ZillitResult<List<SupportingArtistDay>>

    /** One day row, with its pay lines — the voucher. */
    suspend fun dayEntry(id: String): ZillitResult<SupportingArtistDay>

    /** Books existing artistes onto a day; anyone already on it is skipped. */
    suspend fun addToDay(
        artisteIds: List<String>,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit>

    /** Creates an external artiste and books them onto a day in one call. */
    suspend fun createArtisteWithDay(
        draft: ArtisteDraft,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit>

    /**
     * Patches one artiste-day.
     *
     * Only the fields given are sent: a PATCH carrying every field would clear
     * a wrap time somebody else entered between this screen loading and the
     * save. [DayEntryPatch] exists so a caller cannot accidentally send one.
     */
    suspend fun updateDayEntry(id: String, patch: DayEntryPatch): ZillitResult<Unit>

    /**
     * The same patch applied to many rows.
     *
     * Answers the ids that **failed**, so the caller reverts only those rows:
     * the web's `updateMany` never rejects as a whole.
     */
    suspend fun updateDayEntries(ids: List<String>, patch: DayEntryPatch): ZillitResult<List<String>>

    suspend fun removeFromDay(id: String): ZillitResult<Unit>

    /** Saves the day's meal breaks, which the server copies onto every unsubmitted row. */
    suspend fun saveDayMeals(shootDate: Long, meals: List<MealBreak>): ZillitResult<Unit>

    /** Appends a purchase order to the day's saved list. */
    suspend fun appendDayPurchaseOrders(
        shootDate: Long,
        purchaseOrders: List<DayPurchaseOrder>,
    ): ZillitResult<Unit>

    /** Writes a report's captured day fields onto the day header. */
    suspend fun patchDay(shootDate: Long, patch: DayPatch): ZillitResult<Unit>

    /**
     * Sends the day to the production, after which it is locked.
     *
     * Creates no purchase orders: they are raised first, with
     * [createPurchaseOrder], and the saved records passed in here.
     */
    suspend fun submitDay(request: SubmitDayRequest): ZillitResult<Unit>

    /** Re-sends one submitted day's approval e-mail. */
    suspend fun sendApprovalLink(id: String): ZillitResult<Unit>

    /** Renders the day's list server-side as a file. */
    suspend fun exportDayList(
        format: ExportFormat,
        rows: List<SupportingArtistDay>,
        shootDate: Long,
        dayNumber: Int?,
    ): ZillitResult<DownloadedFile>

    suspend fun poQueue(): ZillitResult<PoQueue>

    // -- rates, agencies and queries -----------------------------------------

    suspend fun rateConfig(): ZillitResult<RateConfig>

    suspend fun saveRateConfig(
        nonUnion: NonUnionRates,
        union: List<UnionPayBreakdown>,
    ): ZillitResult<Unit>

    /** Saved union agreements as `[{ id, name }]`, for the artiste form's picker. */
    suspend fun unionList(): ZillitResult<List<UnionAgreementRef>>

    /** One saved agreement's stored snapshot. */
    suspend fun savedUnion(agreementId: String): ZillitResult<UnionPayBreakdown?>

    /** The live deal-memo agreement, for one not yet saved — or a **Refresh**. */
    suspend fun catalogueAgreement(agreementId: String): ZillitResult<UnionAgreementDocument?>

    /** The deal-memo agreements catalogue for a territory. */
    suspend fun agreementCatalogue(territory: String): ZillitResult<List<UnionAgreementRef>>

    suspend fun deleteUnion(agreementId: String): ZillitResult<Unit>

    /** The project's agencies, as the artiste forms' picker reads them. */
    suspend fun agencyVendors(): ZillitResult<List<Agency>>

    /** Registers an agency; the server forces its type to `agency`. */
    suspend fun createAgencyVendor(draft: AgencyDraft): ZillitResult<Unit>

    suspend fun deleteAgencyVendor(id: String): ZillitResult<Unit>

    /** The Agency tab's own list, which is a different endpoint from the picker's. */
    suspend fun agencies(): ZillitResult<List<Agency>>

    suspend fun createAgency(draft: AgencyDraft): ZillitResult<Unit>

    suspend fun updateAgency(id: String, draft: AgencyDraft): ZillitResult<Unit>

    suspend fun deleteAgency(id: String): ZillitResult<Unit>

    suspend fun verifyAgency(id: String): ZillitResult<Unit>

    suspend fun agencyHistory(id: String): ZillitResult<List<AgencyHistoryEntry>>

    suspend fun queries(): ZillitResult<List<ArtisteQuery>>

    suspend fun query(id: String): ZillitResult<ArtisteQuery>

    suspend fun replyToQuery(id: String, text: String): ZillitResult<Unit>

    suspend fun resolveQuery(id: String): ZillitResult<Unit>

    /** The nominal codes the rate editor's pickers offer. */
    suspend fun chartOfAccounts(): ZillitResult<List<NominalCode>>

    // -- reports --------------------------------------------------------------

    suspend fun reports(kind: ReportKind): ZillitResult<List<ReportSummary>>

    suspend fun report(kind: ReportKind, id: String): ZillitResult<AdReport>

    /** The day's report of that kind, or null. */
    suspend fun reportByDay(kind: ReportKind, shootDate: Long): ZillitResult<AdReport?>

    /**
     * Creates the day's report.
     *
     * Idempotent per project, day and kind: without [asCopy] the server
     * returns the day's existing report flagged [AdReport.existed], which the
     * caller must treat as a refusal rather than adopting it.
     */
    suspend fun createReport(
        kind: ReportKind,
        draft: ReportDraft,
        asCopy: Boolean = false,
        notify: Boolean = true,
    ): ZillitResult<AdReport>

    suspend fun saveReport(
        kind: ReportKind,
        id: String,
        draft: ReportDraft,
        notify: Boolean = true,
    ): ZillitResult<Unit>

    /**
     * Publishes: freeze, render the PDF, e-mail the distribution list, mark the
     * day published.
     *
     * Its one structured refusal is `ad_report_signatures_pending`, whose data
     * holds the rows still owing a signature.
     */
    suspend fun postReport(kind: ReportKind, id: String, draft: ReportDraft): ZillitResult<PostOutcome>

    suspend fun deleteReport(kind: ReportKind, id: String): ZillitResult<Unit>

    suspend fun saveReportAsTemplate(kind: ReportKind, id: String, name: String): ZillitResult<Unit>

    /** The published PDF, rendered from the frozen snapshot. */
    suspend fun reportPdf(kind: ReportKind, id: String): ZillitResult<DownloadedFile>

    /**
     * Records one Exhibit G signature, upserted on its cell and row.
     *
     * The image is uploaded first and sent as an attachment, never inline:
     * only the public portal sends a data URL.
     */
    suspend fun signReportRow(
        id: String,
        personId: String,
        cellId: String,
        rowId: String,
        signatureName: String,
        signatureBytes: ByteArray,
        inPerson: Boolean,
    ): ZillitResult<Unit>

    /** Voids every signature and bumps the round, which kills outstanding links. */
    suspend fun resetSignatures(id: String): ZillitResult<Unit>

    suspend fun templates(): ZillitResult<List<ReportTemplate>>

    suspend fun template(id: String): ZillitResult<ReportTemplate>

    suspend fun createTemplate(name: String, kind: ReportKind): ZillitResult<String>

    suspend fun saveTemplate(id: String, template: ReportTemplate): ZillitResult<Unit>

    suspend fun deleteTemplate(id: String): ZillitResult<Unit>

    suspend fun duplicateTemplate(id: String, name: String): ZillitResult<Unit>

    // -- owned by other areas -------------------------------------------------

    /**
     * One `PENDING` department purchase order for an agency group.
     *
     * Raised through the purchase-order service, not this one, and seeded with
     * the same auto-approvals the ordinary department PO flow uses. Answers the
     * record the day then stores.
     */
    suspend fun createPurchaseOrder(
        group: AgencyGroup,
        reference: String,
        suffix: Int,
        dayLabel: String,
        dateLabel: String,
        urgent: Boolean,
    ): ZillitResult<DayPurchaseOrder>

    /** The AD's saved signatures, from the e-signature service. */
    suspend fun savedSignatures(): ZillitResult<List<SavedSignature>>

    suspend fun saveSignature(name: String, bytes: ByteArray): ZillitResult<Unit>

    suspend fun deleteSavedSignature(id: String): ZillitResult<Unit>
}

/** An artiste as either form fills it in. */
@Suppress("LongParameterList")
data class ArtisteDraft(
    val isExternal: Boolean = false,
    val userId: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val dateOfBirth: String = "",
    val address: String = "",
    val category: ArtisteCategory = ArtisteCategory.GeneralSa,
    val engagement: EngagementType = EngagementType.DirectPaye,
    val union: String = "",
    val agencyName: String = "",
    val agencyId: String = "",
    val isMinor: Boolean = false,
    val refNumber: String = "",
    val bank: BankDetails = BankDetails(),
    val documents: List<ArtisteDocument> = emptyList(),
    /** `draft` from **Save draft**, `pending` from **Save & add to list**. */
    val status: ArtisteStatus = ArtisteStatus.Pending,
) {
    /**
     * On the AD's side only identity is required.
     *
     * A project user from the eligible list, or the external toggle with a
     * first and last name. The e-mail is only format-checked here — which is
     * why an external artiste saved with none has a dead portal link until
     * somebody adds one.
     */
    val identityComplete: Boolean
        get() = if (isExternal) {
            firstName.isNotBlank() && lastName.isNotBlank()
        } else {
            userId.isNotBlank()
        }

    val emailValid: Boolean get() = email.isBlank() || isValidEmail(email)

    /** Quick add and the import both require an e-mail; this form does not. */
    val readyForPortal: Boolean get() = isValidEmail(email)
}

/** One artiste-day patch: only the fields set are sent. */
data class DayEntryPatch(
    /**
     * The day the row belongs to, which per-artiste meals anchor on.
     *
     * A meal time anchored on today stamps a past day's edit onto today's
     * date, so the meal falls outside the shift and the pay engine reads no
     * break at all.
     */
    val shootDate: Long? = null,
    val callTime: Long? = null,
    val clearCallTime: Boolean = false,
    val wrapTime: Long? = null,
    val clearWrapTime: Boolean = false,
    val attendance: AttendanceStatus? = null,
    val clearAttendance: Boolean = false,
    val dayType: String? = null,
    val meals: List<MealBreak>? = null,
    val allowances: List<RateLine>? = null,
    val additionalFees: List<RateLine>? = null,
    val notes: String? = null,
) {
    val isEmpty: Boolean
        get() = callTime == null && !clearCallTime && wrapTime == null && !clearWrapTime &&
            attendance == null && !clearAttendance && dayType == null && meals == null &&
            allowances == null && additionalFees == null && notes == null
}

/** Everything `POST /ad-shoot-days/submit` carries. */
data class SubmitDayRequest(
    val shootDate: Long,
    val typedName: String,
    val signatureImage: String?,
    /** `typed` only for the type-your-name fallback; a library pick is `e-sign`. */
    val signStatus: String,
    val poReference: String,
    val poPostType: String,
    val purchaseOrders: List<DayPurchaseOrder>,
    val signedAt: Long,
)

/** An agency as the vendor form fills it in. */
data class AgencyDraft(
    val name: String = "",
    val email: String = "",
    val contactPerson: String = "",
    val phone: String = "",
    val address: String = "",
    val bank: BankDetails = BankDetails(),
) {
    val ready: Boolean get() = name.isNotBlank()
}

/** A report's payload, which every create, save and publish carries. */
data class ReportDraft(
    val id: String? = null,
    val name: String = "",
    val shootDate: Long? = null,
    val templateId: String? = null,
    val pageRows: List<ReportPageRow> = emptyList(),
    val fills: Map<String, String> = emptyMap(),
    val header: Map<String, String> = emptyMap(),
    val dayPatch: DayPatch = DayPatch(),
)

/** How a publish ended. */
sealed interface PostOutcome {
    data object Published : PostOutcome

    /** The server refused while cast rows are unsigned, and named them. */
    data class SignaturesPending(val rows: List<SigningRow>) : PostOutcome
}

/** What a list export may be rendered as. */
enum class ExportFormat(val wire: String, val extension: String) {
    Pdf("pdf", "pdf"),
    Excel("excel", "xlsx"),
    Csv("csv", "csv"),
}

/** A file the server rendered, named as it asked to be. */
data class DownloadedFile(val fileName: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is DownloadedFile && fileName == other.fileName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = fileName.hashCode() * 31 + bytes.contentHashCode()
}

/** One nominal code from the project's chart of accounts. */
data class NominalCode(
    val id: String = "",
    val code: String = "",
    val name: String = "",
) {
    val display: String get() = listOf(code, name).filter { it.isNotBlank() }.joinToString(" · ")
}

/** One of the AD's saved signatures. */
data class SavedSignature(
    val id: String,
    val name: String = "",
    val imageUrl: String = "",
    val createdAt: Long? = null,
)

/** Which page a socket frame reloads. */
enum class AdRefresh {
    /** The open shoot day: its header and the artiste rows on it. */
    Today,

    /** The artiste register. */
    Register,

    /** The list of shoot days. */
    Days,

    /** The artiste query threads. */
    Queries,

    /** Rates & Config, and with it the agency lists. */
    Rates,

    /** Reports and templates — the backend sends one event for both. */
    Reports,

    /** The PO / Payroll queue, which the server derives from the day headers. */
    Queue,

    /** Exhibit G signatures were voided somewhere else. */
    Signatures,
}
