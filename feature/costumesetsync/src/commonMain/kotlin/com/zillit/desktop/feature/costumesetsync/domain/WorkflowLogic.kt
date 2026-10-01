@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure logic of the workflow tickets (fittings, cleaning, alterations, damage,
 * missing): dates, the next pipeline stage, and the written "reminder" and
 * "send a request" messages the web builds in its screens. Kept free of
 * Compose so the wording and the line-trimming rules can be tested.
 */

/** Looks a screen string up by its web key (`csync_as`); placeholders are filled by [fill]. */
typealias Say = (String) -> String

/** An enum value in the service's own words. */
typealias EnumSay = (String?) -> String

/** `{n}`-style placeholders filled, as the web's `.replace('{n}', …)` chains. */
fun fill(text: String, vararg values: Pair<String, Any?>): String =
    values.fold(text) { acc, (name, value) -> acc.replace("{$name}", value?.toString().orEmpty()) }

/** What "Send a request" opens with: a subject, a body, and (for one record) which record it is about. */
data class RequestDraft(val title: String, val body: String, val entityId: String? = null)

private const val MAX_TITLE = 160
private const val MAX_LINE = 160
private const val MAX_CHASE_LINES = 20
private const val MS_PER_DAY = 86_400_000L

/**
 * Keeps every non-blank line and a blank only after a non-blank one — the web's `.filter((line, i, all) => line ||
 * all[i-1])`.
 */
fun tidyLines(lines: List<String>): List<String> =
    lines.filterIndexed { i, line -> line.isNotEmpty() || (i > 0 && lines[i - 1].isNotEmpty()) }

/** The subject of a request, clipped to the service's limit. */
fun clipTitle(title: String): String = title.take(MAX_TITLE)

/**
 * `2026-03-05` + `14:05` → epoch ms in [zone]; null when the date is not a date. A blank or odd time reads as midnight.
 */
fun dateTimeMs(date: String, time: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: return null
    val clock = runCatching { LocalTime.parse(time.trim().padStart(TIME_PAD, '0')) }.getOrNull() ?: LocalTime(0, 0)
    return day.atTime(clock).toInstant(zone).toEpochMilliseconds()
}

private const val TIME_PAD = 5

/** Epoch ms → (`YYYY-MM-DD`, `HH:mm`) for the two fields of a date-time input; blanks for an unset value. */
fun splitDateTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): Pair<String, String> {
    val d = localTime(ms, zone) ?: return "" to ""
    return "${d.year}-${two(d.monthNumber)}-${two(d.dayOfMonth)}" to "${two(d.hour)}:${two(d.minute)}"
}

private fun two(n: Int) = n.toString().padStart(2, '0')

/** `Thu, 5 Mar 2026 14:05` — the fitting page's subtitle (the web's dayjs `ddd, D MMM YYYY HH:mm`). */
fun fmtDateTimeLong(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val d: LocalDateTime = localTime(ms, zone) ?: return ""
    val weekday = when (d.dayOfWeek) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        else -> "Sun"
    }
    return "$weekday, ${fmtDate(ms, zone)} ${two(d.hour)}:${two(d.minute)}"
}

/** Local midnight of the day [now] falls on. */
fun startOfDay(now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val day = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    return day.atTime(LocalTime(0, 0)).toInstant(zone).toEpochMilliseconds()
}

