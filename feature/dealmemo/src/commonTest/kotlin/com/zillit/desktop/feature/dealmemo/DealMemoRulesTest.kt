@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.CatalogueDepartment
import com.zillit.desktop.feature.dealmemo.domain.CatalogueDesignation
import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealListRules
import com.zillit.desktop.feature.dealmemo.domain.DealPerson
import com.zillit.desktop.feature.dealmemo.domain.DealQuickFilter
import com.zillit.desktop.feature.dealmemo.domain.DealSort
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.NoticeGroupKind
import com.zillit.desktop.feature.dealmemo.domain.NoticeRules
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.domain.rates.DesignationRate
import com.zillit.desktop.feature.dealmemo.domain.rates.GlobalRatesRules
import com.zillit.desktop.feature.dealmemo.domain.rates.RateDepartmentSection
import com.zillit.desktop.feature.dealmemo.domain.rates.TerritoryCatalogue
import com.zillit.desktop.feature.dealmemo.domain.rates.UnionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure rules behind the lists, notices and Global Production Rates. */
class DealMemoRulesTest {

    private fun deal(json: String) = DealDoc(Json.parseToJsonElement(json).jsonObject)

    private val catalogue = DepartmentCatalogue(
        listOf(
            CatalogueDepartment(
                id = "d-cam",
                identifier = "department_camera",
                nameKey = "camera_label",
                designations = listOf(
                    CatalogueDesignation("g-dop", "designation_dop_camera", "director_of_photography_label"),
                    CatalogueDesignation("g-fac", "designation_focus_puller_camera", "focus_puller_label"),
                ),
            ),
            CatalogueDepartment(
                id = "d-art",
                identifier = "department_art_department",
                nameKey = "art_department_label",
                designations = listOf(
                    CatalogueDesignation("g-pb", "designation_prop_buyer_art_department", "prop_buyer_label"),
                ),
            ),
        ),
    )

    private val noTranslations: (String) -> String? = { null }

    // -- territories ------------------------------------------------------------------------

    @Test
    fun `the catalogue is the web's 41 territories in 13 regions`() {
        assertEquals(41, TerritoryCatalogue.total)
        assertEquals(13, TerritoryCatalogue.regions.size)
        assertEquals("Europe & UK", TerritoryCatalogue.regions.first().label)
        assertEquals("GBP", TerritoryCatalogue.defaultCurrency("UK"))
        assertEquals("EUR", TerritoryCatalogue.defaultCurrency("hr"))
    }

    @Test
    fun `coverage filters the tree but fails open when nothing is known`() {
        assertEquals(41, TerritoryCatalogue.coveredTree(emptySet()).sumOf { it.territories.size })
        val covered = TerritoryCatalogue.coveredTree(setOf("uk", "us", "ie"))
        assertEquals(listOf("europe-uk", "north-america"), covered.map { it.id })
    }

    @Test
    fun `sidebar search keeps a whole region by name and does not trim its query`() {
        val all = emptySet<String>()
        assertEquals(9, TerritoryCatalogue.sidebarTree(all, "europe").first { it.id == "europe" }.territories.size)
        assertEquals(
            listOf("United Kingdom"),
            TerritoryCatalogue.sidebarTree(all, "kingdom").flatMap { r -> r.territories.map { it.label } },
        )
        assertTrue(
            TerritoryCatalogue.sidebarTree(all, "kingdom ").isEmpty(),
            "a trailing space defeats the match, as on the web",
        )
    }

    // -- the territory page -----------------------------------------------------------------------

    @Test
    fun `sections come from branches, name unknown unions by id, and sort by union name`() {
        val unions = listOf(
            UnionSummary("equity", "Equity"),
            UnionSummary("bectu", "BECTU"),
            UnionSummary("idle", "Idle Union"),
        )
        val branches = listOf(
            Branch("equity", "Equity", unionIdentifier = "equity"),
            Branch("bectu_camera", "Camera Branch", unionIdentifier = "bectu", shortLabel = "CAM"),
            Branch("bectu_art", "Art Branch", unionIdentifier = "bectu"),
            Branch("orphan_branch", "Orphan", unionIdentifier = "ghost_union"),
        )
        val sections = GlobalRatesRules.unionSections(unions, branches, "")
        assertEquals(listOf("BECTU", "Equity", "ghost_union"), sections.map { it.union.name })
        assertTrue(sections.first { it.union.identifier == "equity" }.isFlat, "one branch that is the union itself")
        assertFalse(sections.first { it.union.identifier == "bectu" }.isFlat)

        val byUnion = GlobalRatesRules.unionSections(unions, branches, "bectu")
        assertEquals(2, byUnion.single().branches.size, "a union matching the query keeps every branch")
        val byBranch = GlobalRatesRules.unionSections(unions, branches, "cam")
        assertEquals(listOf("Camera Branch"), byBranch.single().branches.map { it.name })
    }

