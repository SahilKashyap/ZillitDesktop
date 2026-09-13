package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.data.PayloadWire
import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.ComposeReport
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportTemplates
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportKindTest {

    @Test
    fun `legacy rows without a type belong to the production report only`() {
        assertTrue(ReportKind.Production.owns(""))
        assertFalse(ReportKind.Ad.owns(""))
        assertTrue(ReportKind.Ad.owns(" AD "))
        assertTrue(ReportKind.Wrap.owns("wrap"))
        assertFalse(ReportKind.Production.owns("wrap"))
        assertEquals(ReportKind.Wrap, ReportKind.fromWire("Wrap"))
        assertEquals(ReportKind.Production, ReportKind.fromWire(null))
    }

    @Test
    fun `reportType rides shared on the wire only when set`() {
        val plain = PayloadWire.emit(SheetPayload())["shared"]!!.jsonObject
        assertNull(plain["reportType"])

        val ad = PayloadWire.emit(SheetPayload(shared = SharedHeader(reportType = "ad", secondAdName = "Sam")))
        val shared = ad["shared"]!!.jsonObject
        assertEquals("ad", shared["reportType"]!!.jsonPrimitive.content)
        assertEquals("Sam", shared["secondADName"]!!.jsonPrimitive.content)

        val back = PayloadWire.parse(ad)
        assertEquals("ad", back.shared.reportType)
        assertEquals("Sam", back.shared.secondAdName)
    }

    @Test
    fun `AD and Wrap templates match the web's row order and carry their kind`() {
        val ad = ReportTemplates.ad()
        assertEquals("ad", ad.shared.reportType)
        assertEquals(
            listOf("Report Info", "Day Progress", "Scenes Summary", "Cast", "Cast Not Shooting", "Cast Notes",
                "Stunt Performers", "Stunt Performers Notes", "Requirements", "Notes For Today"),
            ad.rows.map { it.cells.single().title },
        )
        assertEquals(15, ad.rows[3].cells.single().columns.size)

        val wrap = ReportTemplates.wrap()
        assertEquals("wrap", wrap.shared.reportType)
        assertEquals(listOf("Day Info", "Scenes", "Locations", "Notes"), wrap.rows.map { it.cells.single().title })
        assertEquals(CellKind.Notes, wrap.rows.last().cells.single().kind)
        assertNull(ReportTemplates.forKind(ReportKind.Production))
    }

    @Test
    fun `composing a wrap report keeps its kind and takes no crew sections`() {
        val crew = listOf(SheetMember("u1", "Ana", "Camera", "DoP"))
        val (composed, shootDay) = ComposeReport.newReport(
            template = ReportTemplates.wrap(),
            metadata = SheetMetadata(currentShootDay = 4, totalDays = "30"),
            members = crew,
            todayYmd = "2026-08-18",
            regenerateCrew = ReportKind.Wrap.generatesCrewSections,
        )
        assertEquals("wrap", composed.shared.reportType)
        assertEquals("5", composed.shared.shootDayNumber)
        assertEquals(5, shootDay)
        assertEquals(4, composed.rows.size, "only the production report regenerates crew sections")

        val (ad, _) = ComposeReport.newReport(
            template = ReportTemplates.ad(),
            metadata = SheetMetadata(),
            members = crew,
            todayYmd = "2026-08-18",
            regenerateCrew = ReportKind.Ad.generatesCrewSections,
        )
        assertEquals(ReportTemplates.ad().rows.size, ad.rows.size)

        val (production, _) = ComposeReport.newReport(
            template = ReportTemplates.ad(),
            metadata = SheetMetadata(),
            members = crew,
            todayYmd = "2026-08-18",
            regenerateCrew = ReportKind.Production.generatesCrewSections,
        )
        assertTrue(production.rows.size > ReportTemplates.ad().rows.size)
    }
}
