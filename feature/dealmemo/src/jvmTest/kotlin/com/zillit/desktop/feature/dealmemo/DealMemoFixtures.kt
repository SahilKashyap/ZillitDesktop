@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.CatalogueDepartment
import com.zillit.desktop.feature.dealmemo.domain.CatalogueDesignation
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealOverview
import com.zillit.desktop.feature.dealmemo.domain.DealPerson
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCount
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementDocument
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.domain.rates.DesignationRate
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.domain.rates.RateTierEntry
import com.zillit.desktop.feature.dealmemo.domain.rates.UnionSummary
import com.zillit.desktop.feature.dealmemo.ui.AgreementOrigin
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.DealsState
import com.zillit.desktop.feature.dealmemo.ui.GlobalRatesState
import com.zillit.desktop.feature.dealmemo.ui.HistoryState
import com.zillit.desktop.feature.dealmemo.ui.MyDealState
import com.zillit.desktop.feature.dealmemo.ui.NoticesState
import com.zillit.desktop.feature.dealmemo.ui.OverviewState
import com.zillit.desktop.feature.dealmemo.ui.QueueState
import com.zillit.desktop.feature.dealmemo.ui.RatesView
import com.zillit.desktop.feature.dealmemo.ui.SendNoticeDraft
import com.zillit.desktop.feature.dealmemo.ui.SetupGateState
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Realistic states for the render tests and screenshots. */
internal object DealMemoFixtures {

    private fun doc(text: String) = DealDoc(Json.parseToJsonElement(text).jsonObject)

    val accountant = DealMemoViewer(
        userId = "u-acc",
        departmentIdentifier = "department_accounts",
        hasPostingAccess = true,
        hasViewAccess = true,
        memberStatus = "accepted",
        rightsLoaded = true,
    )

    val crew = DealMemoViewer(
        userId = "u-crew",
        departmentIdentifier = "department_camera",
        hasViewAccess = true,
        rightsLoaded = true,
    )

    val catalogue = DepartmentCatalogue(
        listOf(
            CatalogueDepartment(
                "d-cam", "department_camera", "camera_label",
                listOf(
                    CatalogueDesignation(
                        "g-dop",
                        "designation_director_of_photography_camera",
                        "director_of_photography_label",
                    ),
                    CatalogueDesignation("g-foc", "designation_focus_puller_camera", "focus_puller_label"),
                    CatalogueDesignation("g-cla", "designation_clapper_loader_camera", "clapper_loader_label"),
                ),
            ),
            CatalogueDepartment(
                "d-art", "department_art_department", "art_department_label",
                listOf(CatalogueDesignation("g-pb", "designation_prop_buyer_art_department", "prop_buyer_label")),
            ),
            CatalogueDepartment(
                "d-snd", "department_sound", "sound_label",
                listOf(
                    CatalogueDesignation(
                        "g-mix",
                        "designation_production_sound_mixer_sound",
                        "production_sound_mixer_label",
                    ),
                ),
            ),
        ),
    )

    val people = mapOf(
        "u1" to DealPerson("u1", "Amara Okafor", "camera_label", "focus_puller_label", "accepted"),
        "u2" to DealPerson("u2", "Ben Hartley", "art_department_label", "prop_buyer_label", "accepted"),
        "u3" to DealPerson("u3", "Chloé Martin", "sound_label", "production_sound_mixer_label", "accepted"),
        "u4" to DealPerson("u4", "Dev Patel", "camera_label", "clapper_loader_label", "left"),
        "u-acc" to DealPerson("u-acc", "Priya Shah", "accounts_label", "production_accountant_label", "accepted"),
    )

    private const val JUNE = 1_780_272_000_000L // 1 Jun 2026
    private const val AUG = 1_786_492_800_000L // 12 Aug 2026

