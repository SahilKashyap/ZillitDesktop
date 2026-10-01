package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.docDayKey
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.domain.docSceneRows
import com.zillit.desktop.feature.costumesetsync.domain.docSourceKey
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.latestOf
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private val VIEWER_WIDTH = 760.dp

/**
 * The project's documents of one kind, newest first, each with a button to read
 * it into the importer — the "no file needed" path of the upload dialogs. The
 * newest is marked Latest, which is the one the user almost always wants.
 */
@Composable
internal fun DocumentPickList(docs: List<Rec>, loading: Boolean, onPick: (Rec) -> Unit, busyId: String?, actionLabel: String, emptyText: String) {
    if (loading && docs.isEmpty()) {
        MutedText("…")
        return
    }
    if (docs.isEmpty()) {
        MutedText(emptyText)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        docs.forEach { d ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        RowTitle(docName(d), Modifier.weight(1f, fill = false))
                        if (d.bool("latest")) ZillitStatusPill(t("csync_doc_latest"), tone = StatusTone.Pending)
                    }
                    MutedText(listOf(t(docSourceKey(d)), fmtDate(d.long("created")), d.str("revision")).filter { it.isNotEmpty() }.joinToString(" · "))
                }
                ZillitButton(
                    actionLabel,
                    onClick = { onPick(d) },
                    size = ButtonSize.Small,
                    variant = if (d.bool("latest")) ButtonVariant.Primary else ButtonVariant.Secondary,
                    loading = busyId == d.id,
                    enabled = busyId == null || busyId == d.id,
                )
            }
            ZillitDivider()
        }
    }
}

private val KIND_LOWER = mapOf("SCRIPT" to "script", "SCHEDULE" to "schedule", "CALLSHEET" to "callsheet")

/**
 * "View uploaded script / schedule / call sheet": the kept versions and the scenes
 * the app holds for the one on screen, to tick off while reading down the file.
 * The ticks are only for this look and are not saved.
 *
 * Not ported: the in-app PDF / text rendering. The file opens in the system
 * viewer ("Open"), which needs download rights; "Read this" opens the importer on
 * the version on screen (posting rights only, since an import writes).
 */
@Composable
internal fun DocumentViewerDialog(
    open: Boolean,
    kind: String,
    docs: ProjectDocuments,
    scenes: List<Rec>,
    onClose: () -> Unit,
    onRead: (Rec) -> Unit,
) {
    val ctx = LocalSync.current
    val lower = KIND_LOWER[kind] ?: "script"
    var docId by remember(open) { mutableStateOf<String?>(null) }
    var ticked by remember(open) { mutableStateOf(emptySet<String>()) }
    val list = docs.docs
    val latest = latestOf(list)
    val doc = list.firstOrNull { it.id == docId } ?: latest
    val day = docDayKey(doc)
    val rows = docSceneRows(kind, scenes, day)
    val meta = { d: Rec -> listOf(t(docSourceKey(d)), d.str("revision"), fmtDateTime(d.long("created"))).filter { it.isNotEmpty() }.joinToString(" · ") }

    FormDialog(
        open = open,
        title = t("csync_uploaded_doc_$lower"),
        onDismiss = onClose,
        confirmLabel = t("csync_close"),
        onConfirm = onClose,
        width = VIEWER_WIDTH,
    ) {
        if (doc == null) {
            EmptyState(t("csync_doc_kept_none_$lower"), t("csync_doc_kept_hint_$lower"))
            return@FormDialog
        }
        if (list.size > 1) {
            ZillitText(t("csync_doc_n_versions", "n" to list.size), color = ZillitTheme.colors.textSecondary)
            PickInput(
                doc.id, list.map { it.id to "${docName(it)} · ${fmtDateTime(it.long("created"))}" }, { docId = it },
                t("csync_doc_version"), Modifier.fillMaxWidth(),
            )
        } else {
            RowTitle(docName(doc))
        }
        MutedText(meta(doc), maxLines = 2)
        if (latest != null && doc.id != latest.id) {
            ZillitText(t("csync_doc_viewing_older", "name" to docName(latest), "meta" to meta(latest)), color = ZillitTheme.colors.warning)
            ZillitButton(t("csync_doc_show_latest"), onClick = { docId = latest.id }, size = ButtonSize.Small, variant = ButtonVariant.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (ctx.canPost) ZillitButton(t("csync_doc_read_this"), onClick = { onRead(doc) })
            ZillitButton(
                t("csync_open_new_tab"),
                onClick = { ctx.whenDownload { ctx.scope.launch { openDocument(ctx, doc) } } },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
            )
        }
        val title = when {
            kind != "CALLSHEET" -> t("csync_doc_list_$lower")
            day.isNotEmpty() -> t("csync_doc_list_callsheet", "day" to day)
            else -> t("csync_doc_list_callsheet_day")
        }
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        MutedText(t("csync_doc_tick_hint"), maxLines = 2)
        MutedText(t("csync_doc_ticked", "n" to ticked.size, "total" to rows.rows.size))
        if (rows.rows.isEmpty()) MutedText(t("csync_doc_none_yet"))
        rows.rows.forEach { (scene, note) ->
            ZillitCheckbox(
                checked = scene.id in ticked,
                onCheckedChange = { ticked = if (scene.id in ticked) ticked - scene.id else ticked + scene.id },
                label = "${scene.str("number")}   $note",
            )
        }
        if (rows.undated > 0) MutedText(plural("csync_doc_undated", rows.undated, "n" to rows.undated))
    }
}

/** Opens a kept file in the system viewer: its own URL, else the stored key resolved against project storage. */
private suspend fun openDocument(ctx: com.zillit.desktop.feature.costumesetsync.ui.SyncCtx, doc: Rec) {
    val attachment = doc.rec("attachment")
    val url = doc.str("url").ifEmpty { null }
        ?: attachment?.str("signed_url")?.ifEmpty { null }
        ?: attachment?.str("public_url")?.ifEmpty { null }
        ?: attachment?.let { ctx.host.resolveUrl(it.str("media"), it.str("bucket"), it.str("region")) }
    if (url.isNullOrEmpty()) ctx.toast(t("csync_doc_preview_failed"), false) else ctx.host.openUrl(url)
}
