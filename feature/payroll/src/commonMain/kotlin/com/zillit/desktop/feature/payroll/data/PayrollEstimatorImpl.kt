package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.payroll.domain.ActiveDeal
import com.zillit.desktop.feature.payroll.domain.DayCalc
import com.zillit.desktop.feature.payroll.domain.DealRates
import com.zillit.desktop.feature.payroll.domain.EstimateInput
import com.zillit.desktop.feature.payroll.domain.PayrollEstimator
import com.zillit.desktop.feature.payroll.domain.PayrollScriptHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The production's pay engine, run the way the web runs it.
 *
 * `calcDay` and `deriveRatesFromDeal` are not this application's arithmetic:
 * they are published by the payroll service as a JavaScript bundle per
 * agreement, versioned in a manifest, and fetched at runtime
 * (`lib/otEngine.js`). Reimplementing them here would mean two independent
 * sets of overtime rules going out of step the first time an agreement
 * changed — and the number that drifts is somebody's pay. So this fetches the
 * SAME bundle and hands it to a [PayrollScriptHost] to execute.
 *
 * The manifest is re-read on every `ensure`, so a version bump on the server
 * reaches an open window at the next deal opened; a bundle already loaded at
 * that version is reused. A bundle that cannot be fetched or cannot be run
 * leaves the engine unloaded, and every caller degrades as the web's does when
 * `isEngineLoaded()` is false.
 */
internal class PayrollEstimatorImpl(
    private val http: PayrollHttp,
    private val transport: PayrollBinaryTransport,
    private val host: PayrollScriptHost,
    config: AppConfig,
) : PayrollEstimator {

    private val base = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/ot-engine"
    private val loaded = mutableSetOf<String>()

    override suspend fun load(deal: ActiveDeal): ZillitResult<DealRates> {
        val key = when (val engine = ensure(deal.agreementIdentifier)) {
            is ZillitResult.Failure -> return engine
            is ZillitResult.Success -> engine.data
        }
        val rates = host.call(key, DERIVE_RATES, listOf(deal.document))
            ?: return refused("the engine answered no rates for this deal")
        return ZillitResult.Success(
            DealRates(
                deal = deal,
                rates = rates,
                engineKey = key,
                // Holiday pay is never stored on a timecard, so every surface
                // that shows another crew member's accrual derives it from the
                // deal's own rate — the web's `useCrewHolidayPay`.
                holidayPayRate = rates.parse()?.number("hp_rate")?.takeIf { it > 0 } ?: 0.0,
            ),
        )
    }

    /** The bundle for an agreement, loaded if it is not already, named by key. */
    private suspend fun ensure(agreementIdentifier: String?): ZillitResult<String> {
        val engines = manifest() ?: return refused("no engine manifest")
        // An agreement without a published engine of its own is priced by the
        // default one, as the web resolves it.
        val id = agreementIdentifier?.takeIf { engines.containsKey(it) } ?: DEFAULT_ENGINE
        val entry = engines[id] as? JsonObject ?: return refused("manifest has no engine '$id'")
        val bundle = entry.text("bundle") ?: return refused("engine '$id' names no bundle")
        val key = "$id@${entry.text("version").orEmpty()}"
        return if (key in loaded) ZillitResult.Success(key) else fetchAndRun(key, bundle)
    }

    /**
     * Fetches a bundle and runs it. A bundle that will not run is a refusal
     * rather than a crash: the boards that price locally say they cannot,
     * exactly as the web behaves when its own load fails.
     */
    private suspend fun fetchAndRun(key: String, bundle: String): ZillitResult<String> {
        val source = when (val bytes = transport.get("$base/$bundle")) {
            is ZillitResult.Failure -> return bytes
            is ZillitResult.Success -> bytes.data.decodeToString()
        }
        if (!host.load(key, source)) return refused("engine '$key' would not run")
        loaded += key
        return ZillitResult.Success(key)
    }

    override suspend fun calcDay(
        input: EstimateInput,
        index: Int,
        rates: DealRates,
        previous: EstimateInput?,
    ): ZillitResult<DayCalc?> {
        val key = rates.engineKey.takeIf { it.isNotBlank() } ?: return refused("these rates name no engine")
        val answer = host.call(
            key,
            CALC_DAY,
            listOf(input.toJson().toString(), index.toString(), options(rates, previous).toString()),
        ) ?: return ZillitResult.Success(null)
        return ZillitResult.Success(answer.parse()?.toDayCalc())
    }

    /**
     * Re-read every time, as the web re-reads it: the payload is tiny and
     * no-cache, and it is how a server-side version bump propagates.
     */
    private suspend fun manifest(): Map<String, JsonElement>? =
        http.get("$base/manifest").getOrNull().obj()
            ?.let { it.obj("data") ?: it }
            ?.obj("engines")

    /** `{ deal, rates, prevDay }` — the engine's third argument. */
    private fun options(rates: DealRates, previous: EstimateInput?): JsonObject = buildJsonObject {
        rates.deal.document.parse()?.let { put("deal", it) }
        rates.rates.parse()?.let { put("rates", it) }
        // A day that was not filled breaks the turnaround chain, and so does a
        // null here — the same thing said two ways.
        put("prevDay", previous?.toJson() ?: JsonNull)
    }

    private fun <T> refused(reason: String): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Unknown("payroll pay engine: $reason"))

    private companion object {
        const val DEFAULT_ENGINE = "default"
        const val DERIVE_RATES = "deriveRatesFromDeal"
        const val CALC_DAY = "calcDay"
    }
}

/** The day, in the shape the engine's `calcDay` reads (`ui`). */
private fun EstimateInput.toJson(): JsonObject = buildJsonObject {
    put("dateMs", JsonPrimitive(dateMillis))
    put("type", JsonPrimitive(dayType))
    put("call", call?.let(::JsonPrimitive) ?: JsonNull)
    put("timeOut", timeOut?.let(::JsonPrimitive) ?: JsonNull)
    put("unitCall", unitCall?.let(::JsonPrimitive) ?: JsonNull)
    put("unitWrap", unitWrap?.let(::JsonPrimitive) ?: JsonNull)
    // The estimate carries no allowances: it answers what the WORK would pay.
    put("allows", buildJsonArray { })
}

/** What the engine makes of a day. */
private fun JsonObject.toDayCalc(): DayCalc = DayCalc(
    basicPay = amount("basicPay"),
    otPay = amount("otPay"),
    dayGross = amount("dayGross"),
    workedMinutes = number("workedMin")?.toInt() ?: 0,
    lines = objects("lines").map { it.toPayLine() },
)

private fun String.parse(): JsonObject? =
    runCatching { EngineJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** Lenient on purpose: the bundle is another team's JSON, not this module's. */
private val EngineJson = Json { ignoreUnknownKeys = true; isLenient = true }
