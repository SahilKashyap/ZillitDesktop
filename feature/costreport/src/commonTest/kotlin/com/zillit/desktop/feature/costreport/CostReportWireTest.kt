package com.zillit.desktop.feature.costreport

import com.zillit.desktop.feature.costreport.data.epochOf
import com.zillit.desktop.feature.costreport.data.parseBudgets
import com.zillit.desktop.feature.costreport.data.parseCoaRows
import com.zillit.desktop.feature.costreport.data.parseCompanies
import com.zillit.desktop.feature.costreport.data.parseCurrencyOptions
import com.zillit.desktop.feature.costreport.data.parseLedger
import com.zillit.desktop.feature.costreport.data.parseLiveReport
import com.zillit.desktop.feature.costreport.data.parseSnapshotDetail
import com.zillit.desktop.feature.costreport.data.parseSnapshotList
import com.zillit.desktop.feature.costreport.domain.CoaLevel
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.PostKind
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.kpis
import com.zillit.desktop.feature.costreport.ui.PostedCrs
import com.zillit.desktop.feature.costreport.ui.exportFileName
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

class CostReportWireTest {

    @Test
    fun snapshotHeaderReadsEpochAndIsoDatesAndStringNumbers() {
        val list = parseSnapshotList(
            json(
                """
                [
                  {"id":"a","cadence":"weekly","reference":"CR-W-2026-W20","name":"Week 20","period_start":1747609200000,
                   "period_end":"1748213999999","published_at":"2025-05-25T09:00:00Z","total_variance":"-1250.50",
                   "status":"published","published_by":"u1"},
                  {"_id":"b","cadence":"daily","name":"Tue","published_at":1748246400000,"total_variance":300},
                  {"id":"c","cadence":"adhoc","name":"Period Lock — Wk 20","generated_at":"2026-05-26",
                   "total_variance":null}
                ]
                """,
            ),
        )
        assertEquals(3, list.size)
        val a = list.first { it.id == "a" }
        assertEquals(SnapshotCadence.Weekly, a.cadence)
        assertEquals(1747609200000L, a.periodStartMs)
        assertEquals(1748213999999L, a.periodEndMs)
        assertEquals(1748163600000L, a.publishedAtMs)
        assertEquals(-1250.5, a.totalVariance)
        assertEquals("u1", a.postedBy)
        val b = list.first { it.id == "b" }
        assertEquals(1748246400000L, b.postedAtMs)
        val c = list.first { it.id == "c" }
        assertNotNull(c.postedAtMs)
        assertNull(c.totalVariance)
        assertEquals(PostKind.Lock, c.kind)
        // Newest first: b (26 May 2025 08:00 UTC) sits above a (25 May 2025 09:00 UTC).
        assertTrue(list.indexOfFirst { it.id == "b" } < list.indexOfFirst { it.id == "a" })
    }

    @Test
    fun emptyListsArriveAsAnObjectOrWrapped() {
        assertEquals(emptyList(), parseSnapshotList(json("{}")))
        assertEquals(emptyList(), parseSnapshotList(null))
        assertEquals(listOf("x"), parseSnapshotList(json("""{"items":[{"id":"x"}]}""")).map { it.id })
        assertEquals(listOf("y"), parseSnapshotList(json("""{"rows":[{"id":"y"}]}""")).map { it.id })
        assertEquals(emptyList(), parseCoaRows(json("{}")))
        assertEquals(emptyList(), parseBudgets(json("[]")))
    }

    @Test
    fun epochOfAcceptsEveryShapeTheBackendWrites() {
        val zone = TimeZone.of("UTC")
        assertEquals(1748163600000L, epochOf(JsonPrimitive(1748163600000L), zone))
        assertEquals(1748163600000L, epochOf(JsonPrimitive("1748163600000"), zone))
        assertEquals(1748163600000L, epochOf(JsonPrimitive("1748163600000.0"), zone))
        assertEquals(1748163600000L, epochOf(JsonPrimitive("2025-05-25T09:00:00Z"), zone))
        assertEquals(1748163600000L, epochOf(JsonPrimitive("2025-05-25T10:00:00+01:00"), zone))
        assertEquals(1748131200000L, epochOf(JsonPrimitive("2025-05-25"), zone))
        assertEquals(1748163600000L, epochOf(JsonPrimitive("2025-05-25T09:00:00"), zone))
        assertNull(epochOf(JsonPrimitive("")))
        assertNull(epochOf(JsonPrimitive("not a date")))
        assertNull(epochOf(null))
    }

    @Test
    fun coaBudgetsCompaniesAndCurrenciesParseLeniently() {
        val coa = parseCoaRows(
            json(
                """[{"id":"1","code":"atl","name":"Above","line_type":"header","is_active":"1"},
                    {"_id":"2","code":"1100","name":"Story","line_type":"section","head_id":"1","sort_order":"3"},
                    {"id":"3","code":"1110","name":"Writers","line_type":"category","sec_id":"2","is_active":false},
                    {"id":"4","code":"1110-01","name":"Set","line_type":"weird","cat_id":"3"}]""",
            ),
        )
        assertEquals(listOf(CoaLevel.Section, CoaLevel.Header, CoaLevel.Nominal, CoaLevel.Set), coa.map { it.level })
        assertEquals("1", coa[1].parentId)
        assertEquals(3, coa[1].sortOrder)
        assertEquals(false, coa[2].isActive)

        val budgets = parseBudgets(json("""[{"id":"b1","version":"v1","label":"","status":"live"}]"""))
        assertEquals("LIVE", budgets.single().status)
        assertEquals("v1", budgets.single().display)

        val companies = parseCompanies(json("""{"value":[{"id":"c1","name":"Prod Co"},{"name":"no id"}]}"""))
        assertEquals(listOf("c1"), companies.map { it.id })

        val currencies = parseCurrencyOptions(
            json("""{"value":{"currencies":[{"code":"GBP","name":"Pound","symbol":"£","exr":1}],"default":"GBP"}}"""),
        )
        assertEquals("GBP", currencies.defaultCode)
        assertEquals("£", currencies.currencies.single().symbol)
        assertEquals(emptyList(), parseCurrencyOptions(json("{}")).currencies)
    }

