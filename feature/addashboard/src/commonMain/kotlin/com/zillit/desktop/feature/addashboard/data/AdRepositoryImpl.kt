package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdHost
import com.zillit.desktop.feature.addashboard.domain.AdReport
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdTransfer
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The AD dashboard's data layer.
 *
 * One service behind seven route families — `artistes`, `ad-shoot-days`,
 * `supporting-artist-days`, `ad-rate-config`, `ad-agencies`,
 * `artiste-queries` and `ad-report` — which the web routes together by prefix
 * and which therefore share a host here too. Four routes belong to other
 * services and are called on **their** hosts: a raised purchase order, the
 * approval tiers that seed it, the deal-memo agreements catalogue, the chart
 * of accounts, and the AD's saved signatures.
 *
 * [resolveName] turns an internal artiste's `user_id` into a crew name. It is
 * a seam rather than a call because the crew list belongs to the open
 * production, not to this service — the same arrangement every other module
 * that names a person uses.
 */
@Suppress("TooManyFunctions", "LargeClass") // One call per endpoint; the service has sixty.
class AdRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val resolveName: (String) -> String?,
    private val transfer: AdTransfer = AdTransfer.None,
    private val host: AdHost = AdHost.None,
    private val now: () -> Long = { 0L },
) : AdRepository {

    private val root = "${config.baseUrl(ZillitService.AdDashboard)}/api/v2"
    private val artistesRoot = "$root/artistes"
    private val shootDaysRoot = "$root/ad-shoot-days"
    private val saDaysRoot = "$root/supporting-artist-days"
    private val rateConfigRoot = "$root/ad-rate-config"
    private val agenciesRoot = "$root/ad-agencies"
    private val queriesRoot = "$root/artiste-queries"
    private val reportRoot = "$root/ad-report"
    private val hubRoot = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"
    private val poRoot = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders"
    private val dealMemoRoot = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"
    private val esignRoot = "${config.baseUrl(ZillitService.ESignature)}/v2/docusign"

    private fun reportBase(kind: ReportKind) = "$reportRoot/${kind.path}"

    private val templatesRoot = "$reportRoot/templates"

    // -- the register ---------------------------------------------------------

    override suspend fun artistes(): ZillitResult<List<Artiste>> =
        read(artistesRoot, ListSerializer(ArtisteDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain(resolveName) } }

    override suspend fun artiste(artisteId: String): ZillitResult<Artiste> =
        read("$artistesRoot/$artisteId", ArtisteDto.serializer()).flatMap { dto ->
            dto.toDomain(resolveName)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("artiste had no id"))
        }

    override suspend fun createArtiste(draft: ArtisteDraft): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = artistesRoot,
            serializer = CreatedIdDto.serializer(),
            module = RequestModule.ProjectUser,
            body = draft.body(),
        ).map { it.resolved }

    override suspend fun updateArtiste(artisteId: String, draft: ArtisteDraft): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$artistesRoot/$artisteId", draft.body())

    override suspend fun verify(artisteId: String): ZillitResult<Unit> =
        post("$artistesRoot/$artisteId/verify", null)

    override suspend fun block(artisteId: String, reason: String?): ZillitResult<Unit> = post(
        "$artistesRoot/$artisteId/block",
        buildJsonObject {
            // The server takes a null reason; sending "" instead would record
            // an empty explanation rather than none.
            val given = reason?.trim()?.takeIf { it.isNotEmpty() }
            put("reason", given?.let(::JsonPrimitive) ?: JsonNull)
        },
    )

    override suspend fun unblock(artisteId: String): ZillitResult<Unit> =
        post("$artistesRoot/$artisteId/unblock", null)

    override suspend fun deleteArtiste(artisteId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$artistesRoot/$artisteId", null)

    override suspend fun portalLink(artisteId: String): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$artistesRoot/$artisteId/portal-link",
            serializer = PortalTokenDto.serializer(),
            module = RequestModule.ProjectUser,
        ).flatMap { dto ->
            dto.token?.takeIf { it.isNotBlank() }?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_ad_portal_link_failed)))
        }

    // -- the agency-list import ----------------------------------------------

    /**
     * The file goes to storage first, and only its attachment to the service.
     *
     * The server reads the spreadsheet; the client never parses one. Nothing
     * is saved until a commit.
     */
    override suspend fun importPreview(fileName: String, bytes: ByteArray): ZillitResult<ImportPreview> {
        val contentType = contentTypeOf(fileName)
        val key = "ad-dashboard/imports/${now()}-${safeName(fileName)}"
        return when (val stored = transfer.putObject(key, contentType, bytes)) {
            is ZillitResult.Failure -> ZillitResult.Failure(stored.error)
            is ZillitResult.Success -> apiClient.request(
                verb = HttpVerb.Post,
                url = "$artistesRoot/import/preview",
                serializer = ImportPreviewDto.serializer(),
                module = RequestModule.ProjectUser,
                body = buildJsonObject {
                    put(
                        "attachment",
                        buildJsonObject {
                            put("media", JsonPrimitive(stored.data.key))
                            put("bucket", JsonPrimitive(stored.data.bucket))
                            put("region", JsonPrimitive(stored.data.region))
                            put("file_name", JsonPrimitive(fileName))
                            put("content_type", JsonPrimitive(contentType))
                        },
                    )
                },
            ).map { it.toDomain() }
        }
    }

    override suspend fun importCommit(rows: List<ImportRow>): ZillitResult<Int> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$artistesRoot/import/commit",
            serializer = ImportCommitDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("rows", rows.importRowsJson()) },
        ).map { it.resolved }

    override suspend fun importCommitToday(rows: List<ImportRow>, shootDate: Long): ZillitResult<Int> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$artistesRoot/import/commit-today",
            serializer = ImportCommitDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("rows", rows.importRowsJson())
                put("shoot_date", JsonPrimitive(shootDate))
                put("timezone", JsonPrimitive(AdDates.TIMEZONE))
            },
        ).map { it.resolved }

    // -- the day --------------------------------------------------------------

    /**
     * `data: null` until the day has a header, which the server creates with
     * the first extra — an ordinary state, not a failure.
     */
    override suspend fun today(shootDate: Long): ZillitResult<AdShootDay?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$shootDaysRoot/today",
            serializer = ShootDayDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("shoot_date" to shootDate),
        ).map { it?.toDomain() }

    override suspend fun shootDays(): ZillitResult<List<AdShootDay>> =
        read(shootDaysRoot, ListSerializer(ShootDayDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun dayNumber(shootDate: Long): ZillitResult<Int?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$shootDaysRoot/day-number",
            serializer = DayNumberDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("shoot_date" to shootDate),
        ).map { it?.resolved }

    override suspend fun dayList(shootDate: Long): ZillitResult<List<SupportingArtistDay>> =
        read(
            saDaysRoot,
            ListSerializer(SaDayDto.serializer()),
            query = mapOf("shoot_date" to shootDate),
        ).map { rows -> rows.mapNotNull { it.toDomain(resolveName) } }

    override suspend fun dayEntry(id: String): ZillitResult<SupportingArtistDay> =
        read("$saDaysRoot/$id", SaDayDto.serializer()).flatMap { dto ->
            dto.toDomain(resolveName)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("artiste day had no id"))
        }

    override suspend fun addToDay(
        artisteIds: List<String>,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit> = post(
        "$saDaysRoot/bulk",
        buildJsonObject {
            put("artiste_ids", JsonArray(artisteIds.map(::JsonPrimitive)))
            put("shoot_date", JsonPrimitive(shootDate))
            // Anchored on the day being booked, not on today: a time anchored
            // on today lands on the wrong date for tomorrow's list.
            AdDates.hhmmToUtcEpoch(shootDate, callTime)?.let { put("call_time", JsonPrimitive(it)) }
        },
    )

    override suspend fun createArtisteWithDay(
        draft: ArtisteDraft,
        shootDate: Long,
        callTime: String?,
    ): ZillitResult<Unit> = post(
        "$artistesRoot/with-day",
        buildJsonObject {
            put("artiste", draft.body())
            put("shoot_date", JsonPrimitive(shootDate))
            AdDates.hhmmToUtcEpoch(shootDate, callTime)?.let { put("call_time", JsonPrimitive(it)) }
        },
    )

    /**
     * Only the fields actually being changed are sent.
     *
     * A PATCH that carried every field would clear a wrap time somebody else
     * entered between this screen loading and the save.
     */
    override suspend fun updateDayEntry(id: String, patch: DayEntryPatch): ZillitResult<Unit> {
        if (patch.isEmpty) return ZillitResult.Success(Unit)
        return mutate(HttpVerb.Patch, "$saDaysRoot/$id", patch.body())
    }

    /**
     * One PATCH per id, and the ids that failed come back.
     *
     * A bulk mark must not be all-or-nothing: the rows that went through stay
     * marked, and only the failures are reverted on screen.
     */
    override suspend fun updateDayEntries(
        ids: List<String>,
        patch: DayEntryPatch,
    ): ZillitResult<List<String>> {
        if (patch.isEmpty || ids.isEmpty()) return ZillitResult.Success(emptyList())
        val body = patch.body()
        val failed = ids.filter { id ->
            mutate(HttpVerb.Patch, "$saDaysRoot/$id", body) is ZillitResult.Failure
        }
        return ZillitResult.Success(failed)
    }

    override suspend fun removeFromDay(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$saDaysRoot/$id", null)

    override suspend fun saveDayMeals(shootDate: Long, meals: List<MealBreak>): ZillitResult<Unit> =
        mutate(
            HttpVerb.Patch,
            "$shootDaysRoot/today",
            buildJsonObject {
                put("shoot_date", JsonPrimitive(shootDate))
                put("meals", meals.dayMealsJson(shootDate))
            },
        )

    override suspend fun appendDayPurchaseOrders(
        shootDate: Long,
        purchaseOrders: List<DayPurchaseOrder>,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        "$shootDaysRoot/today",
        buildJsonObject {
            put("shoot_date", JsonPrimitive(shootDate))
            put("purchase_orders", purchaseOrders.purchaseOrdersJson())
        },
    )

    override suspend fun patchDay(shootDate: Long, patch: DayPatch): ZillitResult<Unit> {
        if (patch.isEmpty) return ZillitResult.Success(Unit)
        return mutate(
            HttpVerb.Patch,
            "$shootDaysRoot/today",
            buildJsonObject {
                put("shoot_date", JsonPrimitive(shootDate))
                patch.writeInto(this)
            },
        )
    }

    override suspend fun submitDay(request: SubmitDayRequest): ZillitResult<Unit> = post(
        "$shootDaysRoot/submit",
        buildJsonObject {
            put(
                "signature",
                buildJsonObject {
                    put(
                        "typed_name",
                        request.typedName.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    // The image rides inline as a data URL on this one route;
                    // the Exhibit G sign call sends an uploaded attachment.
                    put("signature_image", request.signatureImage?.let(::JsonPrimitive) ?: JsonNull)
                    put("signed_at", JsonPrimitive(request.signedAt))
                },
            )
            put("sign_status", JsonPrimitive(request.signStatus))
            put("po_reference", JsonPrimitive(request.poReference))
            put("po_post_type", JsonPrimitive(request.poPostType))
            put("purchase_orders", request.purchaseOrders.purchaseOrdersJson())
            put("shoot_date", JsonPrimitive(request.shootDate))
        },
    )

    override suspend fun sendApprovalLink(id: String): ZillitResult<Unit> =
        post("$saDaysRoot/$id/send-approval-link", JsonObject(emptyMap()))

    override suspend fun exportDayList(
        format: ExportFormat,
        rows: List<SupportingArtistDay>,
        shootDate: Long,
        dayNumber: Int?,
    ): ZillitResult<DownloadedFile> {
        val body = buildJsonObject {
            put("format", JsonPrimitive(format.wire))
            put("rows", rows.exportJson())
            put(
                "meta",
                buildJsonObject {
                    put("shoot_date", JsonPrimitive(shootDate))
                    put("day_number", dayNumber?.let(::JsonPrimitive) ?: JsonNull)
                    put("timezone", JsonPrimitive(AdDates.TIMEZONE))
                },
            )
        }
        return transfer.postBytes("$saDaysRoot/export", body).map { bytes ->
            DownloadedFile("todays-list.${format.extension}", bytes)
        }
    }

    /**
     * An object of lists, read as one.
     *
     * Through a list reader the whole body would be rewritten to empty and
     * every nested list would vanish without an error.
     */
    override suspend fun poQueue(): ZillitResult<PoQueue> =
        read("$shootDaysRoot/po-queue", PoQueueDto.serializer()).map { it.toDomain() }

    // -- rates, agencies and queries -----------------------------------------

    override suspend fun rateConfig(): ZillitResult<RateConfig> =
        read(rateConfigRoot, RateConfigDto.serializer()).map { it.toDomain() }

    override suspend fun saveRateConfig(
        nonUnion: NonUnionRates,
        union: List<UnionPayBreakdown>,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        rateConfigRoot,
        buildJsonObject {
            put("non_union", nonUnion.body())
            put("union", buildJsonArray { union.forEach { add(it.body()) } })
        },
    )

    override suspend fun unionList(): ZillitResult<List<UnionAgreementRef>> =
        read("$rateConfigRoot/unions", ListSerializer(UnionRefDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun savedUnion(agreementId: String): ZillitResult<UnionPayBreakdown?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$rateConfigRoot/union/$agreementId",
            serializer = SavedUnionDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it?.toDomain() }

    override suspend fun catalogueAgreement(agreementId: String): ZillitResult<UnionAgreementDocument?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$dealMemoRoot/agreements/$agreementId",
            serializer = AgreementDocDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it?.toDomain() }

    override suspend fun agreementCatalogue(territory: String): ZillitResult<List<UnionAgreementRef>> =
        read(
            "$dealMemoRoot/agreements",
            ListSerializer(UnionRefDto.serializer()),
            query = mapOf("territory" to territory.takeIf { it.isNotBlank() }),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun deleteUnion(agreementId: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$rateConfigRoot/union/$agreementId", null)

    override suspend fun agencyVendors(): ZillitResult<List<Agency>> =
        read("$rateConfigRoot/vendors", ListSerializer(AgencyDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createAgencyVendor(draft: AgencyDraft): ZillitResult<Unit> =
        post("$rateConfigRoot/vendors", draft.body())

    override suspend fun deleteAgencyVendor(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$rateConfigRoot/vendors/$id", null)

    /**
     * The Agency tab's list.
     *
     * Read as a single value rather than a list: an empty project answers
     * `data: {}` here, which a list reader would throw on.
     */
    override suspend fun agencies(): ZillitResult<List<Agency>> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = agenciesRoot,
            serializer = ListSerializer(AgencyDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.orEmpty().mapNotNull { it.toDomain() } }

    override suspend fun createAgency(draft: AgencyDraft): ZillitResult<Unit> =
        post(agenciesRoot, draft.body())

    override suspend fun updateAgency(id: String, draft: AgencyDraft): ZillitResult<Unit> =
        mutate(HttpVerb.Patch, "$agenciesRoot/$id", draft.body())

    override suspend fun deleteAgency(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$agenciesRoot/$id", null)

    override suspend fun verifyAgency(id: String): ZillitResult<Unit> =
        post("$agenciesRoot/$id/verify", null)

    override suspend fun agencyHistory(id: String): ZillitResult<List<AgencyHistoryEntry>> =
        read("$agenciesRoot/$id/history", ListSerializer(AgencyHistoryDto.serializer()))
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun queries(): ZillitResult<List<ArtisteQuery>> =
        read(queriesRoot, ListSerializer(QueryDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun query(id: String): ZillitResult<ArtisteQuery> =
        read("$queriesRoot/$id", QueryDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("query had no id"))
        }

    override suspend fun replyToQuery(id: String, text: String): ZillitResult<Unit> = post(
        "$queriesRoot/$id/reply",
        buildJsonObject { put("text", JsonPrimitive(text)) },
    )

    override suspend fun resolveQuery(id: String): ZillitResult<Unit> =
        post("$queriesRoot/$id/resolve", JsonObject(emptyMap()))

    override suspend fun chartOfAccounts(): ZillitResult<List<NominalCode>> =
        read(
            "$hubRoot/chart-of-accounts",
            ListSerializer(NominalCodeDto.serializer()),
            query = mapOf("active_only" to true),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    // -- reports --------------------------------------------------------------

    override suspend fun reports(kind: ReportKind): ZillitResult<List<ReportSummary>> =
        read(
            reportBase(kind),
            ListSerializer(ReportSummaryDto.serializer()),
            query = untypedQuery(kind),
        ).map { rows -> rows.mapNotNull { it.toDomain(kind) } }

    override suspend fun report(kind: ReportKind, id: String): ZillitResult<AdReport> =
        read("${reportBase(kind)}/$id", ReportDto.serializer()).flatMap { dto ->
            dto.toDomain(kind)?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("report had no id"))
        }

    override suspend fun reportByDay(kind: ReportKind, shootDate: Long): ZillitResult<AdReport?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "${reportBase(kind)}/by-day",
            serializer = ReportDto.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = untypedQuery(kind) + mapOf("shoot_date" to shootDate),
        ).map { it?.toDomain(kind) }

    override suspend fun createReport(
        kind: ReportKind,
        draft: ReportDraft,
        asCopy: Boolean,
        notify: Boolean,
    ): ZillitResult<AdReport> = apiClient.request(
        verb = HttpVerb.Post,
        url = reportBase(kind),
        serializer = ReportDto.serializer(),
        module = RequestModule.ProjectUser,
        body = draft.body(kind, notify = notify, asCopy = asCopy),
    ).flatMap { dto ->
        dto.toDomain(kind)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("report had no id"))
    }

    override suspend fun saveReport(
        kind: ReportKind,
        id: String,
        draft: ReportDraft,
        notify: Boolean,
    ): ZillitResult<Unit> = mutate(
        HttpVerb.Patch,
        "${reportBase(kind)}/$id",
        draft.body(kind, notify = notify, asCopy = false),
    )

    /**
     * Publish, and the one refusal worth telling apart.
     *
     * `ad_report_signatures_pending` is not an error to show in red: it is the
     * sheet saying which performers still owe a signature, which the editor
     * opens as a list rather than a toast.
     */
    override suspend fun postReport(
        kind: ReportKind,
        id: String,
        draft: ReportDraft,
    ): ZillitResult<PostOutcome> {
        val outcome = apiClient.envelope(
            verb = HttpVerb.Post,
            url = "${reportBase(kind)}/$id/post",
            module = RequestModule.ProjectUser,
            body = draft.body(kind, notify = true, asCopy = false),
        )
        return when (outcome) {
            is ZillitResult.Success -> ZillitResult.Success(PostOutcome.Published)
            is ZillitResult.Failure -> when {
                !outcome.error.isSignaturesPending() -> ZillitResult.Failure(outcome.error)
                // The refusal names the sheet, not the rows, through the
                // envelope this client reads — so the sheet itself is re-read
                // and the outstanding rows worked out from its signatures,
                // which is the same list the web shows.
                else -> {
                    val rows = (report(kind, id) as? ZillitResult.Success)?.data?.unsignedCast.orEmpty()
                    ZillitResult.Success(PostOutcome.SignaturesPending(rows))
                }
            }
        }
    }

    override suspend fun deleteReport(kind: ReportKind, id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "${reportBase(kind)}/$id", null)

    override suspend fun saveReportAsTemplate(
        kind: ReportKind,
        id: String,
        name: String,
    ): ZillitResult<Unit> = post(
        "${reportBase(kind)}/$id/save-as-template",
        buildJsonObject { put("name", JsonPrimitive(name)) },
    )

    override suspend fun reportPdf(kind: ReportKind, id: String): ZillitResult<DownloadedFile> =
        transfer.getBytes("${reportBase(kind)}/$id/pdf").map { bytes ->
            DownloadedFile("${kind.path}-$id.pdf", bytes)
        }

    /**
     * The drawn signature goes to storage first and rides as an attachment.
     *
     * Only the public portal sends an inline data URL; in-app it is an upload
     * the client made, which is what the server's `signature_file` expects.
     */
    override suspend fun signReportRow(
        id: String,
        personId: String,
        cellId: String,
        rowId: String,
        signatureName: String,
        signatureBytes: ByteArray,
        inPerson: Boolean,
    ): ZillitResult<Unit> {
        val key = "ad-dashboard/signatures/${now()}-${safeName(signatureName)}.png"
        return when (val stored = transfer.putObject(key, "image/png", signatureBytes)) {
            is ZillitResult.Failure -> ZillitResult.Failure(stored.error)
            is ZillitResult.Success -> post(
                "${reportBase(ReportKind.ExhibitG)}/$id/sign",
                buildJsonObject {
                    put("person_id", JsonPrimitive(personId))
                    put("cell_id", JsonPrimitive(cellId))
                    put("row_id", JsonPrimitive(rowId))
                    put(
                        "signature_file",
                        buildJsonObject {
                            put("media", JsonPrimitive(stored.data.key))
                            put("bucket", JsonPrimitive(stored.data.bucket))
                            put("region", JsonPrimitive(stored.data.region))
                            put("content_type", JsonPrimitive("image/png"))
                        },
                    )
                    put("in_person", JsonPrimitive(inPerson))
                },
            )
        }
    }

    override suspend fun resetSignatures(id: String): ZillitResult<Unit> =
        post("${reportBase(ReportKind.ExhibitG)}/$id/reset-signatures", JsonObject(emptyMap()))

    override suspend fun templates(): ZillitResult<List<ReportTemplate>> =
        read(templatesRoot, ListSerializer(TemplateDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun template(id: String): ZillitResult<ReportTemplate> =
        read("$templatesRoot/$id", TemplateDto.serializer()).flatMap { dto ->
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("template had no id"))
        }

    override suspend fun createTemplate(name: String, kind: ReportKind): ZillitResult<String> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = templatesRoot,
            serializer = CreatedIdDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("name", JsonPrimitive(name))
                put("report_type", JsonPrimitive(kind.wire))
                put("pageRows", JsonArray(emptyList()))
            },
        ).map { it.resolved }

    override suspend fun saveTemplate(id: String, template: ReportTemplate): ZillitResult<Unit> =
        mutate(
            HttpVerb.Patch,
            "$templatesRoot/$id",
            buildJsonObject {
                put("name", JsonPrimitive(template.name))
                put("report_type", JsonPrimitive(template.kind.wire))
                put("pageRows", template.pageRows.pageRowsJson())
                put("meta", buildJsonObject { put("header", template.header.toJsonObject()) })
            },
        )

    override suspend fun deleteTemplate(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$templatesRoot/$id", null)

    override suspend fun duplicateTemplate(id: String, name: String): ZillitResult<Unit> =
        post(
            "$templatesRoot/$id/duplicate",
            buildJsonObject { put("name", JsonPrimitive(name)) },
        )

    // -- owned by other areas -------------------------------------------------

    /**
     * An ordinary **department** purchase order, not an accountant-entered one.
     *
     * `PENDING`, in the signed-in AD's own department, with the approval tiers
     * auto-seeded — the same thing the department PO flow sends. One line per
     * artiste at their [SupportingArtistDay.payTotal], so the agency's invoice
     * can be checked against it name by name.
     */
    override suspend fun createPurchaseOrder(
        group: AgencyGroup,
        reference: String,
        suffix: Int,
        dayLabel: String,
        dateLabel: String,
        urgent: Boolean,
    ): ZillitResult<DayPurchaseOrder> {
        val departmentId = host.departmentId()
        val approvals = seededApprovals(departmentId, group.amount)
        val body = buildJsonObject {
            put("vendor_id", JsonPrimitive(group.agencyId))
            put("department_id", departmentId?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "description",
                JsonPrimitive(
                    str(S.desktop_ad_po_description, dayLabel, group.agency, "$reference-${suffix + 1}"),
                ),
            )
            put("currency", JsonPrimitive(group.currency))
            put(
                "notes",
                JsonPrimitive(
                    (if (urgent) str(S.desktop_ad_po_urgent_prefix) else "") +
                        str(S.desktop_ad_po_notes, dayLabel, dateLabel),
                ),
            )
            // `net_amount`, not `total`: a header `total` is accepted with a
            // 200 and stored as an order with no amount at all.
            put("net_amount", JsonPrimitive(group.amount))
            put("status", JsonPrimitive("PENDING"))
            put("attachments", JsonArray(emptyList()))
            put("approvals", approvals)
            put(
                "line_items",
                buildJsonArray {
                    group.artistes.forEach { artiste ->
                        add(
                            buildJsonObject {
                                put(
                                    "description",
                                    JsonPrimitive("${artiste.artisteName} · ${artiste.category.label}"),
                                )
                                put("quantity", JsonPrimitive(1))
                                put("unit_price", JsonPrimitive(artiste.payTotal))
                                put("total", JsonPrimitive(artiste.payTotal))
                            },
                        )
                    }
                },
            )
        }
        return apiClient.request(
            verb = HttpVerb.Post,
            url = poRoot,
            serializer = CreatedPoDto.serializer(),
            module = RequestModule.ProjectUser,
            body = body,
        ).map { created ->
            DayPurchaseOrder(
                poId = created.resolved,
                agency = group.agency,
                agencyId = group.agencyId,
                currency = group.currency,
                amount = group.amount,
            )
        }
    }

    /**
     * The tiers that approve a PO of this size in this department.
     *
     * A tier config that will not load leaves the order with no seeded
     * approvals, which is what the department flow does too — the order still
     * reaches the queue, and an accountant routes it by hand.
     */
    private suspend fun seededApprovals(departmentId: String?, amount: Double): JsonArray {
        val tiers = read(
            "$hubRoot/approval-tiers",
            ListSerializer(ApprovalTierDto.serializer()),
            query = mapOf("module" to "purchase_orders"),
        )
        val rows = (tiers as? ZillitResult.Success)?.data ?: return JsonArray(emptyList())
        val matching = rows.firstOrNull { tier ->
            val department = tier.departmentId
            val withinDepartment = department.isNullOrBlank() || department == departmentId
            val floor = tier.minAmount ?: 0.0
            val ceiling = tier.maxAmount ?: Double.MAX_VALUE
            withinDepartment && amount >= floor && amount <= ceiling
        } ?: return JsonArray(emptyList())
        return buildJsonArray {
            matching.approvers.orEmpty().forEach { approver ->
                val userId = approver.userId?.takeIf { it.isNotBlank() } ?: return@forEach
                add(
                    buildJsonObject {
                        put("user_id", JsonPrimitive(userId))
                        put("tier_number", JsonPrimitive(approver.level ?: 1))
                        put("approved_at", JsonNull)
                    },
                )
            }
        }
    }

    override suspend fun savedSignatures(): ZillitResult<List<SavedSignature>> =
        read("$esignRoot/saved-signatures", ListSerializer(SavedSignatureDto.serializer()))
            .map { rows ->
                rows.mapNotNull { row ->
                    val id = row.underscoreId ?: row.id ?: return@mapNotNull null
                    SavedSignature(
                        id = id,
                        name = row.name.orEmpty(),
                        imageUrl = row.signatureImage ?: row.imageUrl.orEmpty(),
                        createdAt = row.createdAt?.toLong(),
                    )
                }
            }

    override suspend fun saveSignature(name: String, bytes: ByteArray): ZillitResult<Unit> = post(
        "$esignRoot/saved-signatures",
        buildJsonObject {
            put("name", JsonPrimitive(name))
            put("signature_image", JsonPrimitive(bytes.asPngDataUrl()))
        },
    )

    override suspend fun deleteSavedSignature(id: String): ZillitResult<Unit> =
        mutate(HttpVerb.Delete, "$esignRoot/saved-signatures/$id", null)

    // -- plumbing ------------------------------------------------------------

    /** Only the AD Report claims rows with no `report_type`. */
    private fun untypedQuery(kind: ReportKind): Map<String, Any?> =
        if (kind.includeUntyped) mapOf("include_untyped" to 1) else emptyMap()

    private suspend fun <T> read(
        url: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun post(url: String, body: JsonObject?) = mutate(HttpVerb.Post, url, body)

    private suspend fun mutate(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = verb,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    ).asUnit()

    private fun ZillitResult<ApiEnvelope>.asUnit(): ZillitResult<Unit> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(Unit)
        is ZillitResult.Failure -> ZillitResult.Failure(error)
    }

    private inline fun <T, R> ZillitResult<T>.flatMap(
        transform: (T) -> ZillitResult<R>,
    ): ZillitResult<R> = when (this) {
        is ZillitResult.Success -> transform(data)
        is ZillitResult.Failure -> ZillitResult.Failure(error)
    }
}
