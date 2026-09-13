package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.ProjectSection
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** A Deal Memo Setup section a setup page writes to the project, in the web's write order. */
enum class SetupSection(val key: String, val label: String, val project: ProjectSection) {
    AllowancesRentals("allowances_rentals", "Allowances & Rentals", ProjectSection.AllowancesRentals),
    StandardDealConditions(
        "standard_deal_conditions",
        "Standard Deal Conditions",
        ProjectSection.StandardDealConditions,
    ),
    PayrollBureau("payroll_bureau", "Payroll Bureau", ProjectSection.PayrollBureau),
    ProductionSchedule("production_schedule", "Production Schedule", ProjectSection.ProductionSchedule),
    AgreementsDocuments("agreements_documents", "Agreements & Documents", ProjectSection.AgreementsDocuments),
    ;

    companion object {
        /** The page's Global sections by section id — what Save Setup keeps in step with the project. */
        val GLOBAL: List<Pair<Int, SetupSection>> = listOf(
            5 to ProductionSchedule,
            7 to AllowancesRentals,
            8 to StandardDealConditions,
            11 to PayrollBureau,
        )
    }
}

/** A payroll bureau row on a setup page — kept beside the form, as the web keeps `setupBureaus`. */
data class BureauRow(val id: String, val title: String = "", val description: String = "")

/** How a seed treats sections the project already has. */
enum class SeedMode {
    /** Write a section only when the project's is empty and the form has something for it. */
    Seed,

    /** The form is the section's next state: every section is written, empty ones included. */
    Replace,
}

/**
 * `firstRunSetup.js`: the project-settings writes a saved setup seeds, and the
 * comparison that decides whether a Global section still matches the project.
 */
object SetupSeeding {

    /**
     * `firstRunPatches(form, settings, uploadedDocs, bureauRows, mode)`: the
     * section bodies to write, keyed by section. Documents are additive in
     * both modes — their API has no replace.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One block per section, in the web's order.
    fun patches(
        form: DealForm,
        settings: ProjectSettingsView,
        uploadedDocs: List<JsonObject>?,
        bureauRows: List<BureauRow>?,
        mode: SeedMode,
        now: Long,
    ): Map<SetupSection, JsonElement> {
        val replace = mode == SeedMode.Replace
        val out = LinkedHashMap<SetupSection, JsonElement>()

        if (replace || emptyAllowancesRentals(settings)) {
            val allowances = form.objects("allowances").filter { str(it["name"]).isNotEmpty() }.map {
                psRow(it, rental = false)
            }
            val rentals = form.objects("rentals").filter { str(it["name"]).isNotEmpty() }.map {
                psRow(it, rental = true)
            }
            if (replace || allowances.isNotEmpty() || rentals.isNotEmpty()) {
                out[SetupSection.AllowancesRentals] = buildJsonObject {
                    put("allowances", JsonArray(allowances))
                    put("rentals", JsonArray(rentals))
                }
            }
        }

        if (replace || emptyConditions(settings)) {
            val conditions = form.list("customConditions")
                .map { condition ->
                    str(
                        if (condition is JsonPrimitive && condition.isString) {
                            condition
                        } else {
                            (condition as? JsonObject)?.get("condition")
                        },
                    )
                }
                .filter { it.isNotEmpty() }
            if (replace || conditions.isNotEmpty()) {
                out[SetupSection.StandardDealConditions] = JsonArray(
                    conditions.mapIndexed { order, condition ->
                        buildJsonObject {
                            put("order", order)
                            put("condition", condition)
                        }
                    },
                )
            }
        }

        if (replace || emptyBureau(settings)) {
            val rows = bureauRows.orEmpty()
                .mapIndexed { index, row ->
                    BureauRow(row.id.ifEmpty { "bureau-$now-$index" }, row.title.trim(), row.description.trim())
                }
                .filter { it.title.isNotEmpty() }
            val title = form.text("bureau").trim()
            when {
                rows.isNotEmpty() -> out[SetupSection.PayrollBureau] = JsonArray(rows.map(::bureauJson))
                title.isNotEmpty() ->
                    out[SetupSection.PayrollBureau] = JsonArray(listOf(bureauJson(BureauRow("bureau-$now", title))))
                replace -> out[SetupSection.PayrollBureau] = JsonArray(emptyList())
            }
        }

        if (replace || emptySchedule(settings)) {
            fun phase(start: String, end: String) = epochOf(form[start]) to epochOf(form[end])
            val prep = phase("schedPrepStart", "schedPrepEnd")
            val shoot = phase("schedShootStart", "schedShootEnd")
            val wrap = phase("schedWrapStart", "schedWrapEnd")
            val customDays = form.objects("customDays").filter { str(it["name"]).isNotEmpty() }.map { day ->
                buildJsonObject {
                    put("name", str(day["name"]))
                    put("start_date", epochOf(day["start_date"]?.takeUnless { it is JsonNull } ?: day["start"]))
                    put("end_date", epochOf(day["end_date"]?.takeUnless { it is JsonNull } ?: day["end"]))
                }
            }
            val overallStart = epochOf(form["dealStart"])
            val overallEnd = epochOf(form["dealEnd"])
            fun set(value: Long?) = value != null && value != 0L
            val any = set(overallStart) || set(overallEnd) ||
                listOf(prep, shoot, wrap).any { set(it.first) || set(it.second) } || customDays.isNotEmpty()
            if (replace || any) {
                out[SetupSection.ProductionSchedule] = buildJsonObject {
                    put("start_date", overallStart ?: prep.first ?: shoot.first ?: wrap.first)
                    put("end_date", overallEnd ?: wrap.second ?: shoot.second ?: prep.second)
                    put("prep", phaseJson(prep))
                    put("shoot", phaseJson(shoot))
                    put("wrap", phaseJson(wrap))
                    put("custom_days", JsonArray(customDays))
                }
            }
        }

        if (settings.agreementDocuments.isEmpty()) {
            val docs = (uploadedDocs ?: form.objects("documents"))
                .filter { doc ->
                    Js.truthy(doc["attachment"]) && doc["source"]?.takeUnless(Js::isNullish)?.let(Js::text) != "ps"
                }
                .mapNotNull { doc ->
                    val attachment = doc["attachment"] as? JsonObject ?: return@mapNotNull null
                    val caption = attachment["caption"]?.takeIf(Js::truthy) ?: JsonPrimitive(str(doc["description"]))
                    JsonObject(attachment + ("caption" to caption))
                }
            if (docs.isNotEmpty()) out[SetupSection.AgreementsDocuments] = JsonArray(docs)
        }
        return out
    }

    /** `setupSectionChanged(payload, stored)`: different once ids, blanks and key order are ignored. */
    fun sectionChanged(payload: JsonElement?, stored: JsonElement?): Boolean =
        canon(payload ?: JsonNull).toString() != canon(stored ?: JsonNull).toString()

