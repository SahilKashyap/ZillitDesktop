@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewDraft
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCompany
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalCoding
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealProjectInfo
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.MyDealState
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState
import com.zillit.desktop.feature.dealmemo.ui.preview.CrewFormUi
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewState
import com.zillit.desktop.feature.dealmemo.ui.preview.GateMode
import com.zillit.desktop.feature.dealmemo.ui.preview.NominalsEditorState
import com.zillit.desktop.feature.dealmemo.ui.preview.ProductionRefs
import com.zillit.desktop.feature.dealmemo.ui.preview.RulesEditorState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The deal page, the crew form and the editors over one realistic UK deal. */
internal object DealPageFixtures {

    private fun doc(text: String) = DealDoc(Json.parseToJsonElement(text).jsonObject)

    val deal: DealDoc = doc(
        """{
          "_id":"deal-1","status":"issued","deal_reference":"DM-0042","user_id":"u-crew","created_at":1786000000000,
          "territory_union":{"territory_code":"uk","prod_entity":"co-1"},
          "crew_details":{
            "crew_name":"Amara Okafor","full_legal_name":"","preferred_name":"Amara Okafor",
            "department_id":"department_camera","designation_id":"designation_focus_puller_camera",
            "screen_credit_designation":"1st AC","crew_type":"shoot_crew","reports_to":"Director of Photography",
            "call_sheet_tier":"Tier 2","emp_status":"paye","unit":"unit-main","gender":"female","dob":631152000000,
            "email":"amara@example.com","mobile":"+447700900123","insurance_no":"QQ 12 34 56 C","tax_code":"1257L",
            "right_to_work":"UK Citizen / Settled Status",
            "passport_attachment":[{"media":"p/passport.pdf","bucket":"b","region":"eu-west-2","name":"amara-passport.pdf","content_type":"document","content_subtype":"pdf"}],
            "home_address":{"line1":"12 Baker Street","line2":"Flat 4","city":"London","state":"Greater London","postal_code":"NW1 6XE","country":"United Kingdom"},
            "emergency_contact_name":"Chidi Okafor","emergency_contact_number":"7700900456",
            "emergency_details":{"name":"Chidi Okafor","country_code":"GB","phone_number":"7700900456","email":"chidi@example.com","address":null},
            "representative_details":{"name":"","country_code":"","phone_number":"","email":"","address":null},
            "mandatory_fields":["dob","niNumber","homeAddress","bankSortCode"],
            "uk":{"starter_statement":null,"p45_previous_pay":24500.5,"p45_previous_tax":3100,"p45_leaving_date":1780012800000,"p45_previous_paye_ref":"123/AB456","student_loan_plan":"plan_2","pg_loan":null,"ni_category":"A","pension_status":"opt_in"}
          },
          "bank":{"account_holder_name":"Amara Okafor","name":"Barclays","account_number":"12345678","sort_code":"204891","iban_number":"","swift_code":"BARCGB22",
            "additional_details":[{"field":"Building society roll","value":"R-7781","field_type":"text"}]},
          "deal":{"type":"daily","billing_basis":"per_day","start_date":1786492800000,"end_date":1796492800000,"notice_period":"2_week"},
          "rates":{"contract_currency":"GBP","daily":{"rate":450},"weekly":{"rate":2250}},
          "overtimes":[
            {"row_id":"ot-1","nominal_code":"4410","source":{"id":"ot-1","label":"Overtime after 10 hours","rate_type":"multiplier","rate_amount":1.5,"basis":"hour"}},
            {"row_id":"ot-2","nominal_code":"","source":{"id":"ot-2","label":"6th day","rate_type":"multiplier","rate_amount":1.5,"basis":"day"}}
          ],
          "premiums":[{"row_id":"pr-1","nominal_code":"4420","source":{"id":"pr-1","label":"Night premium","rate_type":"flat","rate_amount":60,"basis":"day"}}]
        }""",
    )

    /** The same crew member engaged through their own company. */
    val loanOutDeal: DealDoc = DealDoc(
        JsonObject(
            deal.json + (
                "crew_details" to JsonObject(
                    (deal.crew ?: JsonObject(emptyMap())) + mapOf(
                        "emp_status" to JsonPrimitive("ltd"),
                        "loan_out_company" to Json.parseToJsonElement(
                            """{"name":"Okafor Camera Ltd","country_code":"+44","phone_number":"2079460000","email":"accounts@okafor.example","address":{"line1":"4 Wharf Road","line2":null,"city":"London","state":null,"postal_code":"N1 7GR","country":"United Kingdom"}}""",
                        ),
                    ),
                )
            ),
        ),
    )