/** True when [ms] is a real timestamp that falls on the same local day as [now]. */
fun isSameDay(ms: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Boolean =
    ms != 0L && ms >= startOfDay(now, zone) && ms < startOfDay(now, zone) + MS_PER_DAY

/** The stage after [status] in [pipeline], or null at the end or when the status is not on it. */
fun nextStage(pipeline: List<String>, status: String): String? {
    val i = pipeline.indexOf(status)
    return if (i >= 0 && i < pipeline.lastIndex) pipeline[i + 1] else null
}

// -- fittings -----------------------------------------------------------------

/** The actor being called in: the fitting's own, else the character's cast actor. */
fun fittingActorName(fitting: Rec): String =
    fitting.rec("actor")?.str("name").orEmpty().ifBlank {
        fitting.rec("character")?.rec("actor")?.str("name").orEmpty()
    }

/** Items of a fitting already ticked off. */
fun fittedCount(fitting: Rec): Int = fitting.recs("items").count { it.str("status") == "FITTED" }

private val OPEN_FITTING = setOf("SCHEDULED", "IN_PROGRESS")

/**
 * "Send reminder request" for fittings: every fitting still open — not what the
 * filters show. What is still to come leads; a slot already gone by follows,
 * marked missed.
 */
fun fittingChase(fittings: List<Rec>, today: Long, projectName: String, t: Say): RequestDraft {
    val still = fittings.filter { it.str("status") in OPEN_FITTING }.sortedBy { it.long("scheduled_at") }
    val soon = still.filter { it.long("scheduled_at") >= today }
    val upcoming = soon + still.filter { it.long("scheduled_at") < today }
    val missed = still.size - soon.size
    val lines = upcoming.map { x ->
        val who = fittingActorName(x).ifBlank { t("csync_actor_not_cast") }
        val place = x.str("location").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val gone = if (x.long("scheduled_at") < today) " · ${t("csync_missed")}" else ""
        val character = x.rec("character")?.str("name").orEmpty()
        val line = "${fmtDateTime(x.long("scheduled_at"))} — $who ${t("csync_as")} $character$place$gone"
        "· ${line.take(MAX_LINE)}"
    }
    val title = clipTitle("${t("csync_fitting_chase_title")} · $projectName")
    if (upcoming.isEmpty()) return RequestDraft(title, t("csync_fitting_chase_empty"))
    val lead = fill(
        t(if (upcoming.size == 1) "csync_fitting_chase_lead_one" else "csync_fitting_chase_lead_many"),
        "n" to upcoming.size
    )
    val tail = if (missed > 0) fill(t("csync_fitting_chase_missed"), "n" to missed) else ""
    val more = if (
        lines.size > MAX_CHASE_LINES
    ) "\n· ${fill(t("csync_and_n_more"), "n" to lines.size - MAX_CHASE_LINES)}" else ""
    return RequestDraft(
        title,
        "$lead$tail:\n${lines.take(MAX_CHASE_LINES).joinToString("\n")}$more\n\n${t("csync_fitting_chase_ask")}"
    )
}

/** "Schedule & send": the request about the fitting just booked. */
@Suppress("LongParameterList")
fun fittingSendDraft(
    fittingId: String,
    characterName: String,
    actorName: String,
    whenMs: Long,
    location: String,
    pieces: List<String>,
    notes: String,
    t: Say,
): RequestDraft {
    val who = characterName + if (actorName.isNotBlank()) " ($actorName)" else ""
    val head = fill(
        t("csync_fitting_send_body"),
        "character" to who,
        "date" to fmtDate(whenMs),
        "time" to fmtTime(whenMs)
    ) +
        (if (location.isNotBlank()) ", $location" else "") + "."
    val lines = listOf(
        head,
        if (pieces.isNotEmpty()) "${t("csync_pieces")}: ${pieces.joinToString(", ")}" else "",
        notes.trim(),
        "",
        t("csync_fitting_send_ask"),
    )
    return RequestDraft(
        clipTitle("${t("csync_fitting")} · $characterName"),
        tidyLines(lines).joinToString("\n"),
        fittingId
    )
}

// -- cleaning -----------------------------------------------------------------

/** A ticket is off the board once it is one of these. */
val CLEANING_CLOSED = setOf("READY", "CANCELLED")

/** The stages the web falls back to when the ticket carries none. */
val DEFAULT_CLEANING_PIPELINE = listOf(
    "REQUESTED",
    "RECEIVED",
    "CLEANING",
    "DRYING",
    "IRONING",
    "QUALITY_CHECK",
    "READY"
)

/** "Send reminder request" for the whole sink, emergencies first. */
fun cleaningChase(tickets: List<Rec>, projectName: String, t: Say, e: EnumSay): RequestDraft {
    val open = tickets.sortedByDescending { it.bool("is_emergency") }
    val lines = open.map { i ->
        val costume = i.rec("costume")
        val siren = if (i.bool("is_emergency")) "🚨 " else ""
        val by = i
            .long("expected_ready_at").takeIf { it != 0L }?.let {
                " · ${t("csync_needed_by_lower")} ${fmtDateTime(it)}"
            }
            .orEmpty()
        val asset = costume?.str("asset_number").orEmpty()
        val line = "$siren$asset ${costume?.str("name").orEmpty()} — ${i.str("problem")} · ${e(i.str("status"))}$by"
        "· ${line.take(MAX_LINE)}"
    }
    val title = clipTitle("${t("csync_cleaning_chase_title")} · $projectName")
    if (open.isEmpty()) return RequestDraft(title, t("csync_cleaning_chase_empty"))
    val lead = fill(
        t(if (open.size == 1) "csync_cleaning_chase_lead_one" else "csync_cleaning_chase_lead_many"),
        "n" to open.size
    )
    val more = if (
        lines.size > MAX_CHASE_LINES
    ) "\n· ${fill(t("csync_and_n_more"), "n" to lines.size - MAX_CHASE_LINES)}" else ""
    return RequestDraft(
        title,
        "$lead\n${lines.take(MAX_CHASE_LINES).joinToString("\n")}$more\n\n${t("csync_cleaning_chase_ask")}"
    )
}

/** "Request & send": the request about the ticket just filed. */
@Suppress("LongParameterList")
fun cleaningSendDraft(
    ticketId: String,
    piece: String,
    problem: String,
    cleaningType: String,
    priority: String,
    neededByMs: Long?,
    notes: String,
    t: Say,
    e: EnumSay,
): RequestDraft {
    val by = neededByMs?.let { " · ${t("csync_needed_by_lower")} ${fmtDateTime(it)}" }.orEmpty()
    val lines = listOf(
        fill(t("csync_cleaning_send_body"), "piece" to piece, "problem" to problem),
        "${e(cleaningType)} · ${fill(t("csync_priority_n"), "p" to e(priority))}$by",
        notes.trim(),
        "",
        t("csync_cleaning_send_ask"),
    )
    return RequestDraft(clipTitle("${t("csync_cleaning")} · $piece"), tidyLines(lines).joinToString("\n"), ticketId)
}

// -- alterations / damages / missing ---------------------------------------------

/** Which of the three ticket boards. */
enum class TicketBoard(val route: String, val entity: String, val kind: String, val closed: Set<String>) {
    Alterations("alterations", "ALTERATION", "alteration", setOf("COMPLETED", "CANCELLED")),
    Damages("damages", "DAMAGE", "damage", setOf("REPAIRED", "WRITTEN_OFF")),
    Missing("missing", "MISSING", "missing", setOf("FOUND", "WRITTEN_OFF")),
}

/** One open ticket as the reminder writes it. */
fun ticketChaseLine(board: TicketBoard, r: Rec, t: Say, e: EnumSay): String {
    val costume = r.rec("costume")
    val piece = "${costume?.str("asset_number").orEmpty()} ${costume?.str("name").orEmpty()}"
    return when (board) {
        TicketBoard.Alterations -> {
            val who = r.rec("character")?.let { " (${it.str("name")})" }.orEmpty()
            val tailor = r
                .str("tailor_name").takeIf { it.isNotBlank() }?.let { " · ${fill(t("csync_with_n"), "n" to it)}" }
                .orEmpty()
            val due = r
                .long("deadline").takeIf { it != 0L }?.let {
                    " · ${fill(t("csync_due_lower_n"), "date" to fmtDateTime(it))}"
                }
                .orEmpty()
            "$piece$who — ${r.str("issue")} → ${r.str("required_work")} · ${e(r.str("status"))}$tailor$due"
        }
        TicketBoard.Damages -> {
            val scene = r.rec("scene")?.let { " · ${t("csync_sc")} ${it.str("number")}" }.orEmpty()
            "$piece — ${r.str("description")} · ${e(r.str("status"))}$scene"
        }
        TicketBoard.Missing -> {
            val who = costume?.rec("character")?.let { " (${it.str("name")})" }.orEmpty()
            val seen = fill(
                t("csync_last_seen_lower_n"),
                "x" to r.str("last_seen_location").ifBlank { t("csync_nobody_knows_where") }
            )
            val with = r
                .str("last_assigned_to").takeIf { it.isNotBlank() }?.let { " · ${fill(t("csync_with_n"), "n" to it)}" }
                .orEmpty()
            "$piece$who — $seen$with"
        }
    }
}

/** "Send reminder request" on a ticket board: everything still open, one message. */
fun ticketChase(board: TicketBoard, open: List<Rec>, projectName: String, t: Say, e: EnumSay): RequestDraft {
    val tab = board.route
    val lines = open.map { ticketChaseLine(board, it, t, e) }
    val title = clipTitle("${t("csync_chase_title_$tab")} · $projectName")
    if (lines.isEmpty()) return RequestDraft(title, t("csync_chase_empty_$tab"))
    val lead = fill(
        t(if (lines.size == 1) "csync_chase_lead_${tab}_one" else "csync_chase_lead_$tab"),
        "n" to lines.size
    )
    val shown = lines.take(MAX_CHASE_LINES).joinToString("\n") { "· ${it.take(MAX_LINE)}" }
    val more = if (
        lines.size > MAX_CHASE_LINES
    ) "\n· ${fill(t("csync_and_n_more"), "n" to lines.size - MAX_CHASE_LINES)}" else ""
    return RequestDraft(title, "$lead\n$shown$more\n\n${t("csync_chase_ask_$tab")}")
}

/** The fields a ticket form's "report & send" message reads. */
data class TicketFormValues(
    val issue: String = "",
    val requiredWork: String = "",
    val tailor: String = "",
    val deadlineMs: Long? = null,
    val description: String = "",
    val estimatedCost: String = "",
    val lastSeen: String = "",
    val lastAssignedTo: String = "",
    val notes: String = "",
)

/** "Report & send": the request's opening lines, then a blank line and the ask, as the reference writes it. */
fun ticketSendDraft(board: TicketBoard, id: String, piece: String, v: TicketFormValues, t: Say): RequestDraft {
    val (lines, ask) = when (board) {
        TicketBoard.Alterations -> listOf(
            fill(t("csync_alteration_send_body"), "piece" to piece, "issue" to v.issue, "work" to v.requiredWork),
            if (v.tailor.isNotBlank()) fill(t("csync_tailor_n"), "x" to v.tailor) else "",
            v.deadlineMs?.let { fill(t("csync_due_n"), "date" to fmtDateTime(it)) }.orEmpty(),
        ) to t("csync_alteration_send_ask")
        TicketBoard.Damages -> listOf(
            fill(t("csync_damage_send_body"), "piece" to piece, "damage" to v.description),
            if (v.estimatedCost.isNotBlank()) fill(t("csync_est_repair_colon"), "x" to v.estimatedCost) else "",
        ) to t("csync_damage_send_ask")
        TicketBoard.Missing -> listOf(
            fill(t("csync_missing_send_body"), "piece" to piece),
            if (v.lastSeen.isNotBlank()) fill(t("csync_last_seen_n"), "x" to v.lastSeen) else "",
            if (v.lastAssignedTo.isNotBlank()) "${t("csync_last_with")}: ${v.lastAssignedTo}" else "",
            v.notes,
        ) to t("csync_missing_send_ask")
    }
    val title = clipTitle("${t("csync_ticket_kind_${board.kind}")} · $piece")
    return RequestDraft(title, "${lines.filter { it.isNotEmpty() }.joinToString("\n")}\n\n$ask", id)
}

/** A relative time (`5 minutes ago`, `in 2 hours`) from the web's `relativeTime`, using the same message keys. */
fun relativeTime(ms: Long, now: Long, t: Say): String {
    val mins = ((now - ms) / MS_PER_MIN.toDouble()).let { kotlin.math.round(it).toLong() }
    val hours = kotlin.math.round(mins / MIN_PER_HOUR.toDouble()).toLong()
    fun say(past: String, future: String, n: Long) = fill(t(if (n > 0) past else future), "n" to kotlin.math.abs(n))
    return when {
        ms == 0L -> ""
        kotlin.math.abs(mins) < 1 -> t("csync_just_now")
        kotlin.math.abs(mins) < MIN_PER_HOUR -> say("csync_minutes_ago", "csync_in_minutes", mins)
        kotlin.math.abs(hours) < HOUR_PER_DAY -> say("csync_hours_ago", "csync_in_hours", hours)
        else -> say("csync_days_ago", "csync_in_days", kotlin.math.round(hours / HOUR_PER_DAY.toDouble()).toLong())
    }
}

private const val MS_PER_MIN = 60_000L
private const val MIN_PER_HOUR = 60L
private const val HOUR_PER_DAY = 24L

/** Percent-encodes [text] for a URL query value (`wa.me/…?text=`, `mailto:…?subject=`). */
fun urlEncode(text: String): String = buildString {
    text.encodeToByteArray().forEach { b ->
        val c = b.toInt() and BYTE_MASK
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < ASCII_LIMIT || ch in "-_.~") append(ch) else append('%')
            .append(HEX[c shr NIBBLE])
            .append(HEX[c and NIBBLE_MASK])
    }
}

