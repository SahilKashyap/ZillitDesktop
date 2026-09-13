@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.rules.AgreementRuleImport
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleList
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleLists
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Import union rules" (`agreementRuleImport.js`), pinned by the web's own `agreementRuleImport.test.js`. */
class AgreementRuleImportTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private var sequence = 0

    private fun project(agreement: JsonObject?) =
        AgreementRuleImport.project(agreement) { list -> "nu-imp-${list.wire}-${++sequence}" }

    private fun RuleLists.of(list: RuleList): List<JsonObject> = lists[list].orEmpty()

    private fun RuleLists.all(): List<JsonObject> =
        listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Penalties).flatMap { of(it) }

    private fun List<JsonObject>.labels() = map { it["label"]?.jsonPrimitive?.content.orEmpty() }.sorted()

    private val agreement = json(
        """{
          "overtimes":{"rows":[
            {"id":"ot","label":"Overtime","rate_type":"multiplier","rate_amount":1.5,"is_enhancement":true,"basis":"hour"},
            {"id":"meal_penalty","label":"Meal Penalty","rate_type":"flat","flat":15,"basis":"event"},
            {"id":"broken_turnaround","label":"Broken turnaround","rate_type":"flat","rate_amount":200}]},
          "premiums":{"rows":[{"id":"sixth_day","label":"6th Day","multiplier":2,"is_enhancement":true}]},
          "turnaround":{"rows":[{"id":"short_turnaround","label":"Short turnaround","rate_type":"flat","rate_amount":100}]},
          "allowances":{"rows":[{"id":"production_fee","label":"Production fee","rate_type":"flat","rate_amount":50}]}
        }""",
    )

    @Test
    fun `non-penalty overtime goes to overtimes, penalties and turnarounds to penalties, premiums to premiums`() {
        val out = project(agreement)
        assertEquals(listOf("Overtime"), out.of(RuleList.Overtimes).labels())
        assertEquals(listOf("6th Day"), out.of(RuleList.Premiums).labels())
        assertEquals(listOf("Broken turnaround", "Meal Penalty", "Short turnaround"), out.of(RuleList.Penalties).labels())
        assertTrue(out.all().none { it["label"]?.jsonPrimitive?.content == "Production fee" }, "allowances have no non-union home")
        assertEquals(5, AgreementRuleImport.count(out))
    }

    @Test
    fun `every imported row is multiplicative, normalised, and freshly identified`() {
        val out = project(agreement)
        val rows = out.all()
        assertTrue(rows.all { it["is_enhancement"]?.jsonPrimitive?.content == "false" })
        val sixth = out.of(RuleList.Premiums).single()
        assertEquals("multiplier", sixth["rate_type"]?.jsonPrimitive?.content)
        assertEquals("2", sixth["rate_amount"]?.jsonPrimitive?.content)
        val meal = out.of(RuleList.Penalties).first { it["label"]?.jsonPrimitive?.content == "Meal Penalty" }
        assertEquals("flat", meal["rate_type"]?.jsonPrimitive?.content)
        assertEquals("15", meal["rate_amount"]?.jsonPrimitive?.content)

        val ids = rows.map { it["id"]?.jsonPrimitive?.content.orEmpty() }
        assertTrue(ids.all { it.isNotEmpty() })
        assertEquals(ids.size, ids.toSet().size, "agreement ids repeat, so each row gets its own")
        val again = project(agreement).all().map { it["id"]?.jsonPrimitive?.content }
        assertTrue(again.none { it in ids }, "two imports of one agreement never collide")
    }

    @Test
    fun `an imported row carries only the fields the non-union schema supports`() {
        val row = project(agreement).of(RuleList.Overtimes).single()
        assertEquals(
            setOf("basis", "cap_amount", "cap_type", "id", "is_enhancement", "label", "rate_amount", "rate_type", "triggers"),
            row.keys,
        )
    }

    @Test
    fun `nothing to import from a missing agreement or the empty-object list quirk`() {
        assertEquals(0, AgreementRuleImport.count(project(null)))
        assertEquals(0, AgreementRuleImport.count(project(json("""{"overtimes":{},"premiums":{"rows":[]},"turnaround":{}}"""))))
    }

    // -- real PACT/BECTU MMP rows: multi-entry, per-day-type, engine-padded triggers ------------------

    private val pad = json(
        """{"day_type":"SWD","after":null,"before":null,"less":null,"increment":null,"day_number":null,"consecutive":false,"meal":false,"meal_curtailed":false,"clock":false,"weekly":false,"camera":false,"bdr_min":null,"bdr_max":null}""",
    )

    private fun trg(overrides: String): String = JsonObject(pad + json(overrides)).toString()

    private fun rule(id: String, raw: String, label: String, type: String, amount: Number, basis: String, vararg triggers: String) =
        json("""{"id":"$id","raw_label":"$raw","label":"$label","rate_type":"$type","rate_amount":$amount,"basis":"$basis","triggers":[${triggers.joinToString(",")}]}""")

    private val pact = JsonObject(
        mapOf(
            "overtimes" to rows(
                rule("overtime", "camera_ot_1_2", "Camera OT — Hrs 1–2", "multiplier", 2, "hour", trg("""{"after":660,"before":780,"increment":15,"camera":true}"""), trg("""{"day_type":"CWD","after":600,"before":720,"increment":15,"camera":true}""")),
                rule("overtime", "non_camera_ot", "Non-Camera OT", "multiplier", 1.5, "hour", trg("""{"after":660,"increment":30}""")),
                rule("overtime", "ot_7th_day", "OT — 7th Day", "multiplier", 2, "hour", trg("""{"after":660,"increment":15,"day_number":7,"consecutive":true}""")),
                rule("meal_penalty", "meal_penalty", "Meal Penalty", "multiplier", 2, "hour", trg("""{"after":360,"increment":15,"meal":true}""")),
                rule("meal_break_curtailed", "meal_break_curtailed", "Meal Break Curtailed", "multiplier", 2, "hour", trg("""{"less":60,"increment":15,"meal_curtailed":true}""")),
            ),
            "premiums" to rows(
                rule("early_call", "pre_dawn", "Pre-Dawn", "multiplier", 2, "hour", trg("""{"day_type":null,"before":300,"clock":true}""")),
                rule("night_work", "night_work_past_midnight", "Night Work — Past Midnight", "flat", 25, "hour", trg("""{"day_type":null,"after":0,"increment":60,"clock":true}""")),
                rule("early_work", "night_work_early_call", "Night Work — Early Unit Call", "flat", 25, "hour", trg("""{"day_type":null,"before":180,"increment":60,"clock":true}""")),
                rule("6th_day", "6th_day", "6th Day", "multiplier", 1.5, "day", trg("""{"day_type":null,"day_number":6,"consecutive":true}""")),
                rule("7th_day", "7th_day", "7th Day", "multiplier", 2, "day", trg("""{"day_type":null,"day_number":7,"consecutive":true}""")),
                rule("holiday", "bank_holiday", "Bank Holiday", "multiplier", 1.5, "day", trg("""{"day_type":null,"day_kind":["bank_holiday"]}""")),
            ),
            "turnaround" to rows(
                rule("broken_turnaround", "broken_turnaround", "Broken Turnaround", "multiplier", 2, "hour", trg("""{"day_type":null,"less":660,"increment":30}""")),
            ),
        ),
    )

    private fun rows(vararg rows: JsonObject) = JsonObject(mapOf("rows" to JsonArray(rows.toList())))

    /** raw_label → the list it routes into, the classified template, and what the grid then detects. */
    private val expected = mapOf(
        "camera_ot_1_2" to Triple(RuleList.Overtimes, "camera_ot", "camera_ot"),
        "non_camera_ot" to Triple(RuleList.Overtimes, "ot", "ot"),
        "ot_7th_day" to Triple(RuleList.Overtimes, "ot_7th", "ot_7th"),
        "meal_penalty" to Triple(RuleList.Penalties, "meal_penalty", "meal_penalty"),
        "meal_break_curtailed" to Triple(RuleList.Penalties, "meal_curtailed", "meal_curtailed"),
        "pre_dawn" to Triple(RuleList.Premiums, "pre_dawn", "pre_dawn"),
        "night_work_past_midnight" to Triple(RuleList.Premiums, "night_work", "night_work"),
        // Early night work shares pre-dawn's trigger; the grid reads it as pre-dawn and the label carries the split.
        "night_work_early_call" to Triple(RuleList.Premiums, "night_work_early", "pre_dawn"),
        "6th_day" to Triple(RuleList.Premiums, "sixth_day", "sixth_day"),
        "7th_day" to Triple(RuleList.Premiums, "seventh_day", "seventh_day"),
        "bank_holiday" to Triple(RuleList.Premiums, "bank_holiday", "bank_holiday"),
        "broken_turnaround" to Triple(RuleList.Penalties, "broken_turnaround", "broken_turnaround"),
    )

    private fun pactRow(raw: String): JsonObject = listOf("overtimes", "premiums", "turnaround")
        .flatMap { block -> pact.getValue(block).jsonObject.getValue("rows").jsonArray.map { it.jsonObject } }
        .first { it["raw_label"]?.jsonPrimitive?.content == raw }

    @Test
    fun `each real row classifies to its template and emits a trigger the grid reads back`() {
        expected.forEach { (raw, expectation) ->
            val (list, templateId, gridType) = expectation
            val row = pactRow(raw)
            assertEquals(templateId, RuleTemplate.classify(row, list).id, raw)
            val trigger = AgreementRuleImport.classify(row, list)
            assertEquals(gridType, RuleTemplate.match(JsonArray(listOf(trigger)))?.id, raw)
        }
    }

    @Test
    fun `the real rows collapse to one canonical trigger each and route into the right lists`() {
        val out = project(pact)
        assertEquals(12, out.all().size)
        assertTrue(out.all().all { (it["triggers"] as? JsonArray)?.size == 1 })
        assertEquals(listOf("Camera OT — Hrs 1–2", "Non-Camera OT", "OT — 7th Day"), out.of(RuleList.Overtimes).labels())
        assertEquals(
            listOf("6th Day", "7th Day", "Bank Holiday", "Night Work — Early Unit Call", "Night Work — Past Midnight", "Pre-Dawn"),
            out.of(RuleList.Premiums).labels(),
        )
        assertEquals(listOf("Broken Turnaround", "Meal Break Curtailed", "Meal Penalty"), out.of(RuleList.Penalties).labels())
        val camera = out.of(RuleList.Overtimes).first { it["label"]?.jsonPrimitive?.content == "Camera OT — Hrs 1–2" }
        assertEquals(
            json("""{"after":660,"camera":true,"increment":15}"""),
            (camera["triggers"] as JsonArray).single(),
            "the first day type's trigger, without its padding",
        )
        assertEquals("uncapped", camera["cap_type"]?.jsonPrimitive?.content)
    }
}