    val deals = listOf(
        doc(
            """{"_id":"d1","status":"issued","deal_reference":"DRFT-0042","user_id":"u1","created_by":"u-acc","created_at":5,"department_id":"d-cam","crew_details":{"crew_name":"Amara Okafor","designation_identifier":"designation_focus_puller_camera","department_identifier":"department_camera"},"rates":{"daily":{"rate":425},"contract_currency":"GBP"},"deal":{"type":"weekly","start_date":$JUNE,"end_date":$AUG,"deal_completion_due":10}}""",
        ),
        doc(
            """{"_id":"d2","status":"approved","deal_reference":"DM-0038","user_id":"u2","created_at":4,"department_id":"d-art","crew_details":{"crew_name":"Ben Hartley","designation_identifier":"designation_prop_buyer_art_department"},"rates":{"daily":{"rate":310.5},"contract_currency":"GBP"},"deal":{"type":"fixed","start_date":$JUNE,"end_date":$AUG},"is_nominal":false}""",
        ),
        doc(
            """{"_id":"d3","status":"awaiting_approval","deal_reference":"DM-0035","user_id":"u3","created_at":3,"department_id":"d-snd","is_external":true,"crew_details":{"crew_name":"Chloé Martin","custom_designation":"Sound Recordist"},"rates":{"daily":{"rate":510},"contract_currency":"EUR"},"deal":{"type":"dayplayer","start_date":$JUNE}}""",
        ),
        doc(
            """{"_id":"d4","status":"active","deal_reference":"DM-0031","user_id":"u4","created_at":2,"department_id":"d-cam","crew_details":{"crew_name":"Dev Patel","designation_identifier":"designation_clapper_loader_camera"},"rates":{"daily":{"rate":280},"contract_currency":"GBP"},"deal":{"type":"weekly","start_date":$JUNE,"end_date":$AUG,"notice_period":"2_week"},"last_pay_date":$AUG}""",
        ),
        doc(
            """{"_id":"d5","status":"draft","user_id":"u-acc","created_by":"u-acc","created_at":1,"crew_details":{"crew_name":"DM-DRFT-9F2A"},"rates":{"daily":{"rate":0}},"deal":{"type":"buyout"}}""",
        ),
        doc(
            """{"_id":"d6","status":"active","deal_reference":"DM-0029","user_id":"u1","created_at":0,"department_id":"d-cam","crew_details":{"crew_name":"Amara Okafor","designation_identifier":"designation_director_of_photography_camera"},"rates":{"daily":{"rate":1250},"contract_currency":"GBP"},"deal":{"type":"weekly","end_date":$AUG,"notice_period":"1_week"},"notice_status":"sent","notice_sent_by":"u-acc","notice_sent_at":$JUNE}""",
        ),
    )

    fun shell(tab: DealTab, viewer: DealMemoViewer = accountant) = DealMemoUiState(
        route = DealMemoRoute.Tab(tab),
        viewer = viewer,
        metadata = DealMemoMetadata(isApprover = true, loaded = true),
        catalogue = catalogue,
        people = people,
    )

    fun allDeals() = shell(DealTab.Deals).copy(deals = DealsState(rows = deals, loaded = true, loadedAt = 100))

    fun setupGate() = allDeals().let { it.copy(deals = it.deals.copy(setupGate = SetupGateState(SetupGroup.NonUnion))) }

    fun deleteConfirm() = allDeals().let { it.copy(deals = it.deals.copy(pendingDelete = deals[0])) }

    fun history() = allDeals().copy(
        history = HistoryState(
            dealId = "d1",
            subtitle = "DRFT-0042",
            loading = false,
            entries = listOf(
                DealHistoryEntry("deal_issued", "u-acc", JUNE + 3_600_000, null),
                DealHistoryEntry("crew_details_updated", "u1", JUNE + 7_200_000, "Bank details added"),
                DealHistoryEntry("created", "system", JUNE, null),
            ),
        ),
    )

    fun overview() = shell(DealTab.Overview).copy(
        overview = OverviewState(
            data = DealOverview(
                total = 24.0,
                approved = 9.0,
                awaitingApproval = 4.0,
                totalValue = 18_450.5,
                active = 11.0,
                issued = 5.0,
                draft = 3.0,
                recent = deals.take(5),
                departmentBreakdown = listOf(
                    DepartmentCount("d-cam", 10.0),
                    DepartmentCount("d-art", 6.0),
                    DepartmentCount("d-snd", 4.0),
                ),
            ),
        ),
    )

