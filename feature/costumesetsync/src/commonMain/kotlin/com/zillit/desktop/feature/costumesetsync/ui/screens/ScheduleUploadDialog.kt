package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.horizontalScroll
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.dateKey
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
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
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        ZillitButton(t("csync_sched_drop_$lower"), onClick = upload::chooseFile, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Upload, loading = upload.parsing && upload.doc == null)
        if (upload.parsing) MutedText(t("csync_upload_reading", "file" to upload.pickedName))
    }
    MutedText(t("csync_sched_formats"))
    MutedText(t("csync_sched_keep_note"), maxLines = 3)
}

@Composable
private fun SchedReview(upload: ScheduleUpload, result: Rec) {
    val rows = upload.rows
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(result.str("file"), style = ZillitTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (result.str("format").isNotEmpty()) ZillitStatusPill(result.str("format").uppercase(), tone = StatusTone.Progress)
        MutedText(
            listOf(
                pluralMany("csync_sched_scenes", rows.size, "n" to rows.size),
                pluralMany("csync_sched_days", result.long("days").toInt(), "n" to result.long("days")),
                if (result.has("day_number")) t("csync_sched_day_n", "n" to result.str("day_number")) else "",
            ).filter { it.isNotEmpty() }.joinToString(" · "),
        )
        ZillitButton(t("csync_upload_choose_another"), onClick = upload::reset, size = ButtonSize.Small, variant = ButtonVariant.Secondary)
    }
    result.strings("warnings").forEach { ZillitNotice(it, tone = StatusTone.Pending) }
    ZillitNotice(summary(upload), tone = StatusTone.Ready)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ZillitCheckbox(upload.createMissing, { upload.createMissing = it }, label = t("csync_sched_create_missing"))
        ZillitCheckbox(upload.fillBlanks, { upload.fillBlanks = it }, label = t("csync_sched_fill_blanks"))
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

private object SchedCol {
    val check = 48.dp
    val scene = 70.dp
    val slug = 320.dp
    val date = 170.dp
    val file = 150.dp
    val status = 170.dp
}

@Composable
private fun SCell(width: Dp, content: @Composable () -> Unit) {
    Column(Modifier.width(width).padding(horizontal = 4.dp, vertical = 4.dp)) { content() }
}

@Composable
private fun SchedTable(upload: ScheduleUpload) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row {
            SCell(SchedCol.check) {}
            listOf(
                SchedCol.scene to "csync_col_scene", SchedCol.slug to "csync_col_slugline", SchedCol.date to "csync_col_shoot_date",
                SchedCol.file to "csync_col_from_file", SchedCol.status to "csync_col_status",
            ).forEach { (w, key) ->
                SCell(w) { ZillitText(t(key).uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted, maxLines = 1) }
            }
        }
        ZillitDivider()
        upload.rows.forEach { s ->
            SchedRowView(upload, s)
            ZillitDivider()
        }
    }
}

private fun slug(f: Rec?): String =
    if (f == null) "" else listOf(f.str("int_ext"), f.str("location")).filter { it.isNotEmpty() }.joinToString(". ").ifEmpty { f.str("name") }

@Composable
private fun SchedRowView(upload: ScheduleUpload, s: SchedRow) {
    val on = upload.isOn(s)
    val line = slug(s.read).ifEmpty { slug(s.current) }
    val extras = listOfNotNull(
        s.read?.str("pages")?.ifEmpty { null },
        if (s.cast.isNotEmpty()) "cast ${s.cast.joinToString(", ") { it.str("cast_number") }}" else null,
        s.read?.str("script_day")?.ifEmpty { null },
    )
    Row(Modifier.alpha(if (on) 1f else OFF_ALPHA)) {
        SCell(SchedCol.check) {
            if (s.manual) {
                ZillitButton("✕", onClick = { upload.removeManual(s.number) }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            } else {
                ZillitCheckbox(on, { upload.toggle(s.number) }, enabled = s.exists || upload.createMissing)
            }
        }
        SCell(SchedCol.scene) { MonoText(s.number) }
        SCell(SchedCol.slug) {
            ZillitText(line.ifEmpty { "—" }, style = ZillitTheme.typography.titleSmall, maxLines = 1)
            s.read?.str("time_of_day")?.takeIf { it.isNotEmpty() }?.let { MutedText(tEnum(it)) }
            s.read?.str("description")?.takeIf { it.isNotEmpty() }?.let { MutedText(it) }
        }
        SCell(SchedCol.date) {
            // Clearing the date applies the scene with none ("No change").
            CellDate(upload.dates[s.number].orEmpty(), { upload.setDate(s.number, it) }, SchedCol.date - 8.dp, enabled = on)
        }
        SCell(SchedCol.file) {
            if (extras.isEmpty()) MutedText("—") else extras.forEach { ZillitText(it, style = ZillitTheme.typography.bodySmall) }
        }
        SCell(SchedCol.status) {
            if (s.manual) MutedText(t("csync_sched_added_by_hand"))
            RowStatus(upload, s)
            if (s.exists && s.currentShootDate != 0L) MutedText("${t("csync_was")} ${fmtDate(s.currentShootDate)}")
        }
    }
}

private const val OFF_ALPHA = 0.5f

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
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        MutedText(t("csync_sched_scene_no"))
        CellField(upload.manualNo, { upload.manualNo = it }, MANUAL_NO_W, onEnter = upload::addManual)
        CellDate(upload.defaultManualDate, { upload.manualDate = it }, MANUAL_DATE_W)
        ZillitButton(t("csync_add"), onClick = upload::addManual, enabled = upload.canAddManual)
        ZillitButton(t("csync_done"), onClick = { upload.manualOpen = false; upload.manualNo = "" }, variant = ButtonVariant.Tertiary)
        if (upload.manualDuplicate) MutedText(t("csync_sched_dup"))
    }
}

private val MANUAL_NO_W = 110.dp
private val MANUAL_DATE_W = 170.dp
