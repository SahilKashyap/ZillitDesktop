package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdPhase
import com.zillit.desktop.feature.addashboard.domain.AdPhases
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.DayFieldBinding
import com.zillit.desktop.feature.addashboard.domain.DayPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.PhaseState
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.ReportBlockType
import com.zillit.desktop.feature.addashboard.domain.ReportCell
import com.zillit.desktop.feature.addashboard.domain.ReportColumn
import com.zillit.desktop.feature.addashboard.domain.ReportFills
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportListRules
import com.zillit.desktop.feature.addashboard.domain.ReportPageRow
import com.zillit.desktop.feature.addashboard.domain.ReportRow
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.SubmitRules
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.domain.buildDayPatch
import com.zillit.desktop.feature.addashboard.domain.formatAmount
import com.zillit.desktop.feature.addashboard.domain.isDateEditable
import com.zillit.desktop.feature.addashboard.domain.isDayReadOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a shoot day is run, grouped and reported: the phase stepper, the
 * purchase-order split, the report's write-back onto the day, and the money.
 */
class AdShootDayRulesTest {

    private val shootDate = 1_772_755_200_000L

    @Suppress("LongParameterList") // A day row has many fields, and each test varies a different one.
    private fun row(
        id: String,
        attendance: AttendanceStatus = AttendanceStatus.Unknown,
        call: String = "",
        wrap: String = "",
        status: String = "pending",
        engagement: EngagementType = EngagementType.DirectPaye,
        agency: String = "",
        agencyId: String = "",
        currency: String = "GBP",
        gross: Double = 100.0,
        allowances: List<RateLine> = emptyList(),
    ) = SupportingArtistDay(
        id = id,
        artisteName = id,
        attendance = attendance,
        callTime = call,
        wrapTime = wrap,
        status = status,
        engagement = engagement,
        agencyName = agency,
        agencyId = agencyId,
        currency = currency,
        gross = gross,
        allowances = allowances,
    )

    // -- the phase stepper -----------------------------------------------------

    @Test
    fun `nothing is live until the list has rows`() {
        assertEquals(0, AdPhases.active(emptyList()))
        assertTrue(AdPhases.of(emptyList()).all { it.second == PhaseState.Todo })
    }

    @Test
    fun `phase one runs while the list exists and nobody is marked`() {
        assertEquals(AdPhase.ArrivalAndLists.number, AdPhases.active(listOf(row("s1"), row("s2"))))
    }

    @Test
    fun `phase two runs while some are marked and others are not`() {
        val rows = listOf(row("s1", attendance = AttendanceStatus.Present), row("s2"))
        assertEquals(AdPhase.Attendance.number, AdPhases.active(rows))
    }

