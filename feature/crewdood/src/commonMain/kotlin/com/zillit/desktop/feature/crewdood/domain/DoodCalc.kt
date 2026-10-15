package com.zillit.desktop.feature.crewdood.domain

import com.zillit.desktop.core.common.toDisplayLabel
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * Crew DOOD's model — the whole of the web's `lib/dood.js`, with no I/O, no
 * Compose and no clock in it, so every rule is unit-tested in
 * `DoodCalcTest`.
 *
 * Inputs are [DoodSources] (days, never stamps — see [DoodModels]); the output
 * is one [DoodModel] the grid reads straight off. Day arithmetic runs on
 * [LocalDate], which cannot be shortened by a daylight-saving change the way
 * "add 86,400,000 ms" can.
 */
@Suppress("TooManyFunctions") // One function per rule, each tested on its own.
object DoodCalc {

    /**
     * A chart this wide is already unusable; past it a malformed deal (an end
     * date decades out) would build tens of thousands of columns.
     */
    const val MAX_DAYS = 1100

    /**
     * A holiday range is saved as one day per entry — the API has no range
     * shape — capped so a mistyped year cannot add thousands.
     */
    const val MAX_HOLIDAY_RANGE = 366

    /**
     * Only these two book anyone (product decisions 2026-10-10 and
     * 2026-10-13). `draft`, `issued`, `awaiting_approval`, `approved`,
     * `rejected`, `completed` and `cancelled` book nobody, and the filter is
     * this client's because the endpoint takes no parameters.
     */
    val BOOKED_STATUSES: Set<String> = setOf("active", "deactivated")

    /** How many days the chart opens on when there is nothing at all to show. */
    private const val EMPTY_WINDOW_DAYS = 27

    // ── Project calendar ────────────────────────────────────────────────────

    /** The calendar the API's two fields describe; absent `weekly_off` means the default. */
    fun calendarFrom(weeklyOff: List<Int>?, holidays: List<DoodHoliday>): DoodCalendar {
        val off = (weeklyOff ?: DEFAULT_WEEKLY_OFF.toList())
            .filter { it in 0..<DAYS_PER_WEEK }
            .toSet()
        // First name wins, as the web's Map does: a duplicated date keeps the
        // name it was first given rather than the last one sent.
        val named = LinkedHashMap<LocalDate, String>()
        holidays.forEach { holiday -> named.putIfAbsent(holiday.date, holiday.name.trim()) }
        return DoodCalendar(weeklyOff = off, holidays = named)
    }

