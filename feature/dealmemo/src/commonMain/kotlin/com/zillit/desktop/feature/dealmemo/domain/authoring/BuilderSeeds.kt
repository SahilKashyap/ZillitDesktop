package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/** What the project-amount conversion did, for the Allowances editor's banner. */
sealed interface AllowanceConversion {
    val from: String
    val to: String

    data class Converted(override val from: String, override val to: String, val rate: Double) : AllowanceConversion

    /** The contract currency has no exchange rate in Production Setup; amounts stay in [from]. */
    data class NoRate(override val from: String, override val to: String) : AllowanceConversion
}

/** A draft the leave guard's "Leave Without Saving" deletes. */
data class DiscardTarget(val id: String, val template: Boolean)

/**
 * The builder's seeds and small page rules (`DMTemplateBuilderPage.jsx`
 * B:1580-2600): each takes the form as it stands and answers the form the
 * web's effect would leave, so the view model decides only when they run.
 */
@Suppress("TooManyFunctions") // One seed per effect the web page runs.
object BuilderSeeds {

    private const val CENTS = 100.0

    /**
     * `fromPSAllowance` / `fromPSRental`: a Production Setup row as an editable
     * deal row, carrying its original amounts (`_ps_*`) and the amounts last
     * written automatically (`_auto_*`) so a currency switch can re-convert
     * rows nobody touched.
     */
    fun psRow(row: JsonObject, rental: Boolean, fallbackId: () -> String): JsonObject {
        fun amount(key: String) = row[key]
            ?.takeUnless(Js::isNullishOrEmpty)
            ?.let(Js::text)
            .orEmpty()
        val rate = amount("amount")
        val cap = amount("cap_amount")
        return buildJsonObject {
            put("id", row["id"]?.takeIf(Js::truthy) ?: JsonPrimitive(fallbackId()))
            put("name", row["name"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
            put("on", !isFalse(row["enable"]) && !isFalse(row["on"]))
            put("rate", rate)
            put("_ps_rate", rate)
            put("_auto_rate", rate)
            put("basis", row["basis"]?.takeIf(Js::truthy) ?: JsonPrimitive(if (rental) "week" else "day"))
            put("applies_to", row["applies_to"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
            if (rental) {
                put("cap_type", row["cap_type"]?.takeIf(Js::truthy) ?: JsonPrimitive("uncapped"))
                put("cap_amount", cap)
                put("_ps_cap", cap)
                put("_auto_cap", cap)
            }
            put("nominal", row["nominal_code"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
        }
    }

    /**
     * `mergeOverPS(presets, saved)`: Production Setup's rows in their order,
     * each overlaid by the form's row of the same id, then the form's own rows
     * (an id-less row is dropped).
     */
    fun mergeOverPresets(presets: List<JsonObject>, saved: List<JsonElement>): JsonArray {
        val savedRows = saved.mapNotNull { it as? JsonObject }
        val savedById = savedRows.associateBy { it["id"]?.let(::idKey) }
        val presetIds = presets.mapNotNull { it["id"]?.let(::idKey) }.toSet()
        val merged = presets.map { preset ->
            savedById[preset["id"]?.let(::idKey)]?.let { JsonObject(preset + it) } ?: preset
        }
        val customs = savedRows.filter { Js.truthy(it["id"]) && idKey(it.getValue("id")) !in presetIds }
        return JsonArray(merged + customs)
    }

    /** The standard conditions not on the form yet, put in front — null when there is nothing to add. */
    fun withStandardConditions(form: DealForm, standard: List<JsonObject>): DealForm? {
        val texts = conditionTexts(standard)
        val existing = form.list("customConditions")
        val seen = existing.map { if (it is JsonNull) "" else Js.text(it).trim() }.toSet()
        val fresh = texts.filterNot { it in seen }
        if (fresh.isEmpty()) return null
        return form.with("customConditions", JsonArray(fresh.map { JsonPrimitive(it) } + existing))
    }

    /** The project's clause texts, trimmed, blanks dropped. */
    fun conditionTexts(standard: List<JsonObject>): List<String> =
        standard.map { row -> row["condition"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty().trim() }.filter {
            it.isNotEmpty()
        }

    /** The payment currency seed: the project default replaces the untouched `GBP`. */
    fun withPaymentCurrency(form: DealForm, projectDefault: String): DealForm =
        if (form.text("paymentCurrency") == INITIAL_CURRENCY) form.with("paymentCurrency", projectDefault) else form

    /**
     * The schedule seed: empty deal dates and phases from the project schedule,
     * custom days when the form has none, and the phase toggle on once any
     * phase date is set.
     */
    fun withProjectSchedule(form: DealForm, schedule: JsonObject): DealForm {
        val patch = LinkedHashMap<String, JsonElement>()
        fun fill(key: String, value: JsonElement?) {
            if (form.text(key).isEmpty() && Js.truthy(value)) patch[key] = JsonPrimitive(dateInput(value))
        }
        fun phase(name: String) = schedule[name] as? JsonObject
        fill("dealStart", schedule["start_date"])
        fill("dealEnd", schedule["end_date"])
        listOf("prep" to "Prep", "shoot" to "Shoot", "wrap" to "Wrap").forEach { (key, name) ->
            fill("sched${name}Start", phase(key)?.get("start_date"))
            fill("sched${name}End", phase(key)?.get("end_date"))
        }
        if (form.list("customDays").isEmpty()) patch["customDays"] = customDaysFromProduction(schedule["custom_days"])
        val next = form.with(patch)
        val anyPhase = PHASE_KEYS.any { next.text(it).isNotEmpty() }
        return if (anyPhase) next.with("schedOn", true) else next
    }

    /**
     * The deal pages' engagement dates when neither is set: prep's start, else
     * shoot's, else the schedule's; wrap's end, else shoot's, else the schedule's.
     */
    fun engagementDates(schedule: JsonObject?): Pair<String, String> {
        fun date(vararg candidates: JsonElement?): String =
            candidates.firstOrNull { it != null && it !is JsonNull }?.let(::dateInput).orEmpty()
        val prep = schedule?.get("prep") as? JsonObject
        val shoot = schedule?.get("shoot") as? JsonObject
        val wrap = schedule?.get("wrap") as? JsonObject
        return date(prep?.get("start_date"), shoot?.get("start_date"), schedule?.get("start_date")) to
            date(wrap?.get("end_date"), shoot?.get("end_date"), schedule?.get("end_date"))
    }

    /** `customDaysFromProduction`: the production's named ranges as editable rows; `{}` reads as none. */
    fun customDaysFromProduction(value: JsonElement?): JsonArray = JsonArray(
        (value as? JsonArray).orEmpty().map { day ->
            val row = day as? JsonObject
            buildJsonObject {
                put("name", row?.get("name")?.takeUnless { it is JsonNull } ?: JsonPrimitive(""))
                put("start_date", dateInput(row?.get("start_date")))
                put("end_date", dateInput(row?.get("end_date")))
            }
        },
    )

    /** `toCustomDayInput`: an epoch or ISO value as its UTC `YYYY-MM-DD`; blank or unreadable is empty. */
    fun dateInput(value: JsonElement?): String {
        if (value == null || value is JsonNull) return ""
        if (value is JsonPrimitive && value.isString && value.content.isEmpty()) return ""
        val epoch = SetupSeeding.epochOf(value) ?: return ""
        return PayloadParts.fromEpoch(JsonPrimitive(epoch)).ifEmpty { if (epoch == 0L) "1970-01-01" else "" }
    }

    /** `resolveContractCurrency`: non-union deals pay in the project's currency, union deals in the territory's. */
    fun contractCurrency(union: String, territory: String, projectDefault: String?): String =
        if (isNonUnionId(union)) {
            projectDefault.orEmpty()
        } else {
            TerritoryCatalogue.defaultCurrency(territory)?.takeIf { it.isNotEmpty() } ?: projectDefault.orEmpty()
        }

    /**
     * The project-amount conversion: seeded rows still showing their automatic
     * amount are re-derived from the Production Setup original — converted
     * when the contract currency differs and has a rate, restored otherwise.
     */
    @Suppress("CyclomaticComplexMethod")
    fun convertSeededRows(
        form: DealForm,
        from: String,
        to: String,
        rate: Double?,
    ): Pair<DealForm, AllowanceConversion?> {
        val differs = from.isNotEmpty() && to.isNotEmpty() && from != to
        val applied = rate.takeIf { differs }
        fun target(original: JsonElement): String {
            val text = if (original is JsonNull) "" else Js.text(original)
            if (text.isEmpty() || applied == null) return text
            return convertAmount(text, applied)?.let(Js::number) ?: text
        }
        fun convert(row: JsonObject): JsonObject {
            val psRate = row["_ps_rate"]?.takeUnless { it is JsonNull }
            val psCap = row["_ps_cap"]?.takeUnless { it is JsonNull }
            if (psRate == null && psCap == null) return row
            var next = row
            if (psRate != null && text(row["rate"]) == text(row["_auto_rate"])) {
                val t = target(psRate)
                if (t != text(row["rate"])) {
                    next = JsonObject(next + mapOf("rate" to JsonPrimitive(t), "_auto_rate" to JsonPrimitive(t)))
                } else if (text(next["_auto_rate"]) != t) {
                    next = JsonObject(next + ("_auto_rate" to JsonPrimitive(t)))
                }
            }
            if (psCap != null && text(row["cap_amount"]) == text(row["_auto_cap"])) {
                val t = target(psCap)
                if (t != text(next["cap_amount"])) {
                    next = JsonObject(next + mapOf("cap_amount" to JsonPrimitive(t), "_auto_cap" to JsonPrimitive(t)))
                }
            }
            return next
        }
        val next = form.with(
            mapOf(
                "allowances" to JsonArray(form.list("allowances").map { (it as? JsonObject)?.let(::convert) ?: it }),
                "rentals" to JsonArray(form.list("rentals").map { (it as? JsonObject)?.let(::convert) ?: it }),
            ),
        )
        val info = when {
            !differs -> null
            applied != null -> AllowanceConversion.Converted(from, to, applied)
            else -> AllowanceConversion.NoRate(from, to)
        }
        return next to info
    }

    /** `convertPsAmount`: the amount times the rate to 2 dp; a rate of 1, 0 or less leaves it as it is. */
    fun convertAmount(amount: String, rate: Double): Double? {
        if (amount.isBlank()) return null
        val n = amount.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        if (!rate.isFinite() || rate <= 0 || rate == 1.0) return n
        return (n * rate * CENTS).roundToLong() / CENTS
    }

    /** Production Setup's bureaus as setup rows — the legacy `{bureau}` object reads as one row. */
    fun bureauRows(settings: ProjectSettingsView): List<BureauRow> {
        val stored = settings.slice(ProjectSettingsView.PAYROLL_BUREAU)
        val rows: List<JsonObject> = when (stored) {
            is JsonArray -> stored.mapNotNull { it as? JsonObject }
            is JsonObject -> if (Js.truthy(stored["bureau"])) {
                listOf(buildJsonObject {
                    put("id", "bureau-1")
                    put("title", Js.text(stored.getValue("bureau")))
                    put("description", "")
                })
            } else {
                emptyList()
            }
            else -> emptyList()
        }
        return rows.mapIndexed { index, row ->
            BureauRow(
                id = row["id"]?.takeIf(Js::truthy)?.let(Js::text) ?: "bureau-${index + 1}",
                title = row["title"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty(),
                description = row["description"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty(),
            )
        }
    }

    /** `missingSetupFields()`: what Save Setup refuses without. Budget Band is never required here. */
    fun missingSetupFields(form: DealForm, settings: ProjectSettingsView): List<String> = buildList {
        if (form.text("productionEntity").trim().isEmpty()) add("Production Entity")
        if (isNonUnionId(form.text("union"))) {
            if (!settings.hasNonUnionRules) add("Non-Union Pay Rules")
            // The three default day types always stand in for an empty project list.
        } else {
            if (form.text("territory").trim().isEmpty()) add("Territory")
            if (form.text("union").trim().isEmpty()) add("Agreement")
        }
    }

    /** `setupKindLabel`: `Non-Union Setup`, `Union Setup for United Kingdom`, or `Union Setup`. */
    fun setupKindLabel(form: DealForm): String = when {
        isNonUnionId(form.text("union")) -> "Non-Union Setup"
        else -> TerritoryCatalogue.label(form.text("territory"))?.let { "Union Setup for $it" } ?: "Union Setup"
    }

    /**
     * `autosavedDraftToDiscard`: only a draft this visit's autosave created is
     * deleted — never one the page was opened on, and never one the user saved.
     */
    fun discardTarget(
        template: Boolean,
        preOwnedId: String?,
        autosavedId: String?,
        userPersisted: Boolean,
    ): DiscardTarget? {
        if (preOwnedId != null || userPersisted || autosavedId == null) return null
        return DiscardTarget(autosavedId, template)
    }

    /**
     * `resolveTerritoryUpdate`: the territory a production entity's country
     * maps to — empty when the entity was cleared, null when nothing changes.
     */
    fun territoryForEntity(entityId: String, companies: List<JsonObject>, countries: List<DealCountry>): String? {
        if (entityId.isEmpty()) return ""
        val company = companies.firstOrNull { row ->
            row["id"]?.takeUnless { it is JsonNull }?.let(Js::text) == entityId
        }
        val country = company?.get("country")?.takeIf(Js::truthy)?.let(Js::text) ?: return null
        val iso = countries.firstOrNull { it.name == country }?.code ?: return null
        val target = if (iso == "GB") "uk" else iso.lowercase()
        return target.takeIf { TerritoryCatalogue.territory(it) != null }
    }

    /** Inclusive days between two `YYYY-MM-DD` inputs; 0 when either is missing or they run backwards. */
    fun inclusiveDays(start: String, end: String): Int {
        val from = PayloadParts.toEpoch(start) ?: return 0
        val to = PayloadParts.toEpoch(end) ?: return 0
        if (to < from) return 0
        return ((to - from) / DAY_MILLIS).toInt() + 1
    }

    /** The day after a `YYYY-MM-DD` input, for a date picker's lower bound; empty when unreadable. */
    fun nextDay(date: String): String {
        val epoch = PayloadParts.toEpoch(date) ?: return ""
        return PayloadParts.fromEpoch(JsonPrimitive(epoch + DAY_MILLIS))
    }

    private fun text(value: JsonElement?): String = when (value) {
        null -> "undefined"
        JsonNull -> "null"
        else -> Js.text(value)
    }

    private fun idKey(value: JsonElement): String = if (value is JsonNull) "null" else Js.text(value)

    private fun isFalse(value: JsonElement?): Boolean =
        value is JsonPrimitive && !value.isString && value.content == "false"

    private const val INITIAL_CURRENCY = "GBP"
    private const val DAY_MILLIS = 86_400_000L
    private val PHASE_KEYS =
        listOf("schedPrepStart", "schedPrepEnd", "schedShootStart", "schedShootEnd", "schedWrapStart", "schedWrapEnd")
}