    fun queue() = shell(DealTab.ApprovalQueue).copy(
        queue = QueueState(rows = deals.filter { it.rawStatus == "awaiting_approval" } + deals[1], loaded = true),
    )

    fun emptyQueue() = shell(DealTab.ApprovalQueue).copy(queue = QueueState(loaded = true))

    fun myDealEmpty() = shell(DealTab.MyDeal, crew).copy(myDeal = MyDealState(loaded = true))

    fun notices() = shell(DealTab.Notices).copy(notices = NoticesState(rows = deals, loaded = true))

    fun sendNotice() = notices().let {
        val deal = deals[3]
        it.copy(
            notices = it.notices.copy(
                send = SendNoticeDraft(
                    deal = deal,
                    date = "2026-08-12",
                    body = "Dear Dev Patel,\n\nThis letter serves as formal notice that your engagement will end on " +
                        "12 Aug 2026, in line with your 2 Weeks notice period.\n\nYour last pay day will be 12 Aug " +
                        "2026.\n\nThank you for your contribution to the production.",
                ),
            ),
        )
    }

    fun noticeTemplate() = shell(DealTab.Notices).copy(route = DealMemoRoute.NoticeTemplate).let {
        it.copy(
            noticeTemplate = it.noticeTemplate.copy(
                loaded = true,
                text = it.noticeTemplate.text + "\n\nKind regards,\nProduction Office",
            ),
        )
    }

    // -- Global Production Rates -----------------------------------------------------------

    private val covered = setOf("uk", "ie", "us", "ca", "de", "fr", "es", "au", "nz", "za")

    fun ratesWelcome() = shell(DealTab.Deals).copy(
        route = DealMemoRoute.GlobalRates,
        rates = GlobalRatesState(coveredLoading = false, covered = covered),
    )

    fun ratesSkeleton() = shell(DealTab.Deals).copy(route = DealMemoRoute.GlobalRates, rates = GlobalRatesState())

    private val bectuCamera = Branch(
        "bectu_camera",
        "Camera Branch",
        "bectu",
        shortLabel = "CAM",
        territory = "uk",
        region = "europe-uk",
        source = "https://bectu.org.uk/camera/",
        currency = "GBP",
    )

    fun ratesTerritory() = ratesWelcome().let {
        it.copy(
            rates = it.rates.copy(
                view = RatesView.Territory,
                territoryId = "uk",
                unions = listOf(
                    UnionSummary("bectu", "BECTU", "BECTU", "https://bectu.org.uk/"),
                    UnionSummary("equity", "Equity"),
                ),
                branches = listOf(
                    bectuCamera,
                    Branch("bectu_art", "Art Department Branch", "bectu", territory = "uk"),
                    Branch("bectu_sound", "Sound Branch", "bectu", shortLabel = "SND", territory = "uk"),
                    Branch("bectu_hmu", "Hair & Make-Up Branch", "bectu", territory = "uk"),
                    Branch("equity", "Equity", "equity", territory = "uk"),
                ),
            ),
        )
    }