    /** Every day from [from] to [to], both included; empty when backwards, capped at [MAX_HOLIDAY_RANGE]. */
    fun datesBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var day = from
        while (day <= to && out.size < MAX_HOLIDAY_RANGE) {
            out += day
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    /** Holidays as the editor lists them: consecutive days with the same name are one row. */
    fun groupHolidays(holidays: List<DoodHoliday>): List<DoodHolidayRun> {
        val runs = mutableListOf<MutableHolidayRun>()
        holidays.sortedBy { it.date }.forEach { holiday ->
            val last = runs.lastOrNull()
            if (last != null && last.name == holiday.name && last.end.plus(1, DateTimeUnit.DAY) == holiday.date) {
                last.end = holiday.date
                last.dates += holiday.date
            } else {
                runs += MutableHolidayRun(holiday.date, holiday.date, holiday.name, mutableListOf(holiday.date))
            }
        }
        return runs.map { DoodHolidayRun(it.start, it.end, it.name, it.dates.toList()) }
    }

    private class MutableHolidayRun(
        val start: LocalDate,
        var end: LocalDate,
        val name: String,
        val dates: MutableList<LocalDate>,
    )

    // ── Crew rows ──────────────────────────────────────────────────────────

    /**
     * The crew list's own order — departments by the admin's department
     * priority, people by designation priority inside each.
     *
     * The DOOD has no units, so a department listed under several of them is
     * merged into its first appearance, and a person listed twice keeps their
     * first row.
     */
    fun departmentsFromCrewList(units: List<DoodCrewUnit>): List<DeptGroup> {
        val byKey = LinkedHashMap<String, MutableDeptGroup>()
        val seen = mutableSetOf<String>()
        units.forEach { unit ->
            unit.departments.forEach { dept ->
                val key = dept.aliases.firstOrNull { it.isNotBlank() } ?: dept.name
                if (key.isBlank()) return@forEach
                val row = byKey.getOrPut(key) {
                    MutableDeptGroup(key, dept.name, dept.aliases.filter { it.isNotBlank() }.toMutableList())
                }
                row.crew += dept.users.filter { it.userId.isBlank() || seen.add(it.userId) }.map(::toCrew)
            }
        }
        return byKey.values.map { DeptGroup(it.key, it.name, it.aliases.toList(), it.crew.toList()) }
    }

    private fun toCrew(user: DoodCrewUser) = DoodCrew(
        key = user.userId,
        userId = user.userId,
        name = user.name,
        role = user.role,
        picture = user.picture,
    )

    /** A department group while it is still being matched: [aliases] is dropped from the model. */
    data class DeptGroup(
        val key: String,
        val name: String,
        val aliases: List<String>,
        val crew: List<DoodCrew>,
    )

    private class MutableDeptGroup(
        val key: String,
        val name: String,
        val aliases: MutableList<String>,
        val crew: MutableList<DoodCrew> = mutableListOf(),
    )

    /**
     * Who a deal is for: `user_id`, else the crew name, else the deal itself.
     *
     * An external has no Zillit account (`is_external`; older deals simply
     * have no `user_id`), so their row is keyed on their name — which is also
     * why two externals with exactly the same name share one row (handoff §7).
     */
    fun personKey(deal: DoodDeal): String = when {
        deal.userId.isNotBlank() -> deal.userId
        deal.crewName.isNotBlank() -> "ext:${deal.crewName.lowercase()}"
        deal.id.isNotBlank() -> "ext:${deal.id}"
        else -> ""
    }

    /**
     * People with a booking deal but no crew-list row — externals, and users
     * no longer on the project (product decision, 2026-10-13).
     *
     * What the deal engaged them as comes from the deal first (Account Hub's
     * `resolveDealPerson` rule); the project's department master turns the
     * ids it names into words. [labelOf] translates a label key, humanising a
     * miss, as every other screen does.
     */
    fun externalCrew(
        deals: List<DoodDeal>,
        known: Set<String>,
        directory: List<DoodMasterDepartment>,
        labelOf: (String) -> String = ::humanise,
    ): List<ExternalCrew> {
        val designations = HashMap<String, DoodMasterDesignation>()
        directory.forEach { dept ->
            dept.designations.forEach { designation ->
                designation.aliases.forEach { alias -> designations.putIfAbsent(alias, designation) }
            }
        }
        val byKey = LinkedHashMap<String, ExternalCrew>()
        deals.forEach { deal ->
            if (deal.status !in BOOKED_STATUSES) return@forEach
            val key = personKey(deal)
            if (key.isBlank() || key in known || key in byKey) return@forEach
            val matched = deal.designationRefs.firstNotNullOfOrNull { designations[it] }
            val role = deal.customDesignation.trim().ifBlank {
                matched?.name?.takeIf { it.isNotBlank() }
                    ?: deal.designationRefs
                        .firstOrNull { it.startsWith(DESIGNATION_PREFIX) }
                        ?.removePrefix(DESIGNATION_PREFIX)
                        ?.let(labelOf)
                        .orEmpty()
            }
            byKey[key] = ExternalCrew(
                person = DoodCrew(
                    key = key,
                    userId = deal.userId,
                    name = deal.crewName.ifBlank { deal.reference },
                    role = role,
                    external = true,
                ),
                departmentRefs = deal.departmentRefs,
            )
        }
        return byKey.values.toList()
    }

    /** An external and the department refs their deal named, before they are placed. */
    data class ExternalCrew(val person: DoodCrew, val departmentRefs: List<String>)

    /**
     * Puts the externals at the end of their department, after the crew
     * list's people, by name.
     *
     * A department the crew list does not show becomes its own group after
     * the crew list's, in the master's order; refs the master does not know
     * come after those; and someone whose deal names no department at all
     * lands in "No department", last.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // One pass, in the web's order; splitting hides it.
    fun placeExternals(
        departments: List<DeptGroup>,
        externals: List<ExternalCrew>,
        directory: List<DoodMasterDepartment>,
        noDepartment: String,
        labelOf: (String) -> String = ::humanise,
    ): List<DeptGroup> {
        val master = HashMap<String, MasterHit>()
        directory.forEachIndexed { index, dept ->
            dept.aliases.forEach { alias -> master.putIfAbsent(alias, MasterHit(dept, index)) }
        }
        val byAlias = HashMap<String, DeptGroup>()
        // A crew-list department is also known by its master entry's names.
        val rows = departments.map { row ->
            val hit = row.aliases.firstNotNullOfOrNull { master[it] }
            val widened = if (hit == null) row else row.copy(aliases = (row.aliases + hit.dept.aliases).distinct())
            widened.aliases.forEach { alias -> byAlias.putIfAbsent(alias, widened) }
            widened
        }
        val extra = LinkedHashMap<String, MutableList<DoodCrew>>()
        rows.forEach { extra[it.key] = mutableListOf() }
        val added = mutableListOf<DeptGroup>()
        val orderOf = HashMap<String, Int>()
        externals.forEach { external ->
            val hit = external.departmentRefs.firstNotNullOfOrNull { master[it] }
            val refs = if (hit == null) {
                external.departmentRefs
            } else {
                (external.departmentRefs + hit.dept.aliases).distinct()
            }
            val lookup = refs.ifEmpty { listOf(NO_DEPARTMENT_ALIAS) }
            var row = lookup.firstNotNullOfOrNull { byAlias[it] }
            if (row == null) {
                val identifier = refs.firstOrNull { it.startsWith(DEPARTMENT_PREFIX) }
                row = DeptGroup(
                    key = "ext-dept:${refs.firstOrNull() ?: "none"}",
                    name = when {
                        hit != null -> hit.dept.name
                        identifier != null -> labelOf(identifier.removePrefix(DEPARTMENT_PREFIX))
                        else -> noDepartment
                    },
                    aliases = lookup,
                    crew = emptyList(),
                )
                orderOf[row.key] = when {
                    hit != null -> hit.index
                    refs.isNotEmpty() -> Int.MAX_VALUE - 1
                    else -> Int.MAX_VALUE
                }
                row.aliases.forEach { alias -> byAlias.putIfAbsent(alias, row) }
                added += row
                extra[row.key] = mutableListOf()
            }
            extra.getValue(row.key) += external.person
        }
        val tail = added.sortedWith(
            compareBy({ orderOf[it.key] ?: Int.MAX_VALUE }, { it.name.lowercase() }),
        )
        return (rows + tail).map { row ->
            row.copy(crew = row.crew + extra.getValue(row.key).sortedBy { it.name.lowercase() })
        }
    }

    private class MasterHit(val dept: DoodMasterDepartment, val index: Int)

    // ── Bookings ───────────────────────────────────────────────────────────

    /**
     * Each person's booking windows, keyed by [personKey] so an external's
     * several deals land on one row.
     *
     * WF is the earlier of the last pay day and the end date; a last pay day
     * before the start is ignored. No end date and no last pay day is
     * open-ended — the bar runs to the chart's last day, with no WF. A deal
     * with no start, or one that ends before it starts, is skipped.
     */
    fun bookingsFromDeals(deals: List<DoodDeal>): Map<String, List<DoodBooking>> {
        val byPerson = LinkedHashMap<String, MutableList<DoodBooking>>()
        deals.forEach { deal ->
            if (deal.status !in BOOKED_STATUSES) return@forEach
            val key = personKey(deal)
            val start = deal.start
            if (key.isBlank() || start == null) return@forEach
            val end = finishOf(start, deal.end, deal.lastPayDay)
            if (end != null && end < start) return@forEach
            byPerson.getOrPut(key) { mutableListOf() } += DoodBooking(start, end)
        }
        return byPerson
    }

    /**
     * WF: the earlier of the end date and the last pay day; null is
     * open-ended. A last pay day before the start is not a finish at all.
     */
    private fun finishOf(start: LocalDate, end: LocalDate?, lastPay: LocalDate?): LocalDate? {
        if (lastPay == null || lastPay < start) return end
        if (end == null) return lastPay
        return minOf(end, lastPay)
    }

    // ── The chart's window ─────────────────────────────────────────────────

    /**
     * Every booking and every shoot day, widened to whole Monday–Sunday weeks
     * so the Weeks view never shows a part week, and capped at [MAX_DAYS].
     * With nothing to show: this week plus the next three.
     */
    fun dateRange(
        bookings: Map<String, List<DoodBooking>>,
        shootDays: List<LocalDate>,
        today: LocalDate,
    ): List<LocalDate> {
        var min: LocalDate? = null
        var max: LocalDate? = null
        fun take(day: LocalDate?) {
            if (day == null) return
            if (min == null || day < min!!) min = day
            if (max == null || day > max!!) max = day
        }
        bookings.values.forEach { list -> list.forEach { take(it.start); take(it.end) } }
        shootDays.forEach(::take)
        val from = min ?: today
        val to = max ?: today.plus(EMPTY_WINDOW_DAYS, DateTimeUnit.DAY)
        val mondayOffset = (from.sundayBasedDay() + DAYS_PER_WEEK - 1) % DAYS_PER_WEEK
        val first = from.plus(-mondayOffset, DateTimeUnit.DAY)
        var last = to.plus((DAYS_PER_WEEK - to.sundayBasedDay()) % DAYS_PER_WEEK, DateTimeUnit.DAY)
        if (first.daysUntil(last) >= MAX_DAYS) last = first.plus(MAX_DAYS - 1, DateTimeUnit.DAY)
        return datesFromTo(first, last)
    }

    private fun datesFromTo(first: LocalDate, last: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var day = first
        while (day <= last) {
            out += day
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    // ── Phases ─────────────────────────────────────────────────────────────

    /**
     * Shoot Day *n* is the *n*th production date. Before the first is Prep,
     * counted back in weeks (Prep W1 = the seven days before it); after the
     * last is Post, counted on. A day between the two that is not a shoot day
     * is a Rest day, still inside the Shoot band. No production dates means
     * no phases at all — and no Jump to.
     */
    fun phasesFor(dates: List<LocalDate>, shootDays: List<LocalDate>): List<DoodPhase?> {
        if (shootDays.isEmpty()) return dates.map { null }
        val first = shootDays.first()
        val last = shootDays.last()
        val number = shootDays.withIndex().associate { (index, day) -> day to index + 1 }
        return dates.map { day ->
            val shootNumber = number[day]
            when {
                shootNumber != null -> DoodPhase(DoodBand.Shoot, DoodPhaseKind.Shoot, shootNumber)
                day < first -> DoodPhase(DoodBand.Prep, DoodPhaseKind.Prep, weeksBetween(day, first))
                day > last -> DoodPhase(DoodBand.Post, DoodPhaseKind.Post, weeksBetween(last, day))
                else -> DoodPhase(DoodBand.Shoot, DoodPhaseKind.Rest)
            }
        }
    }

    /** Whole weeks, rounded up — the web's `Math.ceil(days / 7)`. */
    private fun weeksBetween(from: LocalDate, to: LocalDate): Int {
        val days = from.daysUntil(to)
        return (days + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
    }

    // ── Cells ──────────────────────────────────────────────────────────────

    /**
     * One code per grid day for one person.
     *
     * A day off is never worked, not even a shoot day on one: a deal starting
     * on one shows SW on the next working day, one ending on one shows WF on
     * the last working day before, and a deal covering only days off shows
     * nothing. Days off between SW and WF read OFF, or HOL on a holiday —
     * outside a booking they stay blank, so the grid stays readable (the
     * column's own shading already says the day is off).
     *
     * Where a person's deals overlap, [DayCode.rank] decides: SWF beats SW
     * beats WF beats W beats HOL beats OFF, so a hand-over day reads as the
     * new deal's start.
     */
    @Suppress("CyclomaticComplexMethod") // Eight rules on one day; each branch IS a rule from §2.7.
    fun cellsFor(
        bookings: List<DoodBooking>?,
        dates: List<LocalDate>,
        calendar: DoodCalendar = DoodCalendar.Default,
    ): List<DayCode> {
        val cells = MutableList(dates.size) { DayCode.Blank }
        if (bookings.isNullOrEmpty() || dates.isEmpty()) return cells
        val first = dates.first()
        val last = dates.last()
        fun put(index: Int, code: DayCode) {
            if (code.rank > cells[index].rank) cells[index] = code
        }
        bookings.forEach { booking ->
            var start = booking.start
            var end = booking.end
            val limit = end ?: last
            while (start <= limit && calendar.isDayOff(start)) start = start.plus(1, DateTimeUnit.DAY)
            if (start > limit) return@forEach // the whole deal falls on days off
            // `start` is a working day now, so this walk stops at it at the latest.
            if (end != null) while (calendar.isDayOff(end!!)) end = end!!.plus(-1, DateTimeUnit.DAY)
            val stop = end ?: last
            if (stop < first || start > last) return@forEach
            val from = maxOf(0, first.daysUntil(start))
            val to = minOf(dates.size - 1, first.daysUntil(stop))
            for (index in from..to) {
                val day = dates[index]
                put(
                    index,
                    when {
                        calendar.isHoliday(day) -> DayCode.Hol
                        calendar.isWeeklyOff(day) -> DayCode.Off
                        else -> DayCode.Work
                    },
                )
            }
            if (end == start) {
                put(from, DayCode.Single)
                return@forEach
            }
            if (start >= first) put(from, DayCode.Start)
            if (end != null && end!! <= last) put(to, DayCode.Finish)
        }
        return cells
    }

    /** Worked days in a row — OFF and HOL do not count. */
    fun bookedDays(cells: List<DayCode>): Int = cells.count { it.worked }

    // ── The whole model ────────────────────────────────────────────────────

    fun buildDood(
        sources: DoodSources,
        today: LocalDate,
        noDepartment: String,
        labelOf: (String) -> String = ::humanise,
    ): DoodModel {
        val calendar = sources.calendar ?: DoodCalendar.Default
        val bookings = bookingsFromDeals(sources.deals)
        val shootDays = sources.shootDays.distinct().sorted()
        val dates = dateRange(bookings, shootDays, today)
        val phases = phasesFor(dates, shootDays)
        val listed = departmentsFromCrewList(sources.units)
        val known = listed.flatMap { group -> group.crew.map { it.key } }.filter { it.isNotBlank() }.toSet()
        val externals = externalCrew(sources.deals, known, sources.directory, labelOf)
        val placed = placeExternals(listed, externals, sources.directory, noDepartment, labelOf)
        val departments = placed.map { group ->
            DoodDepartment(
                key = group.key,
                name = group.name,
                crew = group.crew.map { person ->
                    val cells = cellsFor(bookings[person.key], dates, calendar)
                    person.copy(cells = cells, days = bookedDays(cells))
                },
            )
        }
        return DoodModel(
            dates = dates,
            phases = phases,
            departments = departments,
            calendar = calendar,
            hasPhases = shootDays.isNotEmpty(),
        )
    }

    // ── Columns ────────────────────────────────────────────────────────────

    /** Days view: one column per day. */
    fun dayColumns(
        dates: List<LocalDate>,
        phases: List<DoodPhase?>,
        calendar: DoodCalendar = DoodCalendar.Default,
    ): List<DoodColumn> = dates.mapIndexed { index, day ->
        DoodColumn(
            days = listOf(index),
            start = day,
            end = day,
            band = phases.getOrNull(index)?.band,
            label = phases.getOrNull(index),
            off = calendar.isWeeklyOff(day),
            holiday = calendar.holidays[day],
            workdays = if (calendar.isDayOff(day)) 0 else 1,
        )
    }

    /**
     * Weeks view: one column per seven days from the chart's first Monday.
     *
     * A column's band is the phase most of its days belong to; its label is
     * "Shoot Days a–b" when it holds shoot days, else its most common day
     * label. [DoodColumn.workdays] is what a week's figures count against.
     */
    fun weekColumns(
        dates: List<LocalDate>,
        phases: List<DoodPhase?>,
        calendar: DoodCalendar = DoodCalendar.Default,
    ): List<DoodColumn> {
        val columns = mutableListOf<DoodColumn>()
        var start = 0
        while (start < dates.size) {
            val days = (start until minOf(start + DAYS_PER_WEEK, dates.size)).toList()
            val band = mostCommon(days.map { phases.getOrNull(it)?.band })
            val shoot = days.mapNotNull { index ->
                phases.getOrNull(index)?.takeIf { it.kind == DoodPhaseKind.Shoot }?.n
            }
            val label = if (shoot.isNotEmpty()) {
                DoodPhase(
                    band = DoodBand.Shoot,
                    kind = DoodPhaseKind.Shoot,
                    n = shoot.first(),
                    to = shoot.last().takeIf { shoot.size > 1 },
                )
            } else {
                val common = mostCommon(days.map { labelKey(phases.getOrNull(it)) })
                days.firstOrNull { labelKey(phases.getOrNull(it)) == common }?.let { phases.getOrNull(it) }
            }
            columns += DoodColumn(
                days = days,
                start = dates[days.first()],
                end = dates[days.last()],
                band = band,
                label = label,
                off = false,
                holiday = null,
                workdays = days.count { !calendar.isDayOff(dates[it]) },
            )
            start += DAYS_PER_WEEK
        }
        return columns
    }

    private fun <T> mostCommon(values: List<T>): T? {
        var best: T? = null
        var bestCount = 0
        val counts = LinkedHashMap<T, Int>()
        values.forEach { value -> counts[value] = (counts[value] ?: 0) + 1 }
        counts.forEach { (value, count) ->
            if (count > bestCount) {
                best = value
                bestCount = count
            }
        }
        return best
    }

    private fun labelKey(phase: DoodPhase?): String = phase?.let { "${it.kind}:${it.n ?: ""}" }.orEmpty()

    /**
     * What one row shows in one column.
     *
     * A column with nothing worked still says so when a booking covers it —
     * HOL over OFF. A week whose only booked day is a one-day booking shows
     * SWF; otherwise a week with a start shows the start, else a finish, else
     * plain work.
     */
    @Suppress("CyclomaticComplexMethod") // The week's precedence, in one pass over its days.
    fun cellOf(cells: List<DayCode>, column: DoodColumn): DoodCell {
        var booked = 0
        var hasStart = false
        var hasFinish = false
        var hasSingle = false
        var rest = DayCode.Blank
        column.days.forEach { index ->
            val code = cells.getOrNull(index) ?: DayCode.Blank
            if (!code.worked) {
                if (code.rank > rest.rank) rest = code
                return@forEach
            }
            booked += 1
            when (code) {
                DayCode.Single -> hasSingle = true
                DayCode.Start -> hasStart = true
                DayCode.Finish -> hasFinish = true
                else -> Unit
            }
        }
        if (booked == 0) return DoodCell(rest, 0)
        if (hasSingle && booked == 1) return DoodCell(DayCode.Single, booked)
        val code = when {
            hasStart || hasSingle -> DayCode.Start
            hasFinish -> DayCode.Finish
            else -> DayCode.Work
        }
        return DoodCell(code, booked)
    }

    /** Crew on each grid day, over the rows given. */
    fun crewPerDay(rows: List<DoodCrew>, dayCount: Int): List<Int> {
        val counts = MutableList(dayCount) { 0 }
        rows.forEach { row ->
            for (index in 0 until dayCount) {
                if (row.cells.getOrNull(index)?.worked == true) counts[index] += 1
            }
        }
        return counts
    }

    /** Consecutive runs of equal values — the header's banded cells. */
    fun <T> runs(values: List<T>): List<Run<T>> {
        val out = mutableListOf<MutableRun<T>>()
        values.forEachIndexed { index, value ->
            val last = out.lastOrNull()
            if (last != null && last.value == value) last.count += 1 else out += MutableRun(value, index, 1)
        }
        return out.map { Run(it.value, it.from, it.count) }
    }

    data class Run<T>(val value: T, val from: Int, val count: Int)

    private class MutableRun<T>(val value: T, val from: Int, var count: Int)

    private const val DESIGNATION_PREFIX = "designation_"
    private const val DEPARTMENT_PREFIX = "department_"
    private const val NO_DEPARTMENT_ALIAS = "ext-dept:none"
}

/** A label key with no translation, as the crew list humanises one. */
fun humanise(key: String): String = key.toDisplayLabel()