    // -- the rate card ------------------------------------------------------------------------------

    @Test
    fun `rates group in catalogue order, orphans go to Other, and a mismatched department vanishes`() {
        val rates = listOf(
            DesignationRate("designation_prop_buyer_art_department", productionType = "tv_label"),
            DesignationRate("designation_focus_puller_camera", productionType = "feature_label", minBudget = 2e6),
            DesignationRate("designation_focus_puller_camera", productionType = "feature_label", minBudget = 1e6),
            DesignationRate("designation_dop_camera", departmentIdentifier = "department_art_department"),
            DesignationRate("designation_gaffer_other"),
            DesignationRate(null),
        )
        val sections = GlobalRatesRules.rateSections(rates, catalogue)
        assertEquals(
            listOf("department_camera", "department_art_department", RateDepartmentSection.OTHER_IDENTIFIER),
            sections.map { it.identifier },
        )
        val camera = sections.first()
        assertEquals(
            listOf("designation_focus_puller_camera"),
            camera.designations.map { it.identifier },
            "the DOP row names Art, which does not list it",
        )
        assertEquals(listOf(1e6, 2e6), camera.designations.single().rates.map { it.minBudget }, "lowest budget first")
        assertEquals("gaffer", GlobalRatesRules.orphanLabelKey(sections.last().designations.single().identifier))
        assertFalse(sections.last().designations.single().matched)
    }

    // -- All Deals ------------------------------------------------------------------------------

    private val rows = listOf(
        deal(
            """{"_id":"a","status":"approved","deal_reference":"DM-001","created_at":1,"crew_details":{"crew_name":"Zed Adams"},"rates":{"daily":{"rate":300}}}""",
        ),
        deal(
            """{"_id":"b","status":"active","deal_reference":"DM-002","created_at":3,"crew_details":{"crew_name":"Amy Brown"},"rates":{"daily":{"rate":900}},"last_pay_date":1760000000000}""",
        ),
        deal("""{"_id":"c","status":"deactivated","created_at":2,"crew_details":{"crew_name":"Cal Cole"}}"""),
        deal(
            """{"_id":"d","status":"issued","user_id":"u2","created_by":"u1","department_id":"d-cam","crew_details":{"crew_name":"Dee Dunn","designation_identifier":"designation_focus_puller_camera"},"is_nominal":false}""",
        ),
    )

    @Test
    fun `the approved pill spans active deals and the deactivated pill spans scheduled ones`() {
        val labels = DealCrewLabels(catalogue = catalogue, translate = noTranslations)
        assertEquals(
            listOf("b", "a"),
            DealListRules.visible(rows, DealQuickFilter.Approved, null, "", DealSort.DateDesc, labels).map { it.id },
        )
        assertEquals(
            listOf("b", "c"),
            DealListRules.visible(rows, DealQuickFilter.Deactivated, null, "", DealSort.DateDesc, labels).map { it.id },
        )
        assertEquals(
            listOf("b", "a", "c", "d"),
            DealListRules.visible(rows, DealQuickFilter.All, null, "", DealSort.RateDesc, labels).map { it.id },
        )
        assertEquals(
            listOf("b", "c", "d", "a"),
            DealListRules.visible(rows, DealQuickFilter.All, null, "", DealSort.NameAsc, labels).map { it.id },
        )
    }

    @Test
    fun `search reads the reference and the resolved role`() {
        val labels = DealCrewLabels(catalogue = catalogue, translate = noTranslations)
        assertEquals(
            listOf("b"),
            DealListRules.visible(rows, DealQuickFilter.All, null, " dm-002 ", DealSort.DateDesc, labels).map { it.id },
        )
        assertEquals(
            listOf("d"),
            DealListRules.visible(
                rows,
                DealQuickFilter.All,
                null,
                "focus puller",
                DealSort.DateDesc,
                labels,
            ).map { it.id },
        )
        assertEquals(
            listOf("d"),
            DealListRules.visible(rows, DealQuickFilter.All, "d-cam", "", DealSort.DateDesc, labels).map { it.id },
        )
    }

    @Test
    fun `row actions follow the web's gates`() {
        val issued = rows.last()
        assertTrue(DealListRules.canChase(issued, canPost = true, userId = "u1"))
        assertFalse(DealListRules.canChase(issued, canPost = true, userId = "u2"), "never your own deal")
        assertFalse(DealListRules.canChase(issued, canPost = false, userId = "u1"))
        assertTrue(
            DealListRules.canDelete(issued, canPost = false, userId = "u1"),
            "the creator may delete without posting rights",
        )
        assertFalse(
            DealListRules.canDelete(rows.first(), canPost = true, userId = "u1"),
            "approved deals are never deleted",
        )
        assertTrue(DealListRules.nominalsPending(issued))
        assertFalse(DealListRules.nominalsPending(rows[1]), "a deal on its way out is not asked for codes")
        assertTrue(DealListRules.chaseOverdue(deal("""{"deal":{"deal_completion_due":100}}"""), nowMillis = 200))
    }

