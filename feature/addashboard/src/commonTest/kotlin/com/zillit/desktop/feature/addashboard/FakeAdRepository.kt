package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.addashboard.domain.AdReport
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgencyDraft
import com.zillit.desktop.feature.addashboard.domain.AgencyGroup
import com.zillit.desktop.feature.addashboard.domain.AgencyHistoryEntry
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteDraft
import com.zillit.desktop.feature.addashboard.domain.ArtisteQuery
import com.zillit.desktop.feature.addashboard.domain.DayEntryPatch
import com.zillit.desktop.feature.addashboard.domain.DayPatch
import com.zillit.desktop.feature.addashboard.domain.DayPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.DownloadedFile
import com.zillit.desktop.feature.addashboard.domain.ExportFormat
import com.zillit.desktop.feature.addashboard.domain.ImportPreview
import com.zillit.desktop.feature.addashboard.domain.ImportRow
import com.zillit.desktop.feature.addashboard.domain.MealBreak
import com.zillit.desktop.feature.addashboard.domain.NominalCode
import com.zillit.desktop.feature.addashboard.domain.NonUnionRates
import com.zillit.desktop.feature.addashboard.domain.PoQueue
import com.zillit.desktop.feature.addashboard.domain.PostOutcome
import com.zillit.desktop.feature.addashboard.domain.RateConfig
import com.zillit.desktop.feature.addashboard.domain.ReportDraft
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.SavedSignature
import com.zillit.desktop.feature.addashboard.domain.SubmitDayRequest
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementDocument
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementRef
import com.zillit.desktop.feature.addashboard.domain.UnionPayBreakdown

/**
 * The AD service, answering plausibly and recording what it was asked.
 *
 * One fake for every test in the module: the contract has sixty calls, and a
 * test that only cares about the day lock should not have to implement the
 * report editor to compile. Each member answers an empty success, so a test
 * overrides only what it is about.
 */
@Suppress("TooManyFunctions") // One per endpoint; the contract has sixty.
internal open class FakeAdRepository : AdRepository {

    /** Every write, in the order it was asked for — what a gate test reads. */
    val writes = mutableListOf<String>()

    var artisteRows: List<Artiste> = emptyList()
    var dayRows: List<SupportingArtistDay> = emptyList()
    var dayHeader: AdShootDay? = null
    var shootDayRows: List<AdShootDay> = emptyList()
    var reportRows: Map<ReportKind, List<ReportSummary>> = emptyMap()
    var templateRows: List<ReportTemplate> = emptyList()
    var queryRows: List<ArtisteQuery> = emptyList()
    var agencyRows: List<Agency> = emptyList()
    var config: RateConfig = RateConfig()
    var queue: PoQueue = PoQueue()

    /** The dates `today` was asked for, in order. */
    val daysAsked = mutableListOf<Long>()

    // -- the register ---------------------------------------------------------

    override suspend fun artistes(): ZillitResult<List<Artiste>> = ZillitResult.Success(artisteRows)

    override suspend fun artiste(artisteId: String): ZillitResult<Artiste> =
        artisteRows.firstOrNull { it.id == artisteId }
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Success(Artiste(id = artisteId, name = artisteId))

    override suspend fun createArtiste(draft: ArtisteDraft): ZillitResult<String> {
        writes += "createArtiste:${draft.status.wire}"
        return ZillitResult.Success("new")
    }

    override suspend fun updateArtiste(artisteId: String, draft: ArtisteDraft): ZillitResult<Unit> {
        writes += "updateArtiste:$artisteId:${draft.status.wire}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun verify(artisteId: String): ZillitResult<Unit> {
        writes += "verify:$artisteId"
        return ZillitResult.Success(Unit)
    }

