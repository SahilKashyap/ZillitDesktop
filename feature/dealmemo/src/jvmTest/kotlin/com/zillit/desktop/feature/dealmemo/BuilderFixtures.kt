@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.DealCrewUser
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate
import com.zillit.desktop.feature.dealmemo.domain.authoring.BureauRow
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealHydration
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.authoring.ProjectSettingsView
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleAuthoring
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.CoveredDepartment
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRows
import com.zillit.desktop.feature.dealmemo.domain.rules.NonUnionPayBreakdown
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealProjectInfo
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.ProjectSettingsState
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.SetupHubState
import com.zillit.desktop.feature.dealmemo.ui.TemplatesState
import com.zillit.desktop.feature.dealmemo.ui.builder.AutosaveStatus
import com.zillit.desktop.feature.dealmemo.ui.builder.AutosaveUi
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderMode
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderReference
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderSection
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderValidation
import com.zillit.desktop.feature.dealmemo.ui.builder.DayTypesDraft
import com.zillit.desktop.feature.dealmemo.ui.builder.RuleImportState
import com.zillit.desktop.feature.dealmemo.ui.builder.RulesTarget
import com.zillit.desktop.feature.dealmemo.ui.builder.SetupPageState
import com.zillit.desktop.feature.dealmemo.ui.preview.RulesEditorState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The one-page builder and the Setup Hub over one realistic UK production. */
internal object BuilderFixtures {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** PACT/BECTU TV: a banded scale, a holiday-pay fringe, overtime, premiums and turnarounds. */
    private val agreement = json(
        """{
          "_identifier":"pact-bectu-tvda","id":"pact-bectu-tvda","name":"PACT/BECTU TV Drama Agreement","label":"PACT/BECTU TV Drama Agreement",
          "short_label":"PACT/BECTU TVDA","currency":"GBP",
          "basic_rate_details":{"daily":{"work_hrs":10},"weekly":{"work_hrs":50},"day_type":"SWD"},
          "pact":{"bands":[{"band":"1","label":"Band 1","threshold":"< £1.25m/hr","notes":"10hr day"},{"band":"2","label":"Band 2","threshold":"£1.25m–£3m/hr"}],
            "special_depts":[{"department_identifier":"department_camera","label":"Camera"}],"extra_contracted_hours":{"hrs":1,"multiplier":1}},
          "fringes":{"default":{"label":"PAYE fringes","note":"Employer costs on gross pay.","items":[
            {"id":"holiday_pay","label":"Holiday Pay","rate_type":"percentage","rate_amount":12.07,"basis":"weekly_gross","statutory":true},
            {"id":"ni","label":"Employer NI","rate_type":"percentage","rate_amount":15,"basis":"excess_threshold","statutory":true},
            {"id":"pension","label":"Pension","rate_type":"percentage","rate_amount":3,"basis":"qualifying_earnings","statutory":true}]}},
          "overtimes":{"note":"Overtime is paid in 15-minute increments.","rows":[
            {"id":"ot","label":"Overtime after 10 hours","rate_type":"multiplier","rate_amount":1.5,"basis":"hour","min":22.5,"max":60},
            {"id":"ot-camera","label":"Camera overtime","rate_type":"multiplier","rate_amount":2,"basis":"hour"}]},
          "premiums":{"rows":[{"id":"sixth","label":"6th day","rate_type":"multiplier","rate_amount":1.5,"basis":"day","triggers":[{"day_number":6,"consecutive":true}]},
            {"id":"night","label":"Night work","rate_type":"flat","rate_amount":60,"basis":"day","triggers":[{"clock":true,"after":1320}]}]},
          "turnaround":{"note":"11-hour rest between calls.","rows":[{"id":"bt","label":"Broken turnaround","rate_type":"flat","rate_amount":30,"basis":"hour","triggers":[{"less":660}]}]},
          "allowances":{"rows":[{"id":"travel","label":"Travel allowance","note":"Beyond 30 miles","rate_type":"flat","rate_amount":0.45,"basis":"mile"}]},
          "emp_statuses":[{"id":"paye","label":"PAYE"},{"id":"schedule_d","label":"Schedule D"},{"id":"ltd","label":"Limited company"}]
        }""",
    )

    private val rateCard = json(
        """{"_id":"rate-1","schedule_key":"10hr","status":"agreed","notes":"Band 1 scale for Focus Puller.",
            "daily":[{"base_rate":450,"min_rate":420,"max_rate":520,"work_hrs":10,"day_type":"SWD"}],
            "weekly":[{"base_rate":2250,"min_rate":2100,"work_hrs":50}],"hourly":[]}""",
    )