    @Test
    fun `labels, rates and the delete prompt are the web's words`() {
        assertEquals("Weekly Rolling", DealListRules.dealTypeLabel("weekly"))
        assertEquals("Buy-Out", DealListRules.dealTypeLabel("buy-out"))
        assertEquals("—", DealListRules.dealTypeLabel(null))
        assertEquals("0.00", DealListRules.dayRate(deal("""{}""")))
        assertEquals(
            "PKR1,250.00",
            DealListRules.dayRate(deal("""{"rates":{"daily":{"rate":1250},"contract_currency":"PKR"}}""")),
        )
        assertEquals(
            "Are you sure you want to delete the ISSUED deal (the crew already have its portal link) \"Dee Dunn\"? " +
                "This action cannot be undone.",
            DealListRules.deleteMessage(rows.last()),
        )
        assertEquals(DealStatus.Draft, deal("""{"status":"archived"}""").status, "an unknown status reads as Draft")
    }

    // -- names ------------------------------------------------------------------------------------

    @Test
    fun `names come from the directory first, departments and roles from the deal`() {
        val labels = DealCrewLabels(
            people = mapOf(
                "u2" to DealPerson("u2", "Deanna Dunn", departmentName = "sound_label", designationName = "boom_label"),
            ),
            catalogue = catalogue,
            translate = noTranslations,
        )
        val person = labels.labels(rows.last())
        assertEquals("Deanna Dunn", person.name)
        assertEquals("Camera", person.department)
        assertEquals("Focus Puller", person.role)
        assertEquals("Action Prop Buyer", labels.designationLabel("designation_action_prop_buyer_art_department"))
        assertEquals(
            "Grip Camera Truck",
            labels.designationLabel("designation_grip_camera_truck"),
            "an unknown department is not stripped",
        )
        assertEquals("—", labels.departmentLabel("5f0c2a7b9e1d4c3b2a190807"))
        assertTrue(isNonUnionId(" Non-Union "))
    }

    // -- notices -----------------------------------------------------------------------------------

    @Test
    fun `notices group offboarded crew first and sort the longest notice first`() {
        val deals = listOf(
            deal("""{"_id":"n1","status":"active","user_id":"left","deal":{"end_date":500}}"""),
            deal("""{"_id":"n2","status":"active","deal":{"end_date":300,"notice_period":"1_week"}}"""),
            deal("""{"_id":"n3","status":"active","deal":{"end_date":100,"notice_period":"2_week"}}"""),
            deal("""{"_id":"n4","status":"active","deal":{"end_date":50}}"""),
            deal("""{"_id":"n5","status":"issued"}"""),
        )
        val labels = DealCrewLabels(
            people = mapOf("left" to DealPerson("left", "Gone", status = "Removed")),
            translate = noTranslations,
        )
        val groups = NoticeRules.groups(deals, "", labels)
        assertEquals(
            listOf(NoticeGroupKind.Deactivated, NoticeGroupKind.WithNotice, NoticeGroupKind.WithoutNotice),
            groups.map { it.kind },
        )
        assertEquals(listOf("n3", "n2"), groups[1].deals.map { it.id }, "two weeks before one")
        assertEquals("2 Weeks", NoticeRules.noticeLabel("2_week"))
        assertEquals("1 week", NoticeRules.noticeLabel("1week"))
        assertEquals("—", NoticeRules.noticeLabel(null))
        assertEquals(9999, NoticeRules.rank("production"))
    }

    @Test
    fun `a letter keeps a token it cannot fill, and dates land on their UTC day`() {
        val letter = NoticeRules.fill(
            "Dear {{crew_name}}, ends {{contract_end_date}}.",
            mapOf("crew_name" to "", "contract_end_date" to "12 Aug 2026"),
        )
        assertEquals("Dear {{crew_name}}, ends 12 Aug 2026.", letter)
        assertEquals(1_786_536_000_000L, NoticeRules.noonUtc("2026-08-12"))
        assertEquals(1_786_492_800_000L, NoticeRules.midnightUtc("2026-08-12"))
        val deal = deal("""{"_id":"x","status":"active","last_pay_day":1}""")
        assertFalse(NoticeRules.canDeactivate(deal, emptySet()), "a notice's last pay day already schedules it")
        assertFalse(NoticeRules.canDeactivate(deal("""{"_id":"y","status":"active"}"""), setOf("y")))
    }
}
