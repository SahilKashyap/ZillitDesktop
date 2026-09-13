package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A phase row of the schedule: its label and its two form keys. */
data class PhaseRow(val label: String, val startKey: String, val endKey: String)

/**
 * Deal Structure's advisory rules (`Step4DealStructure.jsx`): phase and
 * custom-day range problems that warn but never block, and the day counts.
 */
object DealStructureRules {

    val PHASES = listOf(
        PhaseRow("Prep", "schedPrepStart", "schedPrepEnd"),
        PhaseRow("Shoot", "schedShootStart", "schedShootEnd"),
        PhaseRow("Wrap", "schedWrapStart", "schedWrapEnd"),
    )

    const val PHASE_BANNER = "Overlapping or out-of-bounds phases will produce incorrect cost estimates."

    /** The problems on each phase, keyed by its label — only while the schedule is on. */
    @Suppress("CyclomaticComplexMethod")
    fun phaseErrors(form: DealForm): Map<String, List<String>> {
        val prep = mutableListOf<String>()
        val shoot = mutableListOf<String>()
        val wrap = mutableListOf<String>()
        if (form.flag("schedOn")) {
            val dealStart = form.text("dealStart").ifEmpty { null }
            val dealEnd = form.text("dealEnd").ifEmpty { null }
            val ps = day(form.text("schedPrepStart"))
            val pe = day(form.text("schedPrepEnd"))
            val ss = day(form.text("schedShootStart"))
            val se = day(form.text("schedShootEnd"))
            val ws = day(form.text("schedWrapStart"))
            val we = day(form.text("schedWrapEnd"))
            if (ps != null && pe != null && pe < ps) prep += BEFORE_START
            if (ss != null && se != null && se < ss) shoot += BEFORE_START
            if (ws != null && we != null && we < ws) wrap += BEFORE_START
            if (pe != null && ss != null && ss <= pe) shoot += "Overlaps with Prep — must start after prep ends"
            if (se != null && ws != null && ws <= se) wrap += "Overlaps with Shoot — must start after shoot ends"
            // Only the first phase with a start, and the last with an end, is held against the deal's dates.
            day(dealStart)?.let { start ->
                val (phase, date) = if (ps != null) prep to ps else shoot to ss
                if (date != null && date < start) phase += BEFORE_DEAL_START
            }
            day(dealEnd)?.let { end ->
                val (phase, date) = when {
                    we != null -> wrap to we
                    se != null -> shoot to se
                    else -> prep to pe
                }
                if (date != null && date > end) phase += AFTER_DEAL_END
            }
        }
        return mapOf("Prep" to prep, "Shoot" to shoot, "Wrap" to wrap)
    }

    /** `customDayRangeErrors`: a reversed range, or one outside the deal's own dates. */
    fun customDayErrors(row: JsonObject, dealStart: String, dealEnd: String): List<String> {
        val start = day(text(row["start_date"]))
        val end = day(text(row["end_date"]))
        val ds = day(dealStart)
        val de = day(dealEnd)
        return buildList {
            if (start != null && end != null && end < start) add(BEFORE_START)
            if (ds != null && start != null && start < ds) add("Starts before the deal start date")
            if (de != null && end != null && end > de) add("Ends after the deal end date")
        }
    }

    /** The custom-day banner: each troubled row by name, its problems joined. */
    fun customDayBanner(rows: List<JsonObject>, dealStart: String, dealEnd: String): String? =
        rows.mapIndexedNotNull { index, row ->
            val errors = customDayErrors(row, dealStart, dealEnd)
            if (errors.isEmpty()) {
                null
            } else {
                "${text(row["name"]).ifEmpty { "Custom day ${index + 1}" }}: ${errors.joinToString("; ")}"
            }
        }.joinToString(" · ").ifEmpty { null }

    /** The phase bounds a picker offers: its start's lower bound, and both ends' upper bound. */
    fun phaseStartMin(form: DealForm, index: Int): String? = when (index) {
        0 -> form.text("dealStart").ifEmpty { null }
        else -> BuilderSeeds.nextDay(form.text(PHASES[index - 1].endKey)).ifEmpty { form.text("dealStart") }.ifEmpty {
            null
        }
    }

    /** The end picker's lower bound: the day after this phase's start, else the day after the start's own bound. */
    fun phaseEndMin(form: DealForm, index: Int): String? {
        val start = form.text(PHASES[index].startKey)
        val base = start.ifEmpty { phaseStartMin(form, index).orEmpty() }
        return BuilderSeeds.nextDay(base).ifEmpty { null }
    }

    private fun day(value: String?): Long? = value?.takeIf { it.isNotEmpty() }?.let(PayloadParts::toEpoch)

    private fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        else -> Js.text(value)
    }

    private const val BEFORE_START = "End date is before start date"
    private const val BEFORE_DEAL_START = "Starts before deal start date"
    private const val AFTER_DEAL_END = "Ends after estimated deal end date"
}