    private val settings = json(
        """{
          "companies":[{"id":"co-1","name":"Frostline Pictures Ltd","country":"United Kingdom"}],
          "project_currencies":{"default":"GBP","currencies":[{"code":"GBP","name":"British Pound","symbol":"£","exr":1},{"code":"EUR","name":"Euro","symbol":"€","exr":1.17}]},
          "day_types":[{"day_type":"CWD","work_min":540,"meal_break_min":0,"label":"Continuous day"},{"day_type":"LONG","work_min":720,"meal_break_min":60,"label":"Long day"}],
          "production":{
            "allowances_rentals":{"allowances":[{"id":"ps-1","name":"Per Diem","amount":35,"basis":"day","applies_to":"shoot","nominal_code":"4400"}],
              "rentals":[{"id":"ps-2","name":"Camera Kit","amount":250,"basis":"week","applies_to":"full_production","cap_type":"capped","cap_amount":1000}]},
            "standard_deal_conditions":[{"order":0,"condition":"Travel out of London paid at agreed rate"}],
            "payroll_bureau":[{"id":"b1","title":"Sargent-Disc","description":"Weekly PAYE"}],
            "agreements_documents":[{"_id":"doc-1","title":"Crew Handbook","description":"Health & safety and conduct","document":{"name":"crew-handbook.pdf","media":"m","bucket":"b","region":"r","content_subtype":"pdf"}}],
            "non_union_paybreakdown":{"overtimes":[{"id":"nu-ot","label":"OT ×1.5 after 10 hrs","rate_type":"multiplier","rate_amount":1.5,"basis":"hour","triggers":[{"after":600}]}],
              "premiums":[{"id":"nu-6","label":"6th Day Premium ×1.5","rate_type":"multiplier","rate_amount":1.5,"basis":"day","triggers":[{"day_number":6,"consecutive":true}]}],
              "penalties":[{"id":"nu-meal","label":"Meal Penalty £9.50 (6 hrs)","rate_type":"flat","rate_amount":9.5,"basis":"event","triggers":[{"meal":true,"after":360}]}]}
          }
        }""",
    )

    private val crewDirectory = listOf(
        DealCrewUser(
            "u1",
            "Amara Okafor",
            "accepted",
            "department_camera",
            "designation_focus_puller_camera",
            "camera_label",
            "focus_puller_label",
        ),
        DealCrewUser(
            "u2",
            "Ben Hartley",
            "accepted",
            "department_art_department",
            "designation_prop_buyer_art_department",
        ),
        DealCrewUser("u5", "Isla Moreno", "pending", "department_sound", null),
    )

    /** The realistic deal, hydrated the way a saved deal loads, then placed on a union agreement. */
    private val dealForm: DealForm = DealHydration.form(DealPageFixtures.deal.json, DealMemoFixtures.catalogue).with(
        mapOf(
            "territory" to JsonPrimitive("uk"),
            "union" to JsonPrimitive("pact-bectu-tvda"),
            "pactBand" to JsonPrimitive("1"),
            "productionEntity" to JsonPrimitive("co-1"),
            "department" to JsonPrimitive("department_camera"),
            "designation" to JsonPrimitive("designation_focus_puller_camera"),
            "jobTitle" to JsonPrimitive("Focus Puller"),
            "dealType" to JsonPrimitive("weekly"),
            "dealStart" to JsonPrimitive("2026-08-12"),
            "dealEnd" to JsonPrimitive("2026-12-18"),
            "dayRate" to JsonPrimitive("450"),
            "weeklyRate" to JsonPrimitive("2250"),
            "rateApiDay" to JsonPrimitive("450"),
            "rateApiWeekly" to JsonPrimitive("2250"),
            "hpMode" to JsonPrimitive("excl"),
            "paymentCurrency" to JsonPrimitive("GBP"),
            "currency" to JsonPrimitive("GBP"),
            "employmentStatus" to JsonPrimitive("paye"),
            "userId" to JsonPrimitive("u1"),
            "unit" to JsonPrimitive("unit-main"),
            "crewType" to JsonPrimitive("shoot_crew"),
            "phaseRatesOn" to JsonPrimitive(true),
            "schedOn" to JsonPrimitive(true),
            "schedPrepStart" to JsonPrimitive("2026-08-12"),
            "schedPrepEnd" to JsonPrimitive("2026-08-28"),
            "schedShootStart" to JsonPrimitive("2026-08-31"),
            "schedShootEnd" to JsonPrimitive("2026-11-27"),
            "prepRate" to JsonPrimitive(400),
            "shootRate" to JsonPrimitive(450),
            "allowances" to Json.parseToJsonElement(
                """[{"id":"ps-1","name":"Per Diem","on":true,"rate":"35","_ps_rate":"35","basis":"day","applies_to":"shoot","nominal":"4400"}]""",
            ),
            "rentals" to Json.parseToJsonElement(
                """[{"id":"ps-2","name":"Camera Kit","on":true,"rate":"250","_ps_rate":"250","basis":"week","applies_to":"full_production","cap_type":"capped","cap_amount":"1000"}]""",
            ),
            "customConditions" to Json.parseToJsonElement(
                """["Travel out of London paid at agreed rate","Screen credit in main titles"]""",
            ),
            "bureau" to JsonPrimitive("Sargent-Disc"),
        ),
    )

