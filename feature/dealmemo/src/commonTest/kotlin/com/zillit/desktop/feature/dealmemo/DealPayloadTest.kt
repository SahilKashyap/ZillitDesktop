@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealHydration
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealPayload
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealPayloadRead
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadContext
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleLines
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `toDealMemoPayload` / `fromDealMemoPayload`, pinned by the web's own test cases. */
class DealPayloadTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun form(text: String) = DealForm(json(text))

    private fun emit(form: DealForm, ctx: PayloadContext = PayloadContext()) = DealPayload.build(form, ctx)

    private fun load(text: String): DealForm =
        DealForm(JsonObject(DealPayloadRead.read(json(text), DepartmentCatalogue())))

    private fun JsonElement?.at(vararg path: String): JsonElement? =
        path.fold(this) { node, key -> (node as? JsonObject)?.get(key) }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    // -- rates ----------------------------------------------------------------------------------------

    @Test
    fun `daily hours come from the scale, else the typed hours, else zero`() {
        assertEquals(
            "9",
            emit(form("""{"dealType":"boxrental","dayRate":"100","basicWorkingHoursPerDay":"9"}"""))
                .at("rates", "daily", "hrs")
                .str(),
        )
        val scale =
            PayloadContext(selectedUnion = json("""{"basic_rate_details":{"daily":{"base_rate":100,"work_hrs":10}}}"""))
        assertEquals(
            "10",
            emit(form("""{"dealType":"weekly","dayRate":"100","basicWorkingHoursPerDay":"9"}"""), scale)
                .at("rates", "daily", "hrs")
                .str(),
        )
        assertEquals("0", emit(form("""{"dealType":"weekly","dayRate":"100"}""")).at("rates", "daily", "hrs").str())
    }

    @Test
    fun `buy-out daily rate persists, and flat fees zero the day and week`() {
        val out = emit(
            form(
                """{"dealType":"buyout","buyoutRate":"2000","buyoutCovers":"10","buyoutDailyRate":"400","dayRate":"350"}""",
            ),
        )
        assertEquals("400", out.at("rates", "buyout_daily_rate").str())
        assertEquals("0", out.at("rates", "daily", "rate").str())
        assertEquals(
            JsonNull,
            emit(form("""{"dealType":"buyout","buyoutRate":"2000"}""")).at("rates", "buyout_daily_rate"),
        )
        assertEquals(JsonNull, out.at("deal", "prep", "start_date"))
    }

    @Test
    fun `the hourly rate prefers the scale, then the day rate over its hours`() {
        val hours = PayloadContext(selectedUnion = json("""{"basic_rate_details":{"daily":{"work_hrs":10}}}"""))
        assertEquals(
            "25.5",
            emit(form("""{"dealType":"weekly","dayRate":"255"}"""), hours).at("rates", "hr_rate").str(),
        )
        val published = PayloadContext(resolvedRate = json("""{"hourly":[{"base_rate":"31.2","work_hrs":1}]}"""))
        assertEquals(
            "31.2",
            emit(form("""{"dealType":"weekly","dayRate":"255"}"""), published).at("rates", "hr_rate").str(),
        )
    }

    @Test
    fun `the rate card entry matching the agreement's hours wins`() {
        val ctx = PayloadContext(
            selectedUnion = json("""{"basic_rate_details":{"daily":{"work_hrs":11}}}"""),
            resolvedRate = json(
                """{"daily":[{"base_rate":300,"work_hrs":10},{"base_rate":330,"work_hrs":11,"day_type":"SWD"}]}""",
            ),
        )
        assertEquals(
            "11",
            emit(form("""{"dealType":"weekly","dayRate":"330"}"""), ctx).at("rates", "daily", "hrs").str(),
        )
        assertEquals("30", emit(form("""{"dealType":"weekly","dayRate":"330"}"""), ctx).at("rates", "hr_rate").str())
    }

    @Test
    fun `hydration reads rates back as strings`() {
        assertEquals("9", load("""{"rates":{"daily":{"rate":100,"hrs":9}}}""").text("basicWorkingHoursPerDay"))
        assertEquals("400", load("""{"rates":{"buyout_daily_rate":400}}""").text("buyoutDailyRate"))
        val empty = load("""{"rates":{}}""")
        assertEquals("", empty.text("basicWorkingHoursPerDay"))
        assertEquals("", empty.text("buyoutDailyRate"))
        assertEquals("both", DealPayloadRead.buyoutMode(JsonPrimitive(2000), JsonPrimitive(400)))
        assertEquals("daily", DealPayloadRead.buyoutMode(JsonNull, JsonPrimitive(400)))
        assertEquals("weekly", DealPayloadRead.buyoutMode(null, JsonPrimitive(" ")))
    }

    // -- rule overrides ---------------------------------------------------------------------------------

    private val agreement = json(
        """{"overtimes":{"rows":[
              {"id":"ot_std","label":"Standard OT","rate_type":"multiplier","rate_amount":1.5,"min":12,"max":40},
              {"id":"meal_penalty","label":"Meal Penalty","rate_type":"flat","rate_amount":10}]},
            "premiums":{"rows":[{"id":"sixth_day","label":"Sixth Day","rate_type":"multiplier","rate_amount":2}]},
            "turnaround":{"rows":[{"id":"broken_turnaround","label":"Broken Turnaround","rate_type":"multiplier","rate_amount":0.5}]}}""",
    )

    private fun source(out: JsonObject, list: String, id: String): JsonObject? =
        out[list]?.jsonArray?.map { it.jsonObject["source"]?.jsonObject }?.firstOrNull { it?.get("id").str() == id }

    @Test
    fun `overrides are ignored while the customiser is off`() {
        val out = emit(
            form(
                """{"union":"pact-bectu-mmp","dealType":"daily","rulesCustomized":false,"ruleOverrides":{"ot_std":{"rate_amount":9.9}}}""",
            ),
            PayloadContext(agreement),
        )
        assertEquals("false", out["rules_customized"].str())
        assertEquals("1.5", source(out, "overtimes", "ot_std")?.get("rate_amount").str())
    }

    @Test
    fun `overrides patch the source rows, penalties split out and still overridable`() {
        val out = emit(
            form(
                """{"union":"pact-bectu-mmp","dealType":"daily","rulesCustomized":true,"ruleOverrides":{
                    "ot_std":{"rate_amount":2.0,"max":55},"sixth_day":{"rate_amount":2.5},
                    "broken_turnaround":{"rate_amount":0.75},"meal_penalty":{"rate_amount":15}}}""",
            ),
            PayloadContext(agreement),
        )
        val otStd = source(out, "overtimes", "ot_std")
        assertEquals("2", otStd?.get("rate_amount").str())
        assertEquals("55", otStd?.get("max").str())
        assertEquals("12", otStd?.get("min").str())
        assertEquals("multiplier", otStd?.get("rate_type").str())
        assertEquals("2.5", source(out, "premiums", "sixth_day")?.get("rate_amount").str())
        assertEquals("0.75", source(out, "turnarounds", "broken_turnaround")?.get("rate_amount").str())
        assertEquals("15", source(out, "penalties", "meal_penalty")?.get("rate_amount").str())
        assertNull(source(out, "overtimes", "meal_penalty"), "a penalty never stays in the overtimes")
    }

    @Test
    fun `an edited value survives save, reload and a re-save that races the materialise`() {
        val saved = emit(
            form(
                """{"union":"pact-bectu-mmp","dealType":"daily","rulesCustomized":true,"ruleOverrides":{"ot_std":{"min":25}}}""",
            ),
            PayloadContext(agreement),
        )
        val reloaded = DealPayloadRead.read(saved, DepartmentCatalogue())
        assertEquals(JsonNull, reloaded["rulesCustomized"])
        assertEquals("25", reloaded["ruleOverrides"].at("ot_std", "min").str())
        val again =
            emit(form("""{"union":"pact-bectu-mmp","dealType":"daily"}""").with(reloaded), PayloadContext(agreement))
        assertEquals("25", source(again, "overtimes", "ot_std")?.get("min").str())
    }

    @Test
    fun `non-union deals always carry the project's pay breakdown`() {
        val nub = json(
            """{"overtimes":[{"id":"nu_ot","label":"Standard OT","rate_type":"multiplier","rate_amount":1.5}],
                "premiums":[{"id":"sixth_day","label":"6th Day","rate_type":"multiplier","rate_amount":1.5}],
                "penalties":[{"id":"meal_penalty","label":"Meal Penalty","rate_type":"flat","rate_amount":12}]}""",
        )
        val out = emit(
            form(
                """{"union":"non_union","dealType":"daily","rulesCustomized":false,"ruleOverrides":{"nu_ot":{"rate_amount":99}}}""",
            ),
            PayloadContext(nonUnionPaybreakdown = nub),
        )
        assertEquals(listOf("nu_ot"), out["overtimes"]?.jsonArray?.map { it.at("source", "id").str() })
        assertEquals(listOf("meal_penalty"), out["penalties"]?.jsonArray?.map { it.at("source", "id").str() })
        assertEquals("1.5", source(out, "overtimes", "nu_ot")?.get("rate_amount").str())
        assertEquals("false", out["rules_customized"].str())
    }

    @Test
    fun `occurrence keys give every repeat of an id its own nominal code`() {
        val repeats = json("""{"overtimes":{"rows":[{"id":"overtime","label":"A"},{"id":"overtime","label":"B"}]}}""")
        val out = emit(
            form(
                """{"union":"x","dealType":"weekly","nominalOverrides":{"ot:overtime":"7100","ot:overtime#1":"7200"}}""",
            ),
            PayloadContext(repeats),
        )
        assertEquals(listOf("7100", "7200"), out["overtimes"]?.jsonArray?.map { it.at("nominal_code").str() })
        assertEquals(
            listOf("overtime", "overtime#1", "night"),
            RuleLines.rowKeys(
                listOf(json("""{"id":"overtime"}"""), json("""{"id":"overtime"}"""), json("""{"id":"night"}""")),
            ),
        )
    }

    @Test
    fun `unknown codes are wrapped for the server to create, penalties never`() {
        val chart = setOf("4410")
        assertEquals("4410", PayloadParts.wrapNominal(" 4410 ", chart))
        assertEquals("[[9999]]", PayloadParts.wrapNominal("9999", chart))
        assertEquals("9999", PayloadParts.wrapNominal("9999", null))
        val out = emit(
            form(
                """{"union":"x","dealType":"weekly","nominalOverrides":{"penalty:meal_penalty":"9999","basic_labour":"9998"}}""",
            ),
            PayloadContext(agreement, coaCodes = chart),
        )
        assertEquals("9999", out["penalties"]?.jsonArray?.single()?.at("nominal_code").str())
        assertEquals("[[9998]]", out.at("rates", "nominal_code").str())
    }

    // -- nominal hydration -------------------------------------------------------------------------------

    @Test
    fun `saved codes hydrate under both the occurrence key and the stored spellings`() {
        val deal = json(
            """{"rates":{"nominal_code":"6000"},"holiday_pay":{"nominal_code":"6100"},
                "overtimes":[{"row_id":"ot_1_5x","source":{"id":"ot_1_5x"},"nominal_code":"7100"},
                             {"row_id":null,"source":{"id":"ot_2x"},"nominal_code":"7200"},
                             {"row_id":"Night Work","source":{"id":"night_work"},"nominal_code":"7300"},
                             {"row_id":"a","source":{"id":"a"},"nominal_code":""}],
                "premiums":[{"row_id":"r1","nominal_code":"1"}],"penalties":[{"row_id":"r1","nominal_code":"5"}],
                "fringes":{}}""",
        )
        val overrides = DealPayloadRead.read(deal, DepartmentCatalogue())["nominalOverrides"] as JsonObject
        assertEquals("7100", overrides["ot:ot_1_5x"].str())
        assertEquals("7200", overrides["ot:ot_2x"].str())
        assertEquals("7300", overrides["ot:night_work"].str())
        assertEquals("7300", overrides["ot:Night Work"].str())
        assertNull(overrides["ot:a"])
        assertEquals("1", overrides["prem:r1"].str())
        assertEquals("5", overrides["penalty:r1"].str())
        assertEquals("6000", overrides["basic_labour"].str())
        assertEquals("6100", overrides["holiday_pay"].str())
    }

    // -- notice ----------------------------------------------------------------------------------------------

    @Test
    fun `notice periods and reminders serialise as tokens`() {
        fun period(text: String) = emit(form(text)).at("deal", "notice_period").str()
        assertEquals("1_week", period("""{"noticeType":"1week"}"""))
        assertEquals("2_week", period("""{"noticeType":"2week"}"""))
        assertEquals("2_week", period("""{}"""), "anything unrecognised is the default two weeks")
        assertEquals("", period("""{"noticeType":"none"}"""))
        assertEquals("10_day", period("""{"noticeType":"custom","noticeCustomValue":"10","noticeCustomUnit":"day"}"""))
        assertEquals("", period("""{"noticeType":"custom","noticeCustomValue":"0","noticeCustomUnit":"week"}"""))
        assertEquals(
            "24_hour",
            emit(form("""{"noticeReminderValue":"24","noticeReminderUnit":"hour"}"""))
                .at("deal", "notice_reminder")
                .str(),
        )
        assertEquals(
            "",
            emit(form("""{"noticeType":"none","noticeReminderValue":"24"}""")).at("deal", "notice_reminder").str(),
        )
    }

    @Test
    fun `stored notices read back, legacy spellings included`() {
        assertEquals("1week", load("""{"deal":{"notice_period":"1_week"}}""").text("noticeType"))
        val four = load("""{"deal":{"notice_period":"4week"}}""")
        assertEquals(
            listOf("custom", "4", "week"),
            listOf(four.text("noticeType"), four.text("noticeCustomValue"), four.text("noticeCustomUnit")),
        )
        val free = load("""{"deal":{"notice_period":"3 weeks rolling"}}""")
        assertEquals(
            listOf("custom", "3", "week"),
            listOf(free.text("noticeType"), free.text("noticeCustomValue"), free.text("noticeCustomUnit")),
        )
        assertEquals("", load("""{"deal":{"notice_period":"statutory"}}""").text("noticeCustomValue"))
        assertEquals("none", load("""{}""").text("noticeType"))
        val reminder = load("""{"deal":{"notice_reminder":"2_day"}}""")
        assertEquals(
            listOf("2", "day"),
            listOf(reminder.text("noticeReminderValue"), reminder.text("noticeReminderUnit")),
        )
        assertEquals("3 Weeks", MemoFormat.durationToken("3_week"))
        assertEquals("Statutory minimum", MemoFormat.durationToken("statutory"))
    }

    // -- crew ----------------------------------------------------------------------------------------------

    @Test
    fun `reports-to serialises from the pick and hydrates back into it`() {
        assertEquals(
            "N/A",
            emit(form("""{"reportsToType":"N/A","reportsTo":""}""")).at("crew_details", "reports_to").str(),
        )
        assertEquals(
            "HOD",
            emit(form("""{"reportsToType":"HOD","reportsTo":""}""")).at("crew_details", "reports_to").str(),
        )
        assertEquals(
            "Director of Photography",
            emit(form("""{"reportsToType":"Other","reportsTo":"Director of Photography"}"""))
                .at("crew_details", "reports_to")
                .str(),
        )
        assertEquals("N/A", load("""{"crew_details":{"reports_to":"N/A"}}""").text("reportsToType"))
        val other = load("""{"crew_details":{"reports_to":"Line Producer"}}""")
        assertEquals("Other", other.text("reportsToType"))
        assertEquals("Line Producer", other.text("reportsTo"))
    }

    @Test
    fun `contacts are always complete objects, dual-written, and never a string address`() {
        val out = emit(
            form(
                """{"emergencyContactName":"Jane Doe","emergencyContactNumber":"7700 900111","emergencyAddress":"12 Baker St"}""",
            ),
        )
        val cd = out["crew_details"]?.jsonObject
        assertEquals("Jane Doe", cd?.get("emergency_contact_name").str())
        assertEquals("Jane Doe", cd.at("emergency_details", "name").str())
        assertEquals("12 Baker St", cd.at("emergency_details", "address", "line1").str())
        listOf("home_address", "loan_out_company").forEach { key ->
            val address = if (key == "home_address") cd?.get(key) else cd.at(key, "address")
            assertEquals(PayloadParts.ADDRESS_KEYS.toSet(), (address as JsonObject).keys)
        }
        assertEquals(PayloadParts.ADDRESS_KEYS.toSet(), cd.at("representative_details", "address")?.jsonObject?.keys)
        val back = load(
            """{"crew_details":{"emergency_contact_name":"","emergency_details":{"name":"Jane","email":"j@x.io"}}}""",
        )
        assertEquals("Jane", back.text("emergencyContactName"), "flat first, but a blank flat value falls through")
    }

    @Test
    fun `external crew never carry a user, and the flag reads nested first`() {
        val out = emit(form("""{"isExternal":true,"userId":"u1"}"""))
        assertEquals(JsonNull, out["user_id"])
        assertEquals("true", out["is_external"].str())
        assertTrue(
            load("""{"crew_details":{"is_external":true},"is_external":false,"user_id":"u1"}""").flag("isExternal"),
        )
        assertTrue(load("""{}""").flag("isExternal"), "no flag and no user reads as external")
    }

    @Test
    fun `the UK block keeps 0, blanks to null, and the pension wish is never null`() {
        val zero = emit(form("""{"uk":{"p45_previous_pay":0,"p45_previous_tax":"0","pension_status":""}}"""))
            .at("crew_details", "uk")
        assertEquals("0", zero.at("p45_previous_pay").str())
        assertEquals("0", zero.at("p45_previous_tax").str())
        assertEquals("opt_in", zero.at("pension_status").str())
        val blank =
            emit(form("""{"uk":{"p45_previous_pay":"","p45_leaving_date":1723680000000}}""")).at("crew_details", "uk")
        assertEquals(JsonNull, blank.at("p45_previous_pay"))
        assertEquals("1723680000000", blank.at("p45_leaving_date").str())
        assertEquals("opt_in", load("""{"crew_details":{}}""").obj("uk")?.get("pension_status").str())
    }

    // -- conditions, day types, bank, documents ------------------------------------------------------------

    @Test
    fun `non-union day types seed the defaults and let the project override them`() {
        val defaults = emit(form("""{"union":"non_union"}""")).get("day_types")?.jsonArray?.map {
            it.at("day_type").str()
        }
        assertEquals(listOf("SWD", "CWD", "SCWD"), defaults)
        val project = listOf(
            json("""{"day_type":"SWD","work_min":660,"label":"11h day"}"""),
            json("""{"day_type":"Night","work_min":480}"""),
        )
        val merged =
            emit(form("""{"union":"non-union"}"""), PayloadContext(projectDayTypes = project))["day_types"]?.jsonArray
        assertEquals(listOf("SWD", "CWD", "SCWD", "Night"), merged?.map { it.at("day_type").str() })
        assertEquals("660", merged?.first().at("work_min").str())
        val union = emit(
            form("""{"union":"pact"}"""),
            PayloadContext(json("""{"day_types":[{"day_type":"SWD","work_min":"660"}]}""")),
        )["day_types"]?.jsonArray
        assertEquals("660", union?.single().at("work_min").str())
    }

    @Test
    fun `the bank omits an absent currency and every server id`() {
        val out = emit(
            form(
                """{"bank":{"name":"Barclays","currency":null,"id":"b1","additional_details":[{"field":"Roll","value":"R1"},{"field":" ","value":"x"}]}}""",
            ),
        ).get("bank")?.jsonObject
        assertFalse("currency" in out.orEmpty())
        assertFalse("id" in out.orEmpty())
        assertEquals(1, out?.get("additional_details")?.jsonArray?.size)
        assertEquals("text", out.at("additional_details")?.jsonArray?.single().at("field_type").str())
        assertTrue(PayloadParts.isBankEmpty(json("""{"currency":null,"additional_details":[]}""")))
        assertFalse(PayloadParts.isBankEmpty(json("""{"currency":{"code":"GBP"}}""")))
    }

    @Test
    fun `entitlements keep only enabled rows and read their rate type from the text`() {
        val out = emit(
            form(
                """{"currency":"EUR","allowances":[
                    {"id":"a1","name":"Per Diem","on":true,"rate":"£35.00","basis":"day"},
                    {"id":"a2","name":"Mileage","on":true,"rate":"45p/mile","basis":"mile","nominal":"4400"},
                    {"id":"a3","name":"Off","on":false,"rate":"10"}],
                   "rentals":[{"id":"r1","name":"Kit","on":true,"rate":"10%","basis":"week","cap_type":"capped","cap_amount":"£1,000"}]}""",
            ),
        )
        val allowances = out["allowances"]?.jsonArray
        assertEquals(2, allowances?.size)
        assertEquals("35", allowances?.get(0).at("amount").str())
        assertEquals("daily", allowances?.get(0).at("pay_frequency").str())
        assertEquals("per_mile", allowances?.get(1).at("rate_type").str())
        assertEquals("0.45", allowances?.get(1).at("amount").str())
        val kit = out["rentals"]?.jsonArray?.single()
        assertEquals("pct", kit.at("rate_type").str())
        assertEquals(JsonNull, kit.at("amount"))
        assertEquals("10", kit.at("rate_pct").str())
        assertEquals("1000", kit.at("cap_amount").str())
        assertEquals("EUR", kit.at("currency").str())
        val back = load("""{"rentals":[{"id":"r1","name":"Kit","rate_type":"pct","rate_pct":10,"enable":true}]}""")
            .objects("rentals")
            .single()
        assertEquals("10%", back["rate"].str())
        assertEquals("uncapped", back["cap_type"].str())
    }

    @Test
    fun `documents need an intact attachment and keep their source`() {
        val deal = json(
            """{"additional_documents":{"docs":[
                 {"_id":"ps1","system":true,"title":"NDA","media":"m","bucket":"b","region":"r"},
                 {"_id":"c1","source":"custom","document":{"name":"x.pdf","media":"m2","bucket":"b","region":"r"},"sign_required":false},
                 {"_id":"broken","title":"No file"}],
               "doc_settings":{"u_sign":true}}}""",
        )
        val docs = load(deal.toString()).objects("documents")
        assertEquals(listOf("ps1", "c1"), docs.map { it["id"].str() })
        assertEquals("ps", docs[0]["source"].str())
        assertEquals("ps1", docs[0]["ps_agreement_id"].str())
        assertEquals("x.pdf", docs[1]["title"].str())
        assertEquals("false", docs[1]["signRequired"].str())
        val again =
            emit(DealForm.INITIAL.with("documents", JsonArray(docs)))["additional_documents"].at("docs")?.jsonArray
        assertEquals("true", again?.get(0).at("system").str())
        assertEquals("m2", again?.get(1).at("media").str())
        assertEquals("m2", again?.get(1).at("document", "media").str())
    }

    @Test
    fun `dates cross as UTC midnight both ways`() {
        assertEquals(1_786_492_800_000L, PayloadParts.toEpoch("2026-08-12"))
        assertNull(PayloadParts.toEpoch("12/08/2026"))
        assertEquals("2026-08-12", PayloadParts.fromEpoch(JsonPrimitive(1_786_492_800_000L)))
        assertEquals("", PayloadParts.fromEpoch(JsonPrimitive(0)))
        val born = PayloadParts.toEpoch("1965-03-14")
        assertTrue(born != null && born < 0)
        assertEquals("1965-03-14", PayloadParts.fromEpoch(JsonPrimitive(born)))
        val round = load(emit(form("""{"completionDue":"2026-09-30"}""")).toString())
        assertEquals("2026-09-30", round.text("completionDue"))
    }

    @Test
    fun `hydration stamps the rate context and keeps saved entitlement rows with ids`() {
        val payload = json(
            """{"territory_union":{"agreement_identifier":"pact"},"crew_details":{"designation_identifier":"focus_puller"},
                "allowances":[{"id":"a1","name":"Per Diem","amount":35},{"name":"no id"}]}""",
        )
        val hydrated = DealHydration.form(payload, DepartmentCatalogue())
        assertEquals("pact|focus_puller||", hydrated.text("rateAutoKey"))
        assertEquals(listOf("a1"), hydrated.objects("allowances").map { it["id"].str() })
        assertEquals("GBP", hydrated.text("currency"), "INIT defaults fill what the payload never mentions")
    }

    @Test
    fun `using a setup drops the person, the bank and the scoping ids`() {
        val setup = json("""{"user_id":"u1","is_external":true,"bank":{"name":"B"},"department_id":"d","designation_id":"g",
            "crew_details":{"emp_status":"paye","crew_type":"shoot_crew","full_legal_name":"Someone"},"deal":{"type":"weekly"}}""")
        val stripped = DealHydration.stripForUse(setup)
        assertFalse(listOf("user_id", "bank", "department_id", "designation_id").any { it in stripped })
        assertEquals("false", stripped["is_external"].str())
        assertEquals(setOf("emp_status", "crew_type"), stripped["crew_details"]?.jsonObject?.keys)
        assertEquals("weekly", stripped.at("deal", "type").str())
    }
}
