package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.payroll.domain.DealCoding
import com.zillit.desktop.feature.payroll.domain.JournalReference
import com.zillit.desktop.feature.payroll.domain.OverrideFlags
import com.zillit.desktop.feature.payroll.domain.PayrollCompany
import com.zillit.desktop.feature.payroll.domain.PayrollCurrencyRates
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollSettingsRepository
import com.zillit.desktop.feature.payroll.domain.TrackingNode
import com.zillit.desktop.feature.payroll.domain.TrackingSet
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.coroutineScope

/**
 * Settings, rights and reference data — four hosts' worth of reads that the
 * three payroll screens are drawn against rather than about.
 */
internal class PayrollSettingsSource(
    private val http: PayrollHttp,
    config: AppConfig,
    private val payroll: String,
) : PayrollSettingsRepository {

    private val hub = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"
    private val costReport = "${config.baseUrl(ZillitService.CostReport)}/api/v2/cost-reports"
    private val dealMemo = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"

    /**
     * Both halves at once, as the web's `fetchMetadata` does; the settings half
     * is allowed to fail. The chart of accounts names the payroll accounts —
     * a code the chart does not know is its own name, as on the web.
     */
    override suspend fun metadata(): ZillitResult<PayrollMetadata> = coroutineScope {
        val meta = async { http.get("$payroll/metadata") }
        val settings = async { http.get("$hub/payroll-settings") }
        val chart = async { http.get("$hub/chart-of-accounts", mapOf("active_only" to "true")) }
        meta.await().map { data ->
            parseMetadata(
                meta = data.obj(),
                settings = (settings.await() as? ZillitResult.Success)?.data.obj(),
                chart = (chart.await() as? ZillitResult.Success)?.data.rows(),
            )
        }
    }

    override suspend fun overrideFlags(): ZillitResult<OverrideFlags> =
        http.get("$payroll/timecards/metadata").map { data ->
            val body = data.obj()?.let { it.obj("data") ?: it }
            OverrideFlags(
                isAccountant = body?.flag("is_accountant") == true,
                isApprover = body?.flag("is_approver") == true,
            )
        }

    /**
     * The web's `useCrLock`: the lock route and the project settings read the
     * same row stored two ways, and the live route has failed outright on a
     * string date — so both are read and the later date wins. Only when both
     * fail is the route's failure returned.
     */
    override suspend fun lockedDate(): ZillitResult<String?> = coroutineScope {
        val route = async { http.get("$costReport/lock-period") }
        val settings = async { http.get("$hub/project-settings") }
        val routeResult = route.await()
        val settingsResult = settings.await()
        val later = listOfNotNull(
            (routeResult as? ZillitResult.Success)?.data.routeLockDate(),
            (settingsResult as? ZillitResult.Success)?.data.settingsLockDate(),
        ).maxOrNull()
        when {
            later != null -> ZillitResult.Success(later)
            routeResult is ZillitResult.Failure && settingsResult is ZillitResult.Failure -> routeResult
            else -> ZillitResult.Success(null)
        }
    }

    /** `project-settings/companies` reads wrapped under `value`, like every per-slice route. */
    override suspend fun companies(): ZillitResult<List<PayrollCompany>> =
        http.get("$hub/project-settings/companies").map { data ->
            val list = data.obj()?.let { it["value"] } ?: data
            list.rows().mapNotNull { row ->
                val id = row.identifier() ?: return@mapNotNull null
                PayrollCompany(id = id, name = row.text("name", "legal_name").orEmpty(), country = row.text("country"))
            }
        }

    /**
     * `/project-settings/project-currencies` — the same row the rest of the
     * hub reads its default from.
     */
    override suspend fun defaultCurrency(): ZillitResult<String?> =
        http.get("$hub/project-settings/project-currencies").map { data ->
            // `default` is the key the hub's own reader uses; the rest are
            // spellings older productions were saved under.
            val body = data.obj()?.let { it.obj("value") ?: it.obj("data") ?: it }
            body?.text("default", "default_currency", "default_code")
        }

    /**
     * `exr`, foreign-per-default — the same read [defaultCurrency] makes,
     * plus the per-currency rate array [defaultCurrency] discards.
     */
    override suspend fun currencyRates(): ZillitResult<PayrollCurrencyRates> =
        http.get("$hub/project-settings/project-currencies").map { data ->
            val body = data.obj()?.let { it.obj("value") ?: it.obj("data") ?: it }
            val rows = body?.array("currencies").orEmpty().mapNotNull { it as? JsonObject }
            val rates = rows.mapNotNull { row ->
                val code = row.text("code")?.uppercase() ?: return@mapNotNull null
                row.number("exr")?.takeIf { it > 0 }?.let { code to it }
            }.toMap()
            PayrollCurrencyRates(
                defaultCode = body?.text("default", "default_currency", "default_code")?.uppercase(),
                rates = rates,
            )
        }

    /**
     * Both halves at once. The sets come from the hub's tracking-sets route
     * with their nodes bundled; the tags are a field of the project settings
     * document, as Production Setup writes them.
     */
    override suspend fun journalReference(): ZillitResult<JournalReference> = coroutineScope {
        val sets = async {
            http.get("$hub/tracking-sets", mapOf("active_only" to "true", "include_nodes" to "true"))
        }
        val settings = async { http.get("$hub/project-settings") }
        ZillitResult.Success(
            JournalReference(
                trackingSets = (sets.await() as? ZillitResult.Success)?.data.toTrackingSets(),
                assetTags = (settings.await() as? ZillitResult.Success)?.data.toAssetTags(),
            ),
        )
    }

    override suspend fun activeDealCoding(userId: String): ZillitResult<DealCoding?> =
        http.get("$dealMemo/deals/active/$userId").map { data ->
            data.obj()?.let { it.obj("data") ?: it }?.toDealCoding()
        }
}


/**
 * Active sets with their active, non-header codes — the web's
 * `TrackingCodesPicker` filter. A header is a grouping row, not something a
 * line can be coded to.
 */
internal fun JsonElement?.toTrackingSets(): List<TrackingSet> = rows()
    .filter { it.flagOrTrue("active") }
    .mapNotNull { set ->
        val id = set.identifier() ?: return@mapNotNull null
        TrackingSet(
            id = id,
            name = set.text("name").orEmpty(),
            nodes = set.objects("nodes")
                .filter { it.flagOrTrue("active") && !it.flag("is_header") }
                .mapNotNull { node ->
                    node.text("code")?.let { TrackingNode(it, node.text("label", "name").orEmpty()) }
                },
        )
    }

/** Production Setup's Account Tags — the web's `useProjectAssetTags`. */
internal fun JsonElement?.toAssetTags(): List<String> {
    val root = obj()?.let { it.obj("value") ?: it }
    val settings = root?.obj("settings") ?: root ?: return emptyList()
    return settings.array("asset_tags").mapNotNull {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** Absent means active: only an explicit `false` disables a set or a node. */
private fun JsonObject.flagOrTrue(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull != false