    val production = ProductionRefs(
        project = DealProjectInfo(projectName = "The Long Winter", companyName = "Frostline Pictures Ltd"),
        webOrigin = "https://app.zillit.test",
        companies = listOf(DealCompany("co-1", "Frostline Pictures Ltd")),
        units = listOf(DealUnit("unit-main", "Main Unit")),
        countries = listOf(
            DealCountry("GG", "+44", "Guernsey"),
            DealCountry("GB", "+44", "United Kingdom"),
            DealCountry("IE", "+353", "Ireland"),
            DealCountry("US", "+1", "United States"),
            DealCountry("FR", "+33", "France"),
        ),
        loaded = true,
    )

    val coa = CoaState(
        loaded = true,
        accounts = listOf(
            DealCoaAccount("4410", "Camera crew overtime", "category"),
            DealCoaAccount("4420", "Camera crew premiums", "category"),
            DealCoaAccount("4430", "Camera crew holiday pay", "category"),
        ),
    )

    fun dealPage(): DealMemoUiState = DealMemoFixtures.shell(DealTab.Deals).copy(
        route = DealMemoRoute.Deal("deal-1", DealBadgeUnit.AllDeals),
        preview = DealPreviewState(dealId = "deal-1", embedded = false, from = DealBadgeUnit.AllDeals, deal = deal),
        production = production,
        coa = coa,
    )

    fun myDealEmbedded(): DealMemoUiState = DealMemoFixtures.shell(DealTab.MyDeal, DealMemoFixtures.crew).copy(
        myDeal = MyDealState(deal = deal, loaded = true),
        preview = DealPreviewState(dealId = "deal-1", embedded = true, from = DealBadgeUnit.MyDeal, deal = deal),
        production = production,
    )

    fun crewForm(
        step: Int,
        source: DealDoc = deal,
        edit: (CrewDraft) -> CrewDraft = { it },
        form: CrewFormUi = CrewFormUi(step = step),
    ): DealMemoUiState = DealMemoFixtures.shell(DealTab.MyDeal, DealMemoFixtures.crew).copy(
        route = DealMemoRoute.CompleteDetails,
        myDeal = MyDealState(deal = source, loaded = true),
        preview = DealPreviewState(
            dealId = source.id,
            embedded = true,
            from = DealBadgeUnit.MyDeal,
            deal = source,
            crewDraft = edit(CrewDraft.seed(source)),
            crewForm = form,
        ),
        production = production,
    )

    fun crewErrors(): DealMemoUiState = crewForm(
        step = 1,
        edit = { draft ->
            val emergency = draft.crewDetails["emergency_details"]?.jsonObject ?: JsonObject(emptyMap())
            draft.withCrew("email", JsonPrimitive("amara@example"))
                .withCrew("emergency_contact_number", JsonPrimitive("77"))
                .withCrew("emergency_details", JsonObject(emergency + ("email" to JsonPrimitive("chidi@"))))
                .withCrew("insurance_no", JsonPrimitive(""))
        },
        form = CrewFormUi(step = 1, submitAttempted = true, touched = setOf(CrewField.Email)),
    )

    fun crewDiscard(): DealMemoUiState = crewForm(
        step = 0,
        edit = { it.withCrew("preferred_name", JsonPrimitive("Amara O.")) },
        form = CrewFormUi(step = 0, discardPrompt = true),
    )

    fun crewLoanOut(): DealMemoUiState = crewForm(step = 4, source = loanOutDeal)

    fun notReady(): DealMemoUiState = myDealEmbedded().let { state ->
        state.copy(preview = state.preview?.copy(gate = GateMode.Send))
    }

    fun nominals(): DealMemoUiState = dealPage().let { state ->
        val form = NominalCoding.hydrate(deal)
        state.copy(
            preview = state.preview?.copy(nominals = NominalsEditorState(form = form, baseline = form.signature)),
        )
    }

    fun rulesGrid(): DealMemoUiState = dealPage().let { state ->
        var next = 0
        val rows = BulkRules.rows(BulkRules.fromDeal(deal)) { "row-${next++}" }
        state.copy(preview = state.preview?.copy(rules = RulesEditorState(rows = rows)))
    }
}