    private val reference = BuilderReference(
        territory = "uk",
        agreements = listOf(agreement),
        agreementId = "pact-bectu-tvda",
        agreement = agreement,
        rateKey = "k",
        resolvedRates = listOf(rateCard),
        coveredRoles = listOf(CoveredDepartment("department_camera", listOf("designation_focus_puller_camera"))),
        coveredTerritories = setOf("uk", "ie", "us", "ca", "fr", "de", "au"),
    )

    private fun base(route: DealMemoRoute) = DealMemoFixtures.shell(DealTab.Deals).copy(
        route = route,
        production = DealPageFixtures.production.copy(
            project = DealProjectInfo("The Long Winter", "Frostline Pictures Ltd", "scripted-tv"),
        ),
        coa = DealPageFixtures.coa,
        projectSettings = ProjectSettingsState(view = ProjectSettingsView(settings), loaded = true),
        crewDirectory = crewDirectory,
        directoryReady = true,
    )

    private fun deal(
        editing: Int? = null,
        form: DealForm = dealForm,
        edit: BuilderState.() -> BuilderState = { this },
    ): DealMemoUiState {
        val route = DealMemoRoute.EditDeal("deal-1")
        return base(route).copy(
            builder = BuilderState(
                visit = 1,
                mode = BuilderMode(deal = true, dealId = "deal-1"),
                form = form,
                dealId = "deal-1",
                dealReference = "DM-0042",
                dealStatus = "draft",
                editingSection = editing,
                autosave = AutosaveUi(AutosaveStatus.Saved, savedAt = 1_786_500_000_000),
                reference = reference,
                savedRules = DealHydration.savedRules(form),
            ).edit(),
        )
    }

    fun dealPage() = deal()

    fun dealEditor(section: Int) = deal(editing = section)

    fun picture() = deal(
        editing = DealValidators.RATES,
        form = dealForm.with("dealType", "picture").with("pictureFee", JsonPrimitive(48000)),
    )

    fun buyout() =
        deal(editing = DealValidators.RATES, form = dealForm.with("dealType", "buyout").with("buyoutRateMode", "both"))

    fun issuePreview() = deal { copy(issuePreview = false) }

    fun validation() = deal {
        copy(
            issueErrors = mapOf(
                DealValidators.CREW to listOf("Crew Member"),
                DealValidators.RATES to listOf("Day Rate"),
            ),
            validation = BuilderValidation(
                title = "Complete these sections before issuing",
                message = "Each section below is highlighted on the page — open its Edit to fill what's missing.",
                fields = listOf("Crew Details — Crew Member", "Rates & Compensation — Day Rate"),
            ),
        )
    }

    fun rulesGrid() = dealEditor(DealValidators.RATES).let { state ->
        var next = 0
        val lists = RuleAuthoring.editorLists(
            dealForm,
            agreement,
            ProjectSettingsView(settings).nonUnionPaybreakdown,
        )
        state.copy(
            builder = state.builder?.copy(rules = RulesEditorState(rows = BulkRules.rows(lists) { "row-${next++}" })),
        )
    }

    private val setupForm = DealForm.INITIAL.with(
        mapOf(
            "territory" to JsonPrimitive("uk"),
            "union" to JsonPrimitive("pact-bectu-tvda"),
            "productionEntity" to JsonPrimitive("co-1"),
            "pactBand" to JsonPrimitive("1"),
            "dealStart" to JsonPrimitive("2026-08-12"),
            "dealEnd" to JsonPrimitive("2026-12-18"),
            "schedOn" to JsonPrimitive(true),
            "schedPrepStart" to JsonPrimitive("2026-08-12"),
            "schedPrepEnd" to JsonPrimitive("2026-08-28"),
            "allowances" to dealForm.list("allowances").let(::JsonArray),
            "rentals" to dealForm.list("rentals").let(::JsonArray),
            "customConditions" to dealForm.list("customConditions").let(::JsonArray),
        ),
    )

