package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The three module settings documents Production Setup edits in its modals —
 * purchase orders, invoices and payroll — and the payroll-accounts batch.
 *
 * ## Read the body, not `value`
 *
 * Unlike the project-settings slices, these three answer the document **bare**:
 * `{ data: { description_format, … } }`. The web reads `res.data` directly
 * (`POSetupDetail`, `InvoicesSetupDetail`, `PayrollSettingsDetail`) and so does
 * the desktop's own PO Settings tab. This client read `data.value`, found
 * nothing, and opened every modal on the defaults — and Save then wrote those
 * defaults over the production's real settings. The body is read first and a
 * `value` wrapper is still honoured, as `HubReportSource.rows` does for lists.
 *
 * ## A document that does not decode is a failure
 *
 * Never the defaults: a modal that opens on defaults after a bad read is one
 * Save away from overwriting the stored document. An absent or empty `data` is
 * a fresh project, and does read as the defaults — the web's `normalize({})`.
 *
 * ## `status: 0` over a 200 is a refusal
 *
 * The web's `handleSave` rejects anything but `status: 1`; `ApiClient.request`
 * does not look at the status at all.
 */
internal class HubSettingsSource(private val apiClient: ApiClient, config: AppConfig) {

    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    /** The invoices service, whose settings this is the only screen to write. */
    private val invoicesBase = "${config.apiV2(ZillitService.Invoices).trimEnd('/')}/invoices"

    /** The purchase-order service, which owns its own settings document. */
    private val poBase = "${config.baseUrl(ZillitService.PurchaseOrder)}/api/v2/purchase-orders"

    suspend fun invoicesSetup(): ZillitResult<InvoicesSetup> =
        read("$invoicesBase/settings", InvoicesSetupDto.serializer(), InvoicesSetup()) { it.toDomain() }

    suspend fun saveInvoicesSetup(setup: InvoicesSetup): ZillitResult<InvoicesSetup> = write(
        url = "$invoicesBase/settings",
        body = buildJsonObject {
            put("team_members", buildJsonArray { setup.teamMembers.forEach { add(it.forWire().toJson()) } })
            // The enabled keys only — see the domain's note on why this is a
            // list rather than a map of booleans.
            put("alerts", buildJsonArray { setup.alerts.forEach { add(JsonPrimitive(it.wire)) } })
            put(
                "run_authorization",
                buildJsonArray {
                    setup.renumbered().runAuthorisation.forEach { tier ->
                        add(
                            buildJsonObject {
                                put("tier", JsonPrimitive(tier.tier))
                                put("user", buildJsonArray { tier.userIds.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    }
                },
            )
        },
        serializer = InvoicesSetupDto.serializer(),
        sent = setup,
    ) { it.toDomain() }

    /**
     * The purchase-order service's own route, not an account-hub one: the
     * settings belong to that module even though the hub edits them too.
     */
    suspend fun purchaseOrderSetup(): ZillitResult<PurchaseOrderSetup> =
        read("$poBase/settings", PurchaseOrderSetupDto.serializer(), PurchaseOrderSetup()) { it.toDomain() }

    suspend fun savePurchaseOrderSetup(setup: PurchaseOrderSetup): ZillitResult<PurchaseOrderSetup> = write(
        url = "$poBase/settings",
        body = buildJsonObject {
            put("description_format", JsonPrimitive(setup.descriptionFormat.wire))
            put("auto_split_rentals", JsonPrimitive(setup.autoSplitRentals))
            put("default_split_type", JsonPrimitive(setup.splitType.wire))
            put("po_number_prefix", JsonPrimitive(setup.numberPrefix))
            put("terms_attachment", setup.termsDocument?.toJson() ?: JsonNull)
            // Null clears the rule — the web's `mapAssetFiltersToDb`, and what
            // the server normalises an all-empty rule to anyway.
            put("asset_filters", setup.assetFilters.toJson())
        },
        serializer = PurchaseOrderSetupDto.serializer(),
        sent = setup,
    ) { it.toDomain() }

    /**
     * Its own route off the hub base, not a project-settings slice: the row
     * lives in `payroll_settings_metadata` and payroll-server reads it directly.
     */
    suspend fun payrollSettings(): ZillitResult<PayrollSettings> =
        read("$hubBase/payroll-settings", PayrollSettingsDto.serializer(), PayrollSettings()) { it.toDomain() }

    suspend fun savePayrollSettings(settings: PayrollSettings): ZillitResult<PayrollSettings> = write(
        url = "$hubBase/payroll-settings",
        body = buildJsonObject {
            put("payroll_approvers", buildJsonArray { settings.approverIds.forEach { add(JsonPrimitive(it)) } })
            // Dropped once locked — the server answers 422 to any pay-period
            // change after the first timecard.
            if (!settings.payPeriodLocked) {
                put(
                    "pay_period",
                    buildJsonObject {
                        put("start_day_of_week", JsonPrimitive(settings.payPeriodStartDay))
                        put("end_day_of_week", JsonPrimitive(settings.payPeriodEndDay))
                    },
                )
            }
            put("journal_description_format", JsonPrimitive(settings.journalDescriptionFormat.wire))
            put("journal_group_by_category", JsonPrimitive(settings.journalGroupByCategory))
            // `payroll_accounts` is deliberately absent: the plain PATCH ignores
            // it, and the codes go through `/custom-accounts` so they cannot
            // drift from the chart.
        },
        serializer = PayrollSettingsDto.serializer(),
        // The codes never ride this PATCH, so an unreadable echo keeps them.
        sent = settings,
    ) { it.toDomain() }

    /**
     * The payroll-accounts batch. The echo is read leniently and is not the
     * truth the caller relies on — the web re-reads the settings after it, and
     * so does the modal.
     */
    suspend fun updatePayrollAccounts(rows: List<PayrollAccountRow>): ZillitResult<PayrollSettings> = write(
        url = "$hubBase/payroll-settings/custom-accounts",
        body = buildJsonObject {
            put(
                "rows",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                row.id?.let { put("id", JsonPrimitive(it)) }
                                if (row.delete) {
                                    put("status", JsonPrimitive("delete"))
                                } else {
                                    put("code", JsonPrimitive(row.code.trim()))
                                    put("name", JsonPrimitive(row.name.trim()))
                                    put("line_type", JsonPrimitive(row.lineType.wire))
                                }
                            },
                        )
                    }
                },
            )
        },
        serializer = PayrollSettingsDto.serializer(),
        sent = PayrollSettings(),
    ) { it.toDomain() }