    override suspend fun block(artisteId: String, reason: String?): ZillitResult<Unit> {
        writes += "block:$artisteId:${reason.orEmpty()}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun unblock(artisteId: String): ZillitResult<Unit> {
        writes += "unblock:$artisteId"
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteArtiste(artisteId: String): ZillitResult<Unit> {
        writes += "deleteArtiste:$artisteId"
        return ZillitResult.Success(Unit)
    }

    override suspend fun portalLink(artisteId: String): ZillitResult<String> {
        writes += "portalLink:$artisteId"
        return ZillitResult.Success("tok-$artisteId")
    }

    override suspend fun importPreview(fileName: String, bytes: ByteArray): ZillitResult<ImportPreview> =
        ZillitResult.Success(ImportPreview())

    override suspend fun importCommit(rows: List<ImportRow>): ZillitResult<Int> {
        writes += "importCommit:${rows.size}"
        return ZillitResult.Success(rows.size)
    }

    override suspend fun importCommitToday(rows: List<ImportRow>, shootDate: Long): ZillitResult<Int> {
        writes += "importCommitToday:${rows.size}:$shootDate"
        return ZillitResult.Success(rows.size)
    }

    // -- the day --------------------------------------------------------------

    override suspend fun today(shootDate: Long): ZillitResult<AdShootDay?> {
        daysAsked += shootDate
        return ZillitResult.Success(dayHeader?.copy(shootDate = shootDate))
    }

    override suspend fun shootDays(): ZillitResult<List<AdShootDay>> = ZillitResult.Success(shootDayRows)

    override suspend fun dayNumber(shootDate: Long): ZillitResult<Int?> = ZillitResult.Success(null)

    override suspend fun dayList(shootDate: Long): ZillitResult<List<SupportingArtistDay>> =
        ZillitResult.Success(dayRows)

    override suspend fun dayEntry(id: String): ZillitResult<SupportingArtistDay> =
        dayRows.firstOrNull { it.id == id }
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Success(SupportingArtistDay(id = id))

    override suspend fun addToDay(
        artisteIds: List<String>,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit> {
        writes += "addToDay:${artisteIds.joinToString("|")}:$shootDate:${callTime.orEmpty()}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun createArtisteWithDay(
        draft: ArtisteDraft,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit> {
        writes += "createWithDay:${draft.email}:$shootDate"
        return ZillitResult.Success(Unit)
    }

    override suspend fun updateDayEntry(id: String, patch: DayEntryPatch): ZillitResult<Unit> {
        writes += "update:$id:${patch.summary()}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun updateDayEntries(
        ids: List<String>,
        patch: DayEntryPatch,
    ): ZillitResult<List<String>> {
        writes += "updateMany:${ids.joinToString("|")}:${patch.summary()}"
        return ZillitResult.Success(emptyList())
    }

    override suspend fun removeFromDay(id: String): ZillitResult<Unit> {
        writes += "remove:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun saveDayMeals(shootDate: Long, meals: List<MealBreak>): ZillitResult<Unit> {
        writes += "saveDayMeals:$shootDate:${meals.size}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun appendDayPurchaseOrders(
        shootDate: Long,
        purchaseOrders: List<DayPurchaseOrder>,
    ): ZillitResult<Unit> {
        writes += "appendPos:${purchaseOrders.size}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun patchDay(shootDate: Long, patch: DayPatch): ZillitResult<Unit> {
        writes += "patchDay:$shootDate"
        return ZillitResult.Success(Unit)
    }

    override suspend fun submitDay(request: SubmitDayRequest): ZillitResult<Unit> {
        writes += "submit:${request.shootDate}:${request.poReference}:${request.purchaseOrders.size}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun sendApprovalLink(id: String): ZillitResult<Unit> {
        writes += "sendApprovalLink:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun exportDayList(
        format: ExportFormat,
        rows: List<SupportingArtistDay>,
        shootDate: Long,
        dayNumber: Int?,
    ): ZillitResult<DownloadedFile> {
        writes += "export:${format.wire}:${rows.size}"
        return ZillitResult.Success(DownloadedFile("todays-list.${format.extension}", ByteArray(0)))
    }

    override suspend fun poQueue(): ZillitResult<PoQueue> = ZillitResult.Success(queue)

    // -- rates, agencies and queries -----------------------------------------

    override suspend fun rateConfig(): ZillitResult<RateConfig> = ZillitResult.Success(config)

    override suspend fun saveRateConfig(
        nonUnion: NonUnionRates,
        union: List<UnionPayBreakdown>,
    ): ZillitResult<Unit> {
        writes += "saveRateConfig:${union.size}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun unionList(): ZillitResult<List<UnionAgreementRef>> =
        ZillitResult.Success(config.unionAgreements)

    override suspend fun savedUnion(agreementId: String): ZillitResult<UnionPayBreakdown?> =
        ZillitResult.Success(null)

    override suspend fun catalogueAgreement(agreementId: String): ZillitResult<UnionAgreementDocument?> =
        ZillitResult.Success(null)

    override suspend fun agreementCatalogue(territory: String): ZillitResult<List<UnionAgreementRef>> =
        ZillitResult.Success(emptyList())

    override suspend fun deleteUnion(agreementId: String): ZillitResult<Unit> {
        writes += "deleteUnion:$agreementId"
        return ZillitResult.Success(Unit)
    }

    override suspend fun agencyVendors(): ZillitResult<List<Agency>> = ZillitResult.Success(agencyRows)

    override suspend fun createAgencyVendor(draft: AgencyDraft): ZillitResult<Unit> {
        writes += "createVendor:${draft.name}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteAgencyVendor(id: String): ZillitResult<Unit> {
        writes += "deleteVendor:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun agencies(): ZillitResult<List<Agency>> = ZillitResult.Success(agencyRows)

    override suspend fun createAgency(draft: AgencyDraft): ZillitResult<Unit> {
        writes += "createAgency:${draft.name}"
        return ZillitResult.Success(Unit)
    }

    override suspend fun updateAgency(id: String, draft: AgencyDraft): ZillitResult<Unit> {
        writes += "updateAgency:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteAgency(id: String): ZillitResult<Unit> {
        writes += "deleteAgency:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun verifyAgency(id: String): ZillitResult<Unit> {
        writes += "verifyAgency:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun agencyHistory(id: String): ZillitResult<List<AgencyHistoryEntry>> =
        ZillitResult.Success(emptyList())

    override suspend fun queries(): ZillitResult<List<ArtisteQuery>> = ZillitResult.Success(queryRows)

    override suspend fun query(id: String): ZillitResult<ArtisteQuery> =
        queryRows.firstOrNull { it.id == id }
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Success(ArtisteQuery(id = id))

    override suspend fun replyToQuery(id: String, text: String): ZillitResult<Unit> {
        writes += "reply:$id:$text"
        return ZillitResult.Success(Unit)
    }

    override suspend fun resolveQuery(id: String): ZillitResult<Unit> {
        writes += "resolveQuery:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun chartOfAccounts(): ZillitResult<List<NominalCode>> =
        ZillitResult.Success(emptyList())

    // -- reports --------------------------------------------------------------

    override suspend fun reports(kind: ReportKind): ZillitResult<List<ReportSummary>> =
        ZillitResult.Success(reportRows[kind].orEmpty())

    override suspend fun report(kind: ReportKind, id: String): ZillitResult<AdReport> =
        ZillitResult.Success(AdReport(id = id, kind = kind))

    override suspend fun reportByDay(kind: ReportKind, shootDate: Long): ZillitResult<AdReport?> =
        ZillitResult.Success(null)

    override suspend fun createReport(
        kind: ReportKind,
        draft: ReportDraft,
        asCopy: Boolean,
        notify: Boolean,
    ): ZillitResult<AdReport> {
        writes += "createReport:${kind.wire}:asCopy=$asCopy:notify=$notify"
        return ZillitResult.Success(
            AdReport(id = draft.id ?: "r1", kind = kind, name = draft.name, shootDate = draft.shootDate),
        )
    }

    override suspend fun saveReport(
        kind: ReportKind,
        id: String,
        draft: ReportDraft,
        notify: Boolean,
    ): ZillitResult<Unit> {
        writes += "saveReport:${kind.wire}:$id:notify=$notify"
        return ZillitResult.Success(Unit)
    }

    override suspend fun postReport(
        kind: ReportKind,
        id: String,
        draft: ReportDraft,
    ): ZillitResult<PostOutcome> {
        writes += "postReport:${kind.wire}:$id"
        return ZillitResult.Success(PostOutcome.Published)
    }

    override suspend fun deleteReport(kind: ReportKind, id: String): ZillitResult<Unit> {
        writes += "deleteReport:${kind.wire}:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun saveReportAsTemplate(
        kind: ReportKind,
        id: String,
        name: String,
    ): ZillitResult<Unit> {
        writes += "saveAsTemplate:$name"
        return ZillitResult.Success(Unit)
    }

    override suspend fun reportPdf(kind: ReportKind, id: String): ZillitResult<DownloadedFile> =
        ZillitResult.Success(DownloadedFile("$id.pdf", ByteArray(0)))

    override suspend fun signReportRow(
        id: String,
        personId: String,
        cellId: String,
        rowId: String,
        signatureName: String,
        signatureBytes: ByteArray,
        inPerson: Boolean,
    ): ZillitResult<Unit> {
        writes += "sign:$id:$personId:inPerson=$inPerson"
        return ZillitResult.Success(Unit)
    }

    override suspend fun resetSignatures(id: String): ZillitResult<Unit> {
        writes += "resetSignatures:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun templates(): ZillitResult<List<ReportTemplate>> =
        ZillitResult.Success(templateRows)

    override suspend fun template(id: String): ZillitResult<ReportTemplate> =
        templateRows.firstOrNull { it.id == id }
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Success(ReportTemplate(id = id))

    override suspend fun createTemplate(name: String, kind: ReportKind): ZillitResult<String> {
        writes += "createTemplate:$name"
        return ZillitResult.Success("t1")
    }

    override suspend fun saveTemplate(id: String, template: ReportTemplate): ZillitResult<Unit> {
        writes += "saveTemplate:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteTemplate(id: String): ZillitResult<Unit> {
        writes += "deleteTemplate:$id"
        return ZillitResult.Success(Unit)
    }

    override suspend fun duplicateTemplate(id: String, name: String): ZillitResult<Unit> {
        writes += "duplicateTemplate:$id"
        return ZillitResult.Success(Unit)
    }

    // -- owned by other areas -------------------------------------------------

    override suspend fun createPurchaseOrder(
        group: AgencyGroup,
        reference: String,
        suffix: Int,
        dayLabel: String,
        dateLabel: String,
        urgent: Boolean,
    ): ZillitResult<DayPurchaseOrder> {
        writes += "createPo:${group.agency}:${group.currency}:$reference-${suffix + 1}:urgent=$urgent"
        return ZillitResult.Success(
            DayPurchaseOrder(
                poId = "po-${group.key}",
                agency = group.agency,
                agencyId = group.agencyId,
                currency = group.currency,
                amount = group.amount,
            ),
        )
    }

    override suspend fun savedSignatures(): ZillitResult<List<SavedSignature>> =
        ZillitResult.Success(emptyList())

    override suspend fun saveSignature(name: String, bytes: ByteArray): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    override suspend fun deleteSavedSignature(id: String): ZillitResult<Unit> =
        ZillitResult.Success(Unit)
}

/** What a patch changed, short enough to assert on. */
private fun DayEntryPatch.summary(): String = buildList {
    attendance?.let { add(it.wire) }
    if (clearAttendance) add("attendance=null")
    callTime?.let { add("call=$it") }
    if (clearCallTime) add("call=null")
    wrapTime?.let { add("wrap=$it") }
    if (clearWrapTime) add("wrap=null")
    dayType?.let { add("dayType=$it") }
    meals?.let { add("meals=${it.size}") }
    allowances?.let { add("allowances=${it.size}") }
    additionalFees?.let { add("fees=${it.size}") }
    notes?.let { add("notes") }
}.joinToString(",")