    @Suppress("LongMethod")
    fun ratesBranch() = ratesTerritory().let {
        it.copy(
            rates = it.rates.copy(
                view = RatesView.Branch,
                branch = bectuCamera,
                agreements = listOf(
                    AgreementSummary("pact_bectu_mmp", "PACT/BECTU Major Motion Picture Agreement", "uk", "GBP"),
                    AgreementSummary("pact_bectu_tvda", "PACT/BECTU TV Drama Agreement", "uk", "GBP"),
                ),
                empStatuses = mapOf(
                    "uk" to listOf(
                        EmpStatus(
                            "paye",
                            "PAYE (Schedule E)",
                            "PAYE",
                            "blue",
                            hpShown = true,
                            sub = "Employed for tax — holiday pay, employer NI and pension auto-enrolment apply.",
                        ),
                        EmpStatus(
                            "ltd",
                            "Limited company",
                            "LTD",
                            "orange",
                            sub = "Engaged through their own company; invoices gross of VAT.",
                        ),
                    ),
                ),
                rates = listOf(
                    DesignationRate(
                        "designation_director_of_photography_camera",
                        productionType = "feature",
                        minBudget = 30_000_000.0,
                        daily = listOf(
                            RateTierEntry(1450.0, 1300.0, 1800.0, 11.0, "SWD"),
                            RateTierEntry(1320.0, workHours = 10.0, dayType = "CWD"),
                        ),
                        weekly = listOf(RateTierEntry(baseRate = 6900.0, workHours = 55.0)),
                        notes = "Rates are minimums; above-scale deals are common on MMPs.",
                    ),
                    DesignationRate(
                        "designation_focus_puller_camera",
                        productionType = "feature",
                        maxBudget = 10_000_000.0,
                        minExp = 2.0,
                        hourly = listOf(RateTierEntry(baseRate = 38.5)),
                        daily = listOf(RateTierEntry(baseRate = 425.0, workHours = 11.0)),
                    ),
                    DesignationRate(
                        "designation_focus_puller_camera",
                        productionType = "television",
                        minBudget = 2_500_000.0,
                        maxBudget = 12_345_678.0,
                        daily = listOf(RateTierEntry(baseRate = 398.0, workHours = 10.0, dayType = "SWD")),
                    ),
                    DesignationRate(
                        "designation_steadicam_operator_other",
                        productionType = "feature",
                        flatRate = listOf(RateTierEntry(baseRate = 750.0)),
                    ),
                ),
            ),
        )
    }

    fun ratesAgreement() = ratesBranch().let {
        val json = Json.parseToJsonElement(
            """
            {"_identifier":"pact_bectu_mmp","name":"PACT/BECTU Major Motion Picture Agreement","source":"https://www.pact.co.uk/mmp/","territory":"uk","currency":"GBP","parties":["PACT","BECTU"],
             "basic_rate_details":{"day_type":"SWD","effective_from":1711929600000,"effective_to":1774915200000,
               "daily":{"base_rate":429,"min_rate":380,"max_rate":500,"work_hrs":11},"weekly":{"base_rate":2145,"work_hrs":55},"hourly":{"base_rate":39}},
             "overtimes":{"note":"Overtime is paid in 30-minute increments.","rows":[
               {"id":"overtime","label":"Pre-call","triggers":[{"day_type":"SWD","call_before":420}],"rate_type":"multiplier","rate_amount":1.5,"basis":"hour","min":30,"max":60},
               {"id":"overtime","label":"Post-wrap","triggers":[{"day_type":"SWD","after":660,"before":780,"guarantee":60,"increment":30}],"multiplier":2,"basis":"30_minutes","is_gold_time":true},
               {"id":"sixth_day","label":"6th consecutive day","triggers":[{"day_number":6,"consecutive":true}],"rate_type":"multiplier","rate_amount":0.5,"is_enhancement":true}
             ]},
             "turnaround":{"rows":[{"label":"Broken turnaround","triggers":[{"less":660}],"rate_type":"flat","rate_amount":85,"basis":"day"}]},
             "allowances":{"note":"Per diems follow HMRC benchmark scale rates.","rows":[{"id":"per_diem","label":"Per diem","rate_amount":35,"basis":"day"},{"id":"mileage","label":"Mileage","rate_type":"flat","rate_amount":0.45,"basis":"mile"}]},
             "fringes":{"default":{"label":"PAYE package","currency":"GBP","items":[{"label":"Holiday pay","rate_type":"percentage","rate_amount":12.07,"basis":"weekly_gross","raw_value":"12.07%"},{"label":"Employer pension","rate_type":"flat","rate_amount":120,"raw_value":120,"basis":"qualifying_earnings","hp_excl_only":true}]}},
             "fringe_map":{"paye":"default"},
             "pact":{"bands":[{"band":"1","label":"Band 1","notes":"Indie","threshold":"Under £4m","ot_min":38,"ot_max":52,"bank_holiday":"Double time"}]}
            }
            """.trimIndent(),
        ).jsonObject
        it.copy(
            rates = it.rates.copy(
                view = RatesView.Agreement,
                agreementOrigin = AgreementOrigin.Branch,
                agreement = AgreementDocument(json),
            ),
        )
    }
}
