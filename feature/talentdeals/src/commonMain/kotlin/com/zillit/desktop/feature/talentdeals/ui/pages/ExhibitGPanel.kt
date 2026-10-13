package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.DayLine
import com.zillit.desktop.feature.talentdeals.domain.SagFacts
import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.SigningRow
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentDates
import com.zillit.desktop.feature.talentdeals.domain.TalentExhibitG
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.ui.ExhibitState
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent

/**
 * Exhibit G — the SAG-AFTRA daily time report, for ONE talent
 * (`components/ExhibitG.jsx`).
 *
 * The days come from the AD dashboard's Exhibit G reports (one per shoot
 * day), filtered server-side to the rows whose Cast cell names this talent,
 * each with its signed state. What the contract adds is the day rate its fee
 * works back to and the thresholds the agreement is read against. Times are
 * recorded on the day by the AD department and read from those reports; the
 * API works out each day's facts (`sag`) and this panel prices them with the
 * contract's day rate.
 *
 * Offered on one combination only (Cast + SAG); see `TalentRoles.exhibitGApplies`.
 */
@Composable
internal fun ExhibitGPanel(
    performer: String,
    character: String,
    fee: Double,
    weeks: Double,
    symbol: String,
    exhibit: ExhibitState,
    onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val (dayRate, basis) = TalentExhibitG.dayRate(fee, weeks)
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(str(S.desktop_td_eg_title), style = ZillitTheme.typography.titleMedium)
        ZillitText(
            text = str(
                S.desktop_td_eg_subtitle,
                TalentExhibitG.WORK_DAY_HOURS,
                TalentExhibitG.TURNAROUND_HOURS,
                TalentExhibitG.MEAL_INTERVAL_HOURS,
                TalentNumbers.formatMoney(dayRate, symbol),
                basis,
            ),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = Int.MAX_VALUE,
        )

        when {
            performer.isBlank() -> Hint(str(S.desktop_td_eg_enter_name))
            exhibit.loading -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                repeat(2) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 40.dp) }
            }
            exhibit.error != null -> Hint(exhibit.error, error = true)
            exhibit.days.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                DayHeading(str(S.desktop_td_eg_daily_report)) { Tag(str(S.desktop_td_eg_no_days)) }
                Grid(performer, character, row = null, signatureLoader = signatureLoader)
            }
            else -> exhibit.days.forEach { day ->
                DayBlock(day, exhibit, performer, character, dayRate, symbol, onEvent, signatureLoader)
            }
        }

        Hint(str(S.desktop_td_eg_footnote))
    }
}

@Composable
private fun DayBlock(
    day: SigningDay,
    exhibit: ExhibitState,
    performer: String,
    character: String,
    dayRate: Double,
    symbol: String,
    onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
) {
    val row = TalentExhibitG.chooseRow(day, exhibit.picked[day.key], performer)
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        DayHeading(day.shootDate?.let(TalentDates::shootDay) ?: str(S.desktop_undated)) {
            DayFacts(row.sag)
            DayFlag(row)
        }
        if (day.rows.size > 1) RowPicker(day, row) { onEvent(TalentDealsEvent.PickExhibitRow(day.key, it)) }
        Grid(performer, character, row, signatureLoader)
        DayCost(TalentExhibitG.dayLines(row.sag, dayRate), symbol)
    }
}

@Composable
private fun DayHeading(title: String, flags: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        flags()
    }
}

@Composable
private fun Tag(text: String, tone: StatusTone = StatusTone.Neutral) = ZillitStatusPill(label = text, tone = tone)