    @Test
    fun liveReportAndSnapshotDetailParseLinesAndTotals() {
        val live = parseLiveReport(
            json(
                """{"lines":[{"account":"1110","budget":"1000","atd":250.5,"po_commits":10,"level":"nominal"},
                            {"account":null,"name":"Fringes","budget":5}],
                    "currency":"USD","default_currency":"GBP","budget_version":{"id":"bv1","version":"v1"}}""",
            ),
        )
        assertEquals(2, live.lines.size)
        assertEquals(1000.0, live.lines[0].budget)
        assertEquals(10.0, live.lines[0].po)
        assertNull(live.lines[1].account)
        assertEquals("USD", live.displayCurrency)
        assertEquals("bv1", live.budgetVersionId)

        val detail = parseSnapshotDetail(
            json(
                """{"id":"s1","cadence":"daily","name":"D","total_variance":-20,
                    "totals":{"budget":1000,"atd":300,"po_commits":100,"efc":0,"variance":null},
                    "lines":[{"id":"l1","account":"1110","budget":1000,"atd":300,"po_commits":100,
                              "etc":50,"efc":450}]}""",
            ),
        )
        assertNotNull(detail)
        assertEquals(1000.0, detail.totals.budget)
        assertEquals(100.0, detail.totals.commits)
        assertEquals(50.0, detail.lines.single().etc)
        val kpis = detail.kpis()
        assertEquals(400.0, kpis.estimatedFinalCost)
        assertEquals(-20.0, kpis.postedVariance)
        assertNull(parseSnapshotDetail(json("{}")))
    }

    @Test
    fun ledgerDerivesTypeFromSourceWhenAbsent() {
        val ledger = parseLedger(
            json(
                """{"code":"1110","name":"Writers","total":80,"count":3,
                    "by_type":{"actuals":{"total":50,"count":2},"commits":{"total":30,"count":1}},
                    "line_items":[
                      {"src":"INV","eff_date":1748163600000,"invoice_number":"INV-1","vendor":"ACME","amount":60},
                      {"src":"CRED","amount":-10,"type":"actuals"},
                      {"src":"PO","po_number":"PO-9","amount":30}
                    ]}""",
            ),
            requestedCode = "1110",
        )
        assertEquals(3, ledger.items.size)
        assertEquals(LedgerType.Actuals, ledger.items[0].effectiveType)
        assertEquals("Invoice", ledger.items[0].typeLabel)
        assertEquals("INV-1", ledger.items[0].reference)
        assertEquals(LedgerType.Actuals, ledger.items[1].effectiveType)
        assertEquals(LedgerType.Commits, ledger.items[2].effectiveType)
        assertEquals("PO-9", ledger.items[2].reference)
        assertEquals(50.0, ledger.actualsToDate)
        assertEquals(30.0, ledger.commitments)
        assertEquals(2, ledger.actuals.size)
        val bare = parseLedger(json("""{"line_items":[],"total":0,"name":null}"""), "2120")
        assertEquals("2120", bare.code)
        assertEquals(0.0, bare.actualsToDate)
    }

    @Test
    fun postedListDerivesKindFiltersAndDelta() {
        val rows = listOf(
            SnapshotHeader("n", cadence = SnapshotCadence.Weekly, name = "Wk 21", totalVariance = -100.0,
                publishedAtMs = 30),
            SnapshotHeader("m", cadence = SnapshotCadence.Daily, name = "Tue", totalVariance = -150.0,
                publishedAtMs = 20),
            SnapshotHeader("l", cadence = SnapshotCadence.Adhoc, name = "Period Lock — Wk 20", totalVariance = -120.0,
                publishedAtMs = 15),
            SnapshotHeader("k", cadence = SnapshotCadence.Adhoc, name = "Kick-off", totalVariance = null,
                publishedAtMs = 10),
        )
        assertEquals(listOf(PostKind.Weekly, PostKind.Daily, PostKind.Lock, PostKind.Initial), rows.map { it.kind })
        val all = PostedCrs(rows = rows).shown
        assertEquals(listOf(50.0, -30.0, null, null), all.map { it.delta })
        val weekEnd = PostedCrs(rows = rows, filter = PostedFilter.WeekEnd).shown
        assertEquals(listOf("n", "l", "k"), weekEnd.map { it.header.id })
        // The delta is against the chronologically previous post, not the previous *shown* one.
        assertEquals(50.0, weekEnd.first().delta)
        assertEquals(listOf("m"), PostedCrs(rows = rows, filter = PostedFilter.Daily).shown.map { it.header.id })
    }

    @Test
    fun exportFileNameUsesReferenceThenNameAndFlattensSlashes() {
        val referenced = SnapshotHeader("x", reference = "CR-D-2026-05-26-001")
        assertEquals("CR-D-2026-05-26-001.pdf", exportFileName(referenced, ExportFormat.Pdf))
        val named = SnapshotHeader("x", name = "Wk 20 / w:e 24 May")
        assertEquals("Wk 20 - w-e 24 May.xlsx", exportFileName(named, ExportFormat.Xlsx))
        assertEquals("cr-snapshot.csv", exportFileName(SnapshotHeader("x"), ExportFormat.Csv))
    }
}
