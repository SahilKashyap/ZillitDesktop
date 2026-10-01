package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.dateKey
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

private val NARROW = 620.dp
private val WIDE = 1120.dp

/** The schedule / call sheet upload dialog: pick, review per scene, apply. */
@Composable
internal fun ScheduleUploadDialog(open: Boolean, upload: ScheduleUpload, docs: ProjectDocuments, onClose: () -> Unit) {
    val result = upload.result
    SyncDialogShell(
        title = t(if (upload.kind == "CALLSHEET") "csync_upload_callsheet" else "csync_upload_schedule"),
        icon = ZillitIcons.Calendar,
        onDismiss = onClose,
        visible = open,
        width = if (result != null) WIDE else NARROW,
        actions = {
            ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary)
            if (result != null) {
                val n = upload.included.size
                ZillitButton(
                    if (upload.applying) t("csync_applying") else if (n == 1) t("csync_sched_apply_one") else t("csync_sched_apply_n", "n" to n),
                    onClick = upload::apply,
                    enabled = n > 0 && !upload.applying,
                    loading = upload.applying,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (result == null) SchedPick(upload, docs) else SchedReview(upload, result)
        }
    }
}

@Composable
private fun SchedPick(upload: ScheduleUpload, docs: ProjectDocuments) {
    val lower = upload.lower
    ZillitNotice(t("csync_sched_intro_$lower"), tone = StatusTone.Progress)
    // The usual case needs no file: the latest is already in Zillit. Reading one is only a preview until it is applied.
    ZillitText(t("csync_doc_from_zillit_$lower"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
    DocumentPickList(
        docs.docs, docs.loading, upload::pickDoc, if (upload.parsing && upload.doc != null) upload.doc?.id else null,
        t("csync_doc_read_this"), t("csync_doc_none_$lower"),
    )
    ZillitText(t("csync_doc_or_upload"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
    DropZone(t("csync_sched_drop_$lower"), t("csync_sched_formats"), upload.parsing, t("csync_upload_reading", "file" to upload.pickedName), upload::chooseFile)
    MutedText(t("csync_sched_keep_note"), maxLines = 3)
}

@Composable
private fun SchedReview(upload: ScheduleUpload, result: Rec) {
    val rows = upload.rows
    // `.csync-sched__file`: icon, the name (cut off with an ellipsis), the format badge, the counts — and "Choose another" pushed to the end.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitIcon(ZillitIcons.File, tint = ZillitTheme.colors.textPrimary, size = 18.dp)
            ZillitText(result.str("file"), style = ZillitTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (result.str("format").isNotEmpty()) ZillitStatusPill(result.str("format").uppercase(), tone = StatusTone.Progress)
            MutedText(
                listOf(
                    pluralMany("csync_sched_scenes", rows.size, "n" to rows.size),
                    pluralMany("csync_sched_days", result.long("days").toInt(), "n" to result.long("days")),
                    if (result.has("day_number")) t("csync_sched_day_n", "n" to result.str("day_number")) else "",
                ).filter { it.isNotEmpty() }.joinToString(" · "),
            )
        }
        ZillitButton(t("csync_upload_choose_another"), onClick = upload::reset, variant = ButtonVariant.Secondary)
    }
    result.strings("warnings").forEach { ZillitNotice(it, tone = StatusTone.Pending) }
    ZillitNotice(summary(upload), tone = StatusTone.Ready)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        InkCheckbox(upload.createMissing, { upload.createMissing = it }, label = t("csync_sched_create_missing"))
        InkCheckbox(upload.fillBlanks, { upload.fillBlanks = it }, label = t("csync_sched_fill_blanks"))
    }
    SchedTable(upload)
    if (rows.isEmpty()) MutedText(t(if (result.str("file").isNotEmpty()) "csync_sched_none_found" else "csync_sched_none_found_manual"), maxLines = 3)
    ManualAdd(upload)
    if (result.bool("breakdown_empty")) MutedText(t("csync_sched_breakdown_empty"), maxLines = 3)
    if (!result.bool("known_cast_numbers") && rows.any { it.cast.isNotEmpty() }) MutedText(t("csync_sched_no_cast_numbers"), maxLines = 3)
}

private fun summary(upload: ScheduleUpload): String {
    val c = upload.summaryCounts
    val parts = listOf(
        if (c.added > 0) plural("csync_sched_summary_added", c.added, "n" to c.added) else "",
        if (c.scheduled > 0) t("csync_sched_summary_scheduled", "n" to c.scheduled) else "",
        if (c.filled > 0) plural("csync_sched_summary_filled", c.filled, "n" to c.filled) else "",
        if (c.links > 0) plural("csync_sched_summary_links", c.links, "n" to c.links) else "",
    ).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { t("csync_sched_summary_nothing") }
    return parts + (if (c.skipped > 0) plural("csync_sched_summary_skipped", c.skipped, "n" to c.skipped) else "") + t("csync_sched_summary_tail")
}

/** Fixed widths with the slugline taking what is left — it cuts off with "…", never a sideways scroll. */
private val SCHED_COLS: List<Dp?> = listOf(44.dp, 64.dp, null, 170.dp, 132.dp, 128.dp)

@Composable
private fun SchedTable(upload: ScheduleUpload) {
    val heads: List<@Composable () -> Unit> = listOf<@Composable () -> Unit>({ }) +
        listOf("csync_col_scene", "csync_col_slugline", "csync_col_shoot_date", "csync_col_from_file", "csync_col_status").map { key -> { ReviewHead(t(key)) } }
    ReviewTable(SCHED_COLS, heads) {
        upload.rows.forEachIndexed { i, s -> SchedRowView(upload, s, i == upload.rows.lastIndex) }
    }
}

private fun slug(f: Rec?): String =
    if (f == null) "" else listOf(f.str("int_ext"), f.str("location")).filter { it.isNotEmpty() }.joinToString(". ").ifEmpty { f.str("name") }

@Composable
private fun ReviewBodyScope.SchedRowView(upload: ScheduleUpload, s: SchedRow, last: Boolean) {
    val on = upload.isOn(s)
    val line = slug(s.read).ifEmpty { slug(s.current) }
    val extras = listOfNotNull(
        s.read?.str("pages")?.ifEmpty { null },
        if (s.cast.isNotEmpty()) "cast ${s.cast.joinToString(", ") { it.str("cast_number") }}" else null,
        s.read?.str("script_day")?.ifEmpty { null },
    )
    row(last, Modifier.alpha(if (on) 1f else OFF_ALPHA)) {
        cell(0) {
            if (s.manual) {
                ZillitButton("✕", onClick = { upload.removeManual(s.number) }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            } else {
                InkCheckbox(on, { upload.toggle(s.number) }, enabled = s.exists || upload.createMissing)
            }
        }
        cell(1) { ZillitText(s.number, style = ReviewText.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), maxLines = 1) }
        cell(2) {
            ZillitText(line.ifEmpty { "—" }, style = ReviewText.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            s.read?.str("time_of_day")?.takeIf { it.isNotEmpty() }?.let { ZillitText(tEnum(it), style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = ZillitTheme.colors.textMuted, maxLines = 1) }
            s.read?.str("description")?.takeIf { it.isNotEmpty() }?.let { MutedText(it) }
        }
        cell(3) {
            // Clearing the date applies the scene with none ("No change").
            CellDate(upload.dates[s.number].orEmpty(), { upload.setDate(s.number, it) }, Dp.Unspecified, enabled = on, height = 32.dp, dmy = true)
        }
        cell(4) {
            if (extras.isEmpty()) {
                MutedText("—")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { extras.forEach { ChipPill(it) } }
            }
        }
        cell(5) {
            if (s.manual) MutedText(t("csync_sched_added_by_hand"))
            RowStatus(upload, s)
            if (s.exists && s.currentShootDate != 0L) MutedText("${t("csync_was")} ${fmtDate(s.currentShootDate)}")
        }
    }
}

private const val OFF_ALPHA = 0.55f

/** What applying would do to this row — the reference's status column. */
@Composable
private fun RowStatus(upload: ScheduleUpload, s: SchedRow) {
    if (!s.exists) {
        if (upload.createMissing || s.manual) ZillitStatusPill(t("csync_sched_new_scene"), tone = StatusTone.Ready) else ZillitStatusPill(t("csync_sched_not_in_breakdown"), tone = StatusTone.Neutral)
        return
    }
    val date = upload.dates[s.number].orEmpty()
    val hasFills = upload.fillBlanks && s.fills > 0
    if (date.isEmpty() && !hasFills) {
        ZillitStatusPill(t("csync_sched_no_change"), tone = StatusTone.Neutral)
        return
    }
    val was = if (s.currentShootDate != 0L) dateKey(s.currentShootDate) else ""
    if (was.isNotEmpty() && was != date) {
        ZillitStatusPill(t("csync_sched_date_changes"), tone = StatusTone.Pending)
    } else {
        ZillitStatusPill(if (hasFills) t("csync_sched_fills_n", "n" to s.fills) else t("csync_sched_scheduled"), tone = StatusTone.Ready)
    }
}

@Composable
private fun ManualAdd(upload: ScheduleUpload) {
    if (!upload.manualOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            ZillitButton(t("csync_sched_add_manual"), onClick = { upload.manualOpen = true }, variant = ButtonVariant.Secondary)
            MutedText(t("csync_sched_add_manual_hint"), maxLines = 2)
        }
        return
    }
    // `.csync-toolbar` with `align-items: flex-end`: each field has its label over it (`.csync-labelled`).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(t("csync_sched_scene_no"), style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold), color = ZillitTheme.colors.textMuted)
            CellField(upload.manualNo, { upload.manualNo = it }, MANUAL_NO_W, onEnter = upload::addManual, autoFocus = true, height = 32.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(t("csync_col_shoot_date"), style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold), color = ZillitTheme.colors.textMuted)
            CellDate(upload.defaultManualDate, { upload.manualDate = it }, MANUAL_DATE_W, height = 32.dp, dmy = true)
        }
        ZillitButton(t("csync_add"), onClick = upload::addManual, enabled = upload.canAddManual)
        ZillitButton(t("csync_done"), onClick = { upload.manualOpen = false; upload.manualNo = "" }, variant = ButtonVariant.Tertiary)
        if (upload.manualDuplicate) MutedText(t("csync_sched_dup"))
    }
}

private val MANUAL_NO_W = 130.dp
private val MANUAL_DATE_W = 170.dp