/** A day's flags, as the design sets them beside the date. */
@Composable
private fun DayFacts(sag: SagFacts?) {
    if (sag == null) return
    val clean = !sag.turnaroundShort && sag.mealPenalties == 0 && !sag.holiday && sag.consecutiveDay < SIXTH_DAY
    sag.workedMinutes?.let { Tag(str(S.desktop_td_eg_worked, TalentExhibitG.fmtDuration(it))) }
    sag.turnaroundMinutes?.let {
        val text = str(S.desktop_cr_turnaround, TalentExhibitG.fmtDuration(it))
        if (sag.turnaroundShort) {
            Tag("$text — ${str(S.desktop_td_eg_short)}", StatusTone.Rejected)
        } else {
            Tag(text, StatusTone.Ready)
        }
    }
    if (sag.mealPenalties > 0) {
        val key = if (sag.mealPenalties == 1) S.desktop_td_eg_meal_penalty_one else S.desktop_td_eg_meal_penalty_other
        Tag(str(key, sag.mealPenalties), StatusTone.Rejected)
    }
    if (sag.forcedCall) Tag(str(S.desktop_td_eg_forced_call), StatusTone.Rejected)
    if (sag.holiday) Tag(str(S.desktop_holiday), StatusTone.Rejected)
    // Counted as consecutive days worked, so "in a row" rather than "of the week".
    if (sag.consecutiveDay >= SIXTH_DAY) Tag(str(S.desktop_td_eg_day_in_row, sag.consecutiveDay), StatusTone.Rejected)
    if (clean) Tag(str(S.desktop_td_eg_no_penalties), StatusTone.Ready)
}

@Composable
private fun DayFlag(row: SigningRow) {
    if (row.signed) Tag(str(S.signed), StatusTone.Ready) else Tag(str(S.desktop_td_eg_not_signed), StatusTone.Rejected)
}

/**
 * Several rows on one day's sheet match the name — a project member, an
 * artiste and a name written on the sheet can share it, and nothing tells which
 * one is this talent. The picked row then stands for the talent: its grid row,
 * flags, signed state and cost.
 */
@Composable
private fun RowPicker(day: SigningDay, selected: SigningRow, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(
            str(S.desktop_td_eg_rows_match, day.rows.size),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitSelect(
            value = selected,
            options = day.rows,
            onSelect = { onPick(it.key) },
            label = ::rowLabel,
            subtitle = { TalentExhibitG.sheetCell(it, CHARACTER_COL).ifBlank { str(S.desktop_csync_no_character) } },
            modifier = Modifier.width(PICKER_WIDTH),
        )
    }
}