    private fun setup(
        form: DealForm = setupForm,
        lockedGroup: SetupGroup? = null,
        edit: BuilderState.() -> BuilderState = { this },
    ): DealMemoUiState {
        val group = lockedGroup ?: SetupGroup.Union
        val saved = (settings["day_types"] as JsonArray).map { it.jsonObject }
        var next = 0
        val dayRows = DayTypeRows.rows(saved) { "dt-${next++}" }
        return base(DealMemoRoute.SetupHub(group, DealMemoRoute.NEW)).copy(
            builder = BuilderState(
                visit = 2,
                mode = BuilderMode(deal = false, lockedGroup = lockedGroup, exitTo = DealMemoRoute.SetupHub(group)),
                form = form,
                reference = reference,
                setupBureaus = listOf(BureauRow("b1", "Sargent-Disc", "Weekly PAYE")),
                setupPage = SetupPageState(dayTypes = DayTypesDraft(dayRows, dayRows)),
                autosave = AutosaveUi(AutosaveStatus.Pending),
            ).edit(),
        )
    }

    fun unionSetup() = setup()

    fun nonUnionSetup() = setup(form = setupForm.with("union", "non_union"), lockedGroup = SetupGroup.NonUnion)

    fun companyModal() = setup {
        copy(
            setupPage = setupPage.copy(
                companyDraft = json(
                    """{"id":"co-2","name":"Frostline Scotland Ltd","legal_name":"","country":"United Kingdom","country_code":"GB","bank_ids":[],"tax_credits":["UK HETV"],"uk":{"paye_ref":"12/AB","accounts_office_ref":""}}""",
                ),
            ),
        )
    }

    fun ruleImport() = nonUnionSetup().let { state ->
        var next = 0
        val lists = NonUnionPayBreakdown.lists(NonUnionPayBreakdown.section(settings)) { "r-${next++}" }
        val builder = requireNotNull(state.builder)
        state.copy(
            builder = builder.copy(
                rules = RulesEditorState(rows = BulkRules.rows(lists) { "row-${next++}" }, agreementImport = true),
                rulesTarget = RulesTarget.Project,
                setupPage = builder.setupPage.copy(
                    ruleImport = RuleImportState(
                        territory = "uk",
                        agreementId = "pact-bectu-tvda",
                        agreements = listOf(
                            AgreementSummary(
                                "pact-bectu-tvda",
                                "PACT/BECTU TV Drama Agreement",
                                shortLabel = "PACT/BECTU TVDA",
                            ),
                        ),
                        agreement = agreement,
                    ),
                ),
            ),
        )
    }

    private val templates = listOf(
        DealTemplate(
            "t1",
            "UK Camera — Standard",
            form = json("""{"union":"pact-bectu-tvda"}"""),
            createdBy = "u-acc",
            createdAt = 1_786_500_000_000,
        ),
        DealTemplate(
            "t2",
            "UK Art Department",
            form = json("""{"union":"pact-bectu-tvda"}"""),
            createdBy = "u2",
            createdAt = 1_786_400_000_000,
        ),
        DealTemplate(
            "t3",
            "TPL-DRFT-7Q2K",
            form = json("""{"union":"pact-bectu-tvda"}"""),
            createdBy = "u-acc",
            createdAt = 1_786_300_000_000,
        ),
        DealTemplate(
            "t4",
            "Non-Union Crew",
            form = json("""{"union":"non_union"}"""),
            createdBy = "u-acc",
            createdAt = 1_786_200_000_000,
        ),
    )

    fun hubCards() = base(DealMemoRoute.SetupHub(SetupGroup.Union)).copy(templates = TemplatesState(rows = templates))

    fun hubDelete() = hubCards().copy(hub = SetupHubState(confirmDelete = templates[1]))

    fun hubInline() = base(DealMemoRoute.SetupHub(SetupGroup.NonUnion)).copy(
        templates = TemplatesState(rows = templates.filterNot { it.nonUnion }),
        hub = SetupHubState(inlineFor = SetupGroup.NonUnion),
        builder = nonUnionSetup().builder?.copy(
            mode = BuilderMode(
                deal = false,
                lockedGroup = SetupGroup.NonUnion,
                embedded = true,
                exitTo = DealMemoRoute.SetupHub(SetupGroup.NonUnion),
            ),
        ),
    )

    /** Every section id of a deal page, for one shot per editor. */
    val dealEditors = BuilderSection.dealSections(accountant = true).filterNot { it.noEdit }.map { it.id to it.name }
}
