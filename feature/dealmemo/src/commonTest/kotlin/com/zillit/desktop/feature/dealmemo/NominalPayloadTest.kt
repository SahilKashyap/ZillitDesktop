package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalCoding
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `/nominal-codes` body (ZL-21198): `raw_label` beside `row_id`, and only the rows that changed. */
class NominalPayloadTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    // The template overtime rows share row_id "overtime" and differ only by source.raw_label; the custom one is
    // keyed by its own id and has an empty label. Premiums and allowances are unambiguous.
    private val deal = DealDoc(
        json(
            """{"_id":"d1",
              "rates":{"nominal_code":"1001"},
              "overtimes":[
                {"row_id":"overtime","nominal_code":"1001","source":{"id":"overtime","raw_label":"camera_ot_1_2"}},
                {"row_id":"overtime","nominal_code":"1001","source":{"id":"overtime","raw_label":"non_camera_ot"}},
                {"row_id":"rule-1784550833116-5723","nominal_code":"","source":{"raw_label":""}}],
              "premiums":[{"row_id":"early_call","nominal_code":"1001"}],
              "allowances":[{"id":"a1","name":"Kit","nominal_code":"2000"}]}""",
        ).jsonObject,
    )

    private fun rows(body: JsonObject, surface: String): List<JsonObject> =
        (body[surface] as JsonArray).map { it.jsonObject }

    private fun row(rowId: String, rawLabel: String, code: String) = json(
        """{"row_id":"$rowId","raw_label":"$rawLabel","nominal_code":"$code"}""",
    )

    @Test
    fun `only the edited row goes, identified by its raw label and row id`() {
        val form = NominalForm(overrides = mapOf("ot:overtime#1" to "600-02"))
        val body = NominalCoding.payload(form, deal)

        assertEquals(listOf(row("overtime", "non_camera_ot", "600-02")), rows(body, "overtimes"))
    }

    @Test
    fun `template rows sharing a row id keep their own codes`() {
        val form = NominalForm(overrides = mapOf("ot:overtime" to "600-01", "ot:overtime#1" to "600-02"))
        val body = NominalCoding.payload(form, deal)

        assertEquals(
            listOf(row("overtime", "camera_ot_1_2", "600-01"), row("overtime", "non_camera_ot", "600-02")),
            rows(body, "overtimes"),
        )
    }

    @Test
    fun `a custom row goes with an empty raw label, keyed by its own row id`() {
        val form = NominalForm(overrides = mapOf("ot:rule-1784550833116-5723" to "600-09"))

        assertEquals(
            listOf(row("rule-1784550833116-5723", "", "600-09")),
            rows(NominalCoding.payload(form, deal), "overtimes"),
        )
    }

    @Test
    fun `a cleared code is an empty string, not an omitted row`() {
        val form = NominalForm(overrides = mapOf("ot:overtime" to ""))

        assertEquals(
            listOf(row("overtime", "camera_ot_1_2", "")),
            rows(NominalCoding.payload(form, deal), "overtimes"),
        )
    }

    @Test
    fun `untouched rows and surfaces stay out of the body, whitespace included`() {
        val form = NominalForm(overrides = mapOf("ot:overtime" to " 1001 ", "prem:early_call" to "1001"))
        val body = NominalCoding.payload(form, deal)

        assertFalse("overtimes" in body.keys)
        assertFalse("premiums" in body.keys)
        assertFalse("rates" in body.keys, "no basic_labour key, so none is sent")
    }

    @Test
    fun `an edited premium goes by row id with its (empty) raw label`() {
        val body = NominalCoding.payload(NominalForm(overrides = mapOf("prem:early_call" to "1500")), deal)

        assertEquals(listOf(row("early_call", "", "1500")), rows(body, "premiums"))
    }

    @Test
    fun `rates are patched whenever the key exists, and allowances still go whole by id`() {
        val hydrated = NominalCoding.hydrate(deal)
        val body = NominalCoding.payload(hydrated, deal)

        assertEquals("1001", body["rates"]?.jsonObject?.get("nominal_code")?.toString()?.trim('"'))
        assertEquals(1, rows(body, "allowances").size)
        assertEquals("a1", rows(body, "allowances").single()["id"]?.toString()?.trim('"'))
    }

    @Test
    fun `hydration hands back every saved code under the key the body diffs against`() {
        val repeated = DealDoc(
            json(
                """{"_id":"d2","overtimes":[
                    {"row_id":"overtime","nominal_code":"600-01","source":{"id":"overtime","raw_label":"ot_1_5"}},
                    {"row_id":"overtime","nominal_code":"600-01","source":{"id":"overtime","raw_label":"ot_2"}},
                    {"row_id":"overtime","source":{"id":"overtime","raw_label":"ot_3"}}]}""",
            ).jsonObject,
        )
        val form = NominalCoding.hydrate(repeated)
        assertTrue(NominalCoding.payload(form, repeated).isEmpty(), "nothing edited, nothing to send")

        val rows = NominalCoding.rowsFromDeal(repeated, form).filter { it.key.startsWith("ot:") }
        val edited = form.withValue(rows[1], "600-02").withValue(rows[2], "600-03")
        assertEquals(
            listOf(row("overtime", "ot_2", "600-02"), row("overtime", "ot_3", "600-03")),
            rows(NominalCoding.payload(edited, repeated), "overtimes"),
        )
    }
}