/** A row in one line — the Cast name, then its Character and Code. */
private fun rowLabel(row: SigningRow): String =
    listOf(row.name.ifBlank { "—" }, TalentExhibitG.sheetCell(row, CHARACTER_COL), TalentExhibitG.sheetCell(row,
        CODE_COL))
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/** A day priced line by line, then its total — the design's cost list. */
@Composable
private fun DayCost(lines: List<DayLine>, symbol: String) {
    if (lines.isEmpty()) return
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ZillitText("${line.label} · ${line.detail}", style = ZillitTheme.typography.bodyMedium,
                    color = colors.textSecondary)
                ZillitText(TalentNumbers.formatMoney(line.amount, symbol), style = ZillitTheme.typography.numeric)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ZillitText(str(S.desktop_td_eg_day_total),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            ZillitText(
                TalentNumbers.formatMoney(TalentExhibitG.total(lines), symbol),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

// -- the document's grid ------------------------------------------------------------------

/** The leaf columns in the order the statutory sheet draws them, each with its group heading when it has one. */
private fun gridHeadings(): List<String> = listOf(
    str(S.desktop_draft_cast),
    str(S.desktop_td_eg_g_minor),
    str(S.character),
    str(S.code),
    str(S.desktop_td_eg_g_report_mw),
    "${str(S.desktop_td_eg_g_work_time)}\n${str(S.desktop_td_eg_g_report_on_set)}",
    "${str(S.desktop_td_eg_g_work_time)}\n${str(S.desktop_td_eg_g_dismiss_on_set)}",
    str(S.desktop_td_eg_g_dismiss_mw),
    "${str(S.desktop_td_eg_g_nd_meal)}\n${str(S.desktop_td_eg_g_in)}",
    "${str(S.desktop_td_eg_g_nd_meal)}\n${str(S.desktop_td_eg_g_out)}",
    "${str(S.desktop_td_eg_g_first_meal)}\n${str(S.start)}",
    "${str(S.desktop_td_eg_g_first_meal)}\n${str(S.desktop_td_eg_g_finish)}",
    "${str(S.desktop_td_eg_g_second_meal)}\n${str(S.start)}",
    "${str(S.desktop_td_eg_g_second_meal)}\n${str(S.desktop_td_eg_g_finish)}",
    "${str(S.desktop_td_eg_g_travel)}\n${str(S.desktop_td_eg_g_leave_for_location)}",
    "${str(S.desktop_td_eg_g_travel)}\n${str(S.desktop_td_eg_g_arrive_on_location)}",
    "${str(S.desktop_td_eg_g_travel)}\n${str(S.desktop_td_eg_g_leave_location)}",
    "${str(S.desktop_td_eg_g_travel)}\n${str(S.desktop_td_eg_g_arrive_studio)}",
    str(S.desktop_td_eg_g_stunt_adjust),
    str(S.desktop_td_eg_g_minors_tutoring),
    str(S.desktop_td_eg_g_outfits),
    str(S.desktop_td_eg_forced_call),
    str(S.desktop_td_eg_g_mpvs),
    str(S.desktop_td_eg_g_signature),
    str(S.desktop_td_eg_g_objection),
)

/**
 * One performer row, as the day's report has it — name, Minor?, Character, the
 * times — then the signature; a dash where the sheet is blank. With no report
 * row (the blank grid) Character falls back to the contract's role.
 */
@Composable
private fun Grid(performer: String, character: String, row: SigningRow?,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?) {
    val colors = ZillitTheme.colors
    val scroll = rememberScrollState()
    val values = row?.values?.takeIf { it.size == TalentExhibitG.GRID_COLUMNS }
    val headings = remember { gridHeadings() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .horizontalScroll(scroll),
    ) {
        Row(Modifier.background(colors.surfaceSunken)) {
            headings.forEach { Cell(it, header = true) }
        }
        Row {
            repeat(TalentExhibitG.GRID_COLUMNS) { col ->
                when {
                    col == NAME_COL -> Cell(row?.name?.ifBlank { null } ?: performer.ifBlank { "—" }, bold = true)
                    col == CHARACTER_COL && values == null -> Cell(character.ifBlank { "—" })
                    col == FORCED_CALL_COL && values?.get(col).isNullOrEmpty() && row?.sag != null ->
                        Cell(if (row.sag.forcedCall) "Y" else "N", bad = row.sag.forcedCall)
                    col == SIGNATURE_COL -> SignatureCell(row, signatureLoader)
                    col == TalentExhibitG.GRID_COLUMNS - 1 -> Cell(values?.get(col).orEmpty())
                    else -> Cell(values?.get(col)?.ifBlank { null } ?: "—")
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false, bold: Boolean = false, bad: Boolean = false) {
    val colors = ZillitTheme.colors
    Box(Modifier.width(CELL_WIDTH).height(if (header) HEADER_HEIGHT else ROW_HEIGHT).padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart) {
        ZillitText(
            text = text,
            style = when {
                header -> ZillitTheme.typography.labelSmall
                bold -> ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                else -> ZillitTheme.typography.bodySmall
            },
            color = if (bad) colors.danger else if (header) colors.textMuted else colors.textPrimary,
            textAlign = TextAlign.Start,
            maxLines = if (header) 2 else 1,
        )
    }
}

/** The signature cell: the drawn mark when there is one, the state otherwise. */
@Composable
private fun SignatureCell(row: SigningRow?, signatureLoader: suspend (TalentAttachment) -> ByteArray?) {
    val colors = ZillitTheme.colors
    if (row == null) {
        Box(Modifier.width(CELL_WIDTH).height(ROW_HEIGHT))
        return
    }
    if (!row.signed) {
        Box(Modifier.width(CELL_WIDTH).height(ROW_HEIGHT).padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart) {
            ZillitText(str(S.desktop_td_eg_not_signed), style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted)
        }
        return
    }
    var image by remember(row.signatureFile) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(row.signatureFile) {
        image = row.signatureFile?.let { signatureLoader(it) }?.let(::decodeImageBitmap)
    }
    Box(Modifier.width(CELL_WIDTH).height(ROW_HEIGHT).padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = str(S.desktop_td_eg_g_signature),
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(ROW_HEIGHT - 6.dp),
            )
        } else {
            ZillitText(str(S.signed), style = ZillitTheme.typography.bodySmall)
        }
    }
}

private const val SIXTH_DAY = 6
private const val NAME_COL = 0
private const val CHARACTER_COL = 2
private const val CODE_COL = 3
private const val FORCED_CALL_COL = TalentExhibitG.FORCED_CALL_COL
private const val SIGNATURE_COL = TalentExhibitG.GRID_COLUMNS - 2
private val CELL_WIDTH = 104.dp
private val HEADER_HEIGHT = 40.dp
private val ROW_HEIGHT = 36.dp
private val PICKER_WIDTH = 320.dp