private const val HEX = "0123456789ABCDEF"
private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 128
private const val NIBBLE = 4
private const val NIBBLE_MASK = 0xF

/** Digits only — a phone number as `wa.me/<n>` wants it. */
fun digitsOnly(text: String): String = text.filter { it.isDigit() }

/**
 * A request about one record from its row's Send request (the web's `RecordActions`): the subject is the
 * record's title plus the production (added once), the body its summary and then what is being asked.
 * The web adds a link to the record; the desktop has no web address to link to, so it leaves it out.
 */
fun recordRequestDraft(
    entityId: String,
    title: String,
    summary: String,
    ask: String,
    projectName: String
): RequestDraft {
    val subject = title + if (projectName.isNotBlank() && !title.contains(projectName)) " · $projectName" else ""
    return RequestDraft(clipTitle(subject), "$summary\n\n$ask", entityId)
}

/**
 * An actor's measurements as label/value pairs. The service sends an object, but an older record can hold
 * it as a JSON string — both read the same. Blank values are left out.
 */
fun measurementsOf(actor: Rec?): List<Pair<String, String>> {
    val raw = actor?.json?.get("measurements") ?: return emptyList()
    val obj = when (raw) {
        is JsonObject -> raw
        is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
        else -> null
    } ?: return emptyList()
    return obj.entries.mapNotNull { (key, value) ->
        val text = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) null else key to text
    }
}

/**
 * An epoch-ms deadline as the ISO instant the alteration endpoints take (alterations are not epoch ms, unlike scenes
 * and fittings).
 */
fun isoInstant(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString()