    /**
     * Step 3 is labelled "Additionals" but means "attendance done, not
     * everyone wrapped" — ticking an allowance does not move it. The web's own
     * behaviour, pinned so the two agree.
     */
    @Test
    fun `phase three runs once everyone is handled but not everyone is wrapped`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, call = "07:00"),
            row("s2", attendance = AttendanceStatus.Absent),
        )
        assertEquals(AdPhase.Additionals.number, AdPhases.active(rows))

        val ticked = rows.map {
            if (it.id == "s1") it.copy(allowances = listOf(RateLine(label = "Costume", rateAmount = 10.0))) else it
        }
        assertEquals(AdPhase.Additionals.number, AdPhases.active(ticked), "an allowance does not move it")
    }

    @Test
    fun `phase four runs once every present artiste has a time out`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, call = "07:00", wrap = "19:30"),
            row("s2", attendance = AttendanceStatus.Absent),
        )
        assertEquals(AdPhase.SubmitAndPo.number, AdPhases.active(rows))
        assertTrue(AdPhases.readyToSubmit(rows))
    }

    /**
     * Step 4 rarely reads done after a submit.
     *
     * It checks **every** row — absent, blocked and draft included — while
     * submit moves only the present ones, so one absentee keeps it active. The
     * web behaves the same way, and the Submit Day shortcut stays visible
     * because of it.
     */
    @Test
    fun `an absentee keeps phase four live after a submit`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, call = "07:00", wrap = "19:30", status = "submitted"),
            row("s2", attendance = AttendanceStatus.Absent),
        )
        assertEquals(AdPhase.SubmitAndPo.number, AdPhases.active(rows))
    }

    @Test
    fun `all four read done once every row is submitted`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, call = "07:00", wrap = "19:30", status = "submitted"),
            row("s2", attendance = AttendanceStatus.Present, call = "07:05", wrap = "19:30", status = "approved"),
        )
        assertTrue(AdPhases.of(rows).all { it.second == PhaseState.Done })
    }

    /** Nobody present means nobody to wrap, so wrap is complete by definition. */
    @Test
    fun `a day of absentees counts as wrapped`() {
        val rows = listOf(row("s1", attendance = AttendanceStatus.Absent))
        assertTrue(AdPhases.everyoneWrapped(rows))
    }

    // -- the locks -------------------------------------------------------------

    @Test
    fun `a locked status, a future date or no posting right each close the day`() {
        assertFalse(isDayReadOnly(AdDayStatus.InProgress))
        assertTrue(isDayReadOnly(AdDayStatus.Submitted))
        assertTrue(isDayReadOnly(AdDayStatus.InProgress, dateEditable = false))
        assertTrue(isDayReadOnly(AdDayStatus.InProgress, canPost = false))
    }

    /** Today and a past day stay runnable; only the future is date-locked. */
    @Test
    fun `only a future date is locked by its date`() {
        assertTrue(isDateEditable(shootDate, today = shootDate))
        assertTrue(isDateEditable(shootDate - AdDates.MILLIS_PER_DAY, today = shootDate))
        assertFalse(isDateEditable(shootDate + AdDates.MILLIS_PER_DAY, today = shootDate))
    }

    // -- the purchase-order split ----------------------------------------------

    /**
     * One order per agency **and currency**.
     *
     * One agency paid in two currencies gets two orders; direct-PAYE artistes
     * are never on one at all.
     */
    @Test
    fun `an agency paid in two currencies gets two groups`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Scene One", agencyId = "v1", currency = "GBP", gross = 100.0),
            row("s2", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Scene One", agencyId = "v1", currency = "USD", gross = 120.0),
            row("s3", attendance = AttendanceStatus.Present, engagement = EngagementType.DirectPaye),
        )

        val groups = SubmitRules.agencyGroups(rows)
        assertEquals(2, groups.size)
        assertEquals(setOf("GBP", "USD"), groups.map { it.currency }.toSet())
        assertTrue(groups.all { it.agency == "Scene One" })
    }

    /** An agency with a name but no registered vendor cannot be raised against. */
    @Test
    fun `a group with no vendor is not raisable`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Unregistered Extras"),
        )

        assertFalse(SubmitRules.agencyGroups(rows).single().hasVendor)
    }

    @Test
    fun `a group's amount is the engine gross plus the day's flat lines`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Scene One", agencyId = "v1", gross = 100.0,
                allowances = listOf(
                    RateLine(label = "Costume", rateAmount = 10.0),
                    RateLine(label = "Agency %", rateType = "pct", rateAmount = 15.0),
                )),
        )

        // The percentage line carries no cash and is not added.
        assertEquals(110.0, SubmitRules.agencyGroups(rows).single().amount)
    }

    @Test
    fun `the totals split each currency into direct and agency`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, gross = 100.0),
            row("s2", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Scene One", agencyId = "v1", gross = 198.0),
            row("s3", attendance = AttendanceStatus.Absent, gross = 50.0),
        )

        val totals = SubmitRules.currencyTotals(rows).single()
        assertEquals(1, totals.directCount)
        assertEquals(100.0, totals.directTotal)
        assertEquals(1, totals.agencyCount)
        assertEquals(198.0, totals.agencyTotal)
        assertEquals(298.0, totals.total, "an absentee is not in the totals")
    }

    /** The day's own stored reference wins; otherwise `PO-D<day>-<yyyymmdd>`. */
    @Test
    fun `the PO reference is the day's own, or built from it`() {
        val day = AdShootDay(id = "d1", shootDate = shootDate)
        assertEquals("PO-D12-20260306", SubmitRules.poReference(day, shootDate, dayNumber = 12))
        assertEquals("PO-D0-20260306", SubmitRules.poReference(day, shootDate, dayNumber = null))
        assertEquals(
            "PO-EXISTING",
            SubmitRules.poReference(day.copy(poReference = "PO-EXISTING"), shootDate, 12),
        )
    }

    /**
     * A group is pending while no saved order on the day matches it, so a
     * failed create shows **Post PO** again.
     */
    @Test
    fun `a group with a saved order is no longer pending`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Scene One", agencyId = "v1", currency = "GBP"),
            row("s2", attendance = AttendanceStatus.Present, engagement = EngagementType.Agency,
                agency = "Extra! Extra!", agencyId = "v2", currency = "GBP"),
        )
        val groups = SubmitRules.agencyGroups(rows)
        val saved = listOf(DayPurchaseOrder(poId = "po1", agency = "Scene One", currency = "GBP"))

        assertEquals(listOf("Extra! Extra!"), SubmitRules.pendingGroups(groups, saved).map { it.agency })
    }

    /**
     * Submit needs one present artiste, the right and a signature — and
     * deliberately **not** a wrap check: present artistes with no time out are
     * submitted as they are.
     */
    @Test
    fun `submit is ready without every wrap`() {
        val rows = listOf(row("s1", attendance = AttendanceStatus.Present))

        assertTrue(SubmitRules.ready(rows, canEdit = true, hasSignature = true))
        assertFalse(SubmitRules.ready(rows, canEdit = false, hasSignature = true))
        assertFalse(SubmitRules.ready(rows, canEdit = true, hasSignature = false))
        assertFalse(SubmitRules.ready(emptyList(), canEdit = true, hasSignature = true))
    }

    @Test
    fun `the exceptions are the rows a submit leaves behind`() {
        val rows = listOf(
            row("s1", attendance = AttendanceStatus.Present),
            row("s2", attendance = AttendanceStatus.Absent),
            row("s3", status = "blocked"),
        )

        assertEquals(setOf("s2", "s3"), SubmitRules.exceptions(rows).map { it.id }.toSet())
    }

    // -- a report's write-back onto the day ------------------------------------

    private fun section(vararg rows: ReportRow) = listOf(
        ReportPageRow(
            id = "pr1",
            cells = listOf(
                ReportCell(
                    id = "cell1",
                    type = ReportBlockType.Section,
                    columns = listOf(ReportColumn("c1"), ReportColumn("c2")),
                    rows = rows.toList(),
                ),
            ),
        ),
    )

    @Test
    fun `a report writes its times and scenes onto the day`() {
        val layout = section(
            ReportRow(id = "r1", cells = listOf("Crew Call", ""), field = "call_time"),
            ReportRow(id = "r2", cells = listOf("Unit Wrap", ""), field = "wrap_time"),
            ReportRow(id = "r3", cells = listOf("Scenes Scheduled", ""), field = "scene_schedule"),
        )
        val fills = mapOf(
            ReportFills.key("cell1", "r1", 1) to "07:30",
            ReportFills.key("cell1", "r2", 1) to "19:45",
            ReportFills.key("cell1", "r3", 1) to "38, 39, 40",
        )

        val patch = buildDayPatch(layout, fills, shootDate)
        assertEquals(AdDates.hhmmToUtcEpoch(shootDate, "07:30"), patch.callTime)
        assertEquals(AdDates.hhmmToUtcEpoch(shootDate, "19:45"), patch.wrapTime)
        assertEquals(listOf("38", "39", "40"), patch.sceneSchedule)
    }

    /**
     * A **Set Call** is not the day's crew call.
     *
     * The label fallback matches anything holding "call", and the patch is
     * last-wins — so a Set Call row under a Crew Call row used to overwrite
     * the day's `call_time`, which is the field timecards show as Unit Call.
     * The guard runs before the tag, so even a mis-tagged template cannot
     * bypass it.
     */
    @Test
    fun `a set call never binds to the day's crew call`() {
        listOf("Set Call", "Shooting Call", "Camera Call", "First-shot Call").forEach { label ->
            assertNull(
                DayFieldBinding.of(ReportRow(id = "r1", cells = listOf(label, ""))),
                "$label must not bind",
            )
            assertNull(
                DayFieldBinding.of(ReportRow(id = "r1", cells = listOf(label, ""), field = "call_time")),
                "$label must not bind even when tagged",
            )
        }
        assertEquals("call_time", DayFieldBinding.of(ReportRow(id = "r1", cells = listOf("Crew Call", ""))))
    }

    /** An empty value is left out, so a report never wipes a day field. */
    @Test
    fun `an empty row writes nothing`() {
        val layout = section(ReportRow(id = "r1", cells = listOf("Crew Call", ""), field = "call_time"))

        assertTrue(buildDayPatch(layout, emptyMap(), shootDate).isEmpty)
    }

    /** A date move carries only the number; the old day's times stay with it. */
    @Test
    fun `a date move carries the day number alone`() {
        val layout = section(ReportRow(id = "r1", cells = listOf("Crew Call", "07:30"), field = "call_time"))

        val patch = buildDayPatch(layout, emptyMap(), shootDate, dayNumberOverride = 13, dayNumberOnly = true)
        assertEquals(13, patch.dayNumber)
        assertNull(patch.callTime)
        assertNull(patch.sceneSchedule)
    }

    /** A row with neither tag nor matching label is free-form report content. */
    @Test
    fun `an untagged row never touches the day`() {
        assertNull(DayFieldBinding.of(ReportRow(id = "r1", cells = listOf("Weather", "Rain"))))
    }

    // -- the report list -------------------------------------------------------

    private fun summary(id: String, kind: ReportKind, day: Long?, status: ReportStatus) =
        ReportSummary(id = id, kind = kind, name = id, status = status, shootDate = day)

    @Test
    fun `the list is newest day first, wrap before AD on the same day`() {
        val rows = listOf(
            summary("ad-today", ReportKind.AdReport, shootDate, ReportStatus.Draft),
            summary("wrap-today", ReportKind.WrapReport, shootDate, ReportStatus.Draft),
            summary("wrap-yesterday", ReportKind.WrapReport, shootDate - AdDates.MILLIS_PER_DAY, ReportStatus.Posted),
        )

        assertEquals(
            listOf("wrap-today", "ad-today", "wrap-yesterday"),
            ReportListRules.sort(rows).map { it.id },
        )
    }

    /**
     * A viewer without posting rights sees published reports only.
     *
     * Hiding the buttons alone would still leak a draft to anyone who kept the
     * row, so the filter is the gate.
     */
    @Test
    fun `a reader sees published reports only`() {
        val rows = listOf(
            summary("draft", ReportKind.WrapReport, shootDate, ReportStatus.Draft),
            summary("posted", ReportKind.WrapReport, shootDate, ReportStatus.Posted),
        )

        assertEquals(listOf("posted"), ReportListRules.filter(rows, canPost = false).map { it.id })
        assertEquals(2, ReportListRules.filter(rows, canPost = true).size)
    }

    @Test
    fun `a day link opens one report, offers a choice, or offers nothing`() {
        val one = listOf(summary("wrap", ReportKind.WrapReport, shootDate, ReportStatus.Draft))
        val two = one + summary("ad", ReportKind.AdReport, shootDate, ReportStatus.Draft)

        assertTrue(ReportListRules.dayLinkTarget(one, shootDate) is
            com.zillit.desktop.feature.addashboard.domain.DayLinkTarget.Open)
        assertEquals(
            com.zillit.desktop.feature.addashboard.domain.DayLinkTarget.Choose,
            ReportListRules.dayLinkTarget(two, shootDate),
        )
        assertEquals(
            com.zillit.desktop.feature.addashboard.domain.DayLinkTarget.None,
            ReportListRules.dayLinkTarget(two, shootDate + AdDates.MILLIS_PER_DAY),
        )
    }

    /** Only the AD Report asks for rows that carry no type. */
    @Test
    fun `only the AD report claims untyped rows`() {
        assertTrue(ReportKind.AdReport.includeUntyped)
        assertFalse(ReportKind.WrapReport.includeUntyped)
        assertFalse(ReportKind.ExhibitG.includeUntyped)
    }

    /** Exhibit G's layout is statutory, so only its rows may change. */
    @Test
    fun `exhibit G is the one locked layout`() {
        assertTrue(ReportKind.ExhibitG.layoutLocked)
        assertFalse(ReportKind.WrapReport.layoutLocked)
        assertFalse(ReportKind.ExhibitG.schedulePickableTemplate())
    }

    private fun ReportKind.schedulePickableTemplate(): Boolean =
        com.zillit.desktop.feature.addashboard.domain.ReportTemplate(id = "t", kind = this).schedulePickable

    // -- money -----------------------------------------------------------------

    /** Grouped, two decimals, and never rounded into a different number. */
    @Test
    fun `an amount reads grouped to two places`() {
        assertEquals("1,234.50", formatAmount(1234.5))
        assertEquals("0.00", formatAmount(0.0))
        assertEquals("142.00", formatAmount(142.0))
        assertEquals("-50.00", formatAmount(-50.0))
        assertEquals("1,234", formatAmount(1234.4, decimals = 0))
    }

    /** `cellId.rowId.col` splits from the right, because an id may hold a dot. */
    @Test
    fun `a fill key parses from the right`() {
        val key = ReportFills.key("cell.1", "row1", 2)
        val parsed = ReportFills.parse(key)!!
        assertEquals("cell.1", parsed.first)
        assertEquals("row1", parsed.second)
        assertEquals(2, parsed.third)
        assertNull(ReportFills.parse("not-a-key"))
    }
}