    // -- plumbing ---------------------------------------------------------------

    private suspend fun <D, T> read(
        url: String,
        serializer: KSerializer<D>,
        empty: T,
        toDomain: (D) -> T,
    ): ZillitResult<T> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
    ).flatMap { envelope ->
        if (envelope.status == REFUSED) return@flatMap refusal(envelope)
        val document = envelope.data.settingsDocument() ?: return@flatMap ZillitResult.Success(empty)
        runCatching { toDomain(accountHubJson.decodeFromJsonElement(serializer, document)) }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Serialization(it.message)) },
        )
    }

    /** A write whose echo becomes the new baseline, or what was [sent] when the echo cannot be read. */
    private suspend fun <D, T> write(
        url: String,
        body: JsonElement,
        serializer: KSerializer<D>,
        sent: T,
        toDomain: (D) -> T,
    ): ZillitResult<T> = apiClient.envelope(
        verb = HttpVerb.Patch,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    ).flatMap { envelope ->
        if (envelope.status == REFUSED) return@flatMap refusal(envelope)
        val echo = envelope.data.settingsDocument()?.let { document ->
            runCatching { toDomain(accountHubJson.decodeFromJsonElement(serializer, document)) }.getOrNull()
        }
        ZillitResult.Success(echo ?: sent)
    }

    private fun <T> refusal(envelope: ApiEnvelope): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))

    private companion object {
        const val REFUSED = 0
    }
}

/**
 * The settings document in an answer that carries it bare (`data: { … }`) or
 * wrapped (`data: { value: { … } }`); null when there is none — a fresh
 * project, `data: null`, or an empty `value`.
 */
internal fun JsonElement?.settingsDocument(): JsonObject? {
    val body = this as? JsonObject ?: return null
    (body["value"] as? JsonObject)?.let { return it }
    return body.takeUnless { it.isEmpty() || it.keys == setOf("value") }
}

/**
 * One accounts-payable team member. A blank posting limit is null —
 * **Unlimited** — and zero is sent as zero: submit-only is a real answer.
 */
private fun InvoiceTeamMember.toJson(): JsonElement = buildJsonObject {
    put("user_id", JsonPrimitive(userId))
    put("posting_limit", postingLimit.trim().replace(",", "").toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
    put("run_access", JsonPrimitive(runAccess))
    put("override_access", JsonPrimitive(overrideAccess))
    put("is_senior", JsonPrimitive(isSenior))
}