    /**
     * `canon`: objects lose `id`/`_id`, sort their keys and drop null or empty
     * values (0 and false stay); numbers print the way JavaScript prints them.
     */
    fun canon(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::canon))
        is JsonObject -> JsonObject(
            value.keys.filter { it != "id" && it != "_id" }.sorted().mapNotNull { key ->
                val canonical = canon(value.getValue(key))
                if (Js.isNullishOrEmpty(canonical)) null else key to canonical
            }.toMap(LinkedHashMap()),
        )
        is JsonPrimitive -> if (!value.isString && value !is JsonNull && value.doubleOrNull != null) {
            JsonPrimitive(Js.number(value.doubleOrNull ?: 0.0).let { text -> text.toLongOrNull() ?: text.toDouble() })
        } else {
            value
        }
    }

    fun emptyAllowancesRentals(settings: ProjectSettingsView): Boolean =
        settings.allowances.isEmpty() && settings.rentals.isEmpty()

    fun emptyConditions(settings: ProjectSettingsView): Boolean =
        (settings.slice(ProjectSettingsView.STANDARD_DEAL_CONDITIONS) as? JsonArray).orEmpty().isEmpty()

    /** The legacy shape was a bare object carrying one `bureau` string. */
    fun emptyBureau(settings: ProjectSettingsView): Boolean =
        when (val value = settings.slice(ProjectSettingsView.PAYROLL_BUREAU)) {
            is JsonObject -> str(value["bureau"]).isEmpty()
            is JsonArray -> value.isEmpty()
            else -> true
        }

    fun emptySchedule(settings: ProjectSettingsView): Boolean {
        val schedule = settings.productionSchedule ?: return true
        fun phase(key: String): Boolean = (schedule[key] as? JsonObject)?.let {
            Js.truthy(it["start_date"]) || Js.truthy(it["end_date"])
        } == true
        return !phase("prep") && !phase("shoot") && !phase("wrap") &&
            !Js.truthy(schedule["start_date"]) && !Js.truthy(schedule["end_date"]) &&
            (schedule["custom_days"] as? JsonArray).orEmpty().isEmpty()
    }

    /** `toPSAllowance` / `toPSRental`: the form row as Production Setup stores it. */
    private fun psRow(row: JsonObject, rental: Boolean): JsonObject = buildJsonObject {
        row["id"]?.let { put("id", it) }
        put("name", str(row["name"]))
        put("enable", !isFalse(row["on"]))
        put("amount", PayloadParts.number(amount(row["rate"])))
        put("basis", row["basis"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
        put("applies_to", row["applies_to"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
        if (rental) {
            put(
                "cap_type",
                if (row["cap_type"]?.takeUnless { it is JsonNull }?.let(Js::text) == "capped") "capped" else "uncapped",
            )
            put("cap_amount", PayloadParts.number(amount(row["cap_amount"])))
        }
        put("nominal_code", row["nominal"]?.takeIf(Js::truthy) ?: JsonPrimitive(""))
    }

    private fun bureauJson(row: BureauRow) = buildJsonObject {
        put("id", row.id)
        put("title", row.title)
        put("description", row.description)
    }

    private fun phaseJson(phase: Pair<Long?, Long?>) = buildJsonObject {
        put("start_date", phase.first)
        put("end_date", phase.second)
    }

    /** `toAmount`: commas dropped, blank or non-numeric is null. */
    private fun amount(value: JsonElement?): Double? {
        val text = str(value).replace(",", "")
        if (text.isEmpty()) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** `new Date(v).getTime()` for a date input, an ISO timestamp or an epoch; null when unreadable. */
    fun epochOf(value: JsonElement?): Long? = when {
        value == null || value is JsonNull -> null
        value is JsonPrimitive && !value.isString -> value.doubleOrNull?.toLong()
        value is JsonPrimitive -> PayloadParts.toEpoch(value.content)
        else -> null
    }

    private fun isFalse(value: JsonElement?): Boolean =
        value is JsonPrimitive && !value.isString && value.content == "false"

    /** `String(v ?? "").trim()`. */
    private fun str(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        else -> Js.text(value).trim()
    }

    /** A patch list as the toast names it: `Allowances & Rentals, Payroll Bureau`. */
    fun labels(sections: Collection<SetupSection>): String = sections.joinToString(", ") { it.label }

    internal fun bureauArray(rows: List<BureauRow>): JsonArray = buildJsonArray { rows.forEach { add(bureauJson(it)) } }
}
