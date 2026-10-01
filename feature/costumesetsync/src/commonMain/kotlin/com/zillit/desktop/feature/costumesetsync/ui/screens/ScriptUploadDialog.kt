package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.t

private val NARROW = 620.dp
private val WIDE = 980.dp

/**
 * The script upload dialog: pick (a listed document or a file), confirm a replace,
 * review scenes and characters, import — and optionally read costume cues straight
 * after, with progress.
 */
@Composable
internal fun ScriptUploadDialog(open: Boolean, upload: ScriptUpload, docs: ProjectDocuments, onClose: () -> Unit) {
    val ctx = LocalSync.current
    val result = upload.result
    val title = if (upload.confirmReplace) t("csync_replace_title") else t("csync_upload_script")
    SyncDialogShell(
        title = title,
        onDismiss = onClose,
        visible = open,
        width = if (result != null) WIDE else NARROW,
        actions = { ScriptActions(upload, ctx.meta, onClose) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            when {
                upload.phase == "cues" -> CuesPhase(upload)
                upload.confirmReplace -> ReplaceConfirm(upload)
                result == null -> PickPhase(upload, docs)
                else -> ReviewPhase(upload, result, ctx.meta)
            }
        }
    }
}

@Composable
private fun ScriptActions(upload: ScriptUpload, meta: Rec?, onClose: () -> Unit) {
    when {
        upload.phase == "cues" -> ZillitButton(
            if (upload.cues.progress.running) t("csync_working") else t("csync_done"),
            onClick = onClose,
            enabled = !upload.cues.progress.running,
        )
        upload.confirmReplace -> {
            ZillitButton(
                t("csync_replace_keep_current"),
                onClick = upload::keepCurrent,
                variant = ButtonVariant.Secondary
            )
            ZillitButton(t("csync_replace_go"), onClick = upload::replaceGo, variant = ButtonVariant.Danger)
        }
        else -> {
            ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary)
            if (upload.result != null) {
                val n = upload.included.size
                val label = when {
                    upload.importing -> t(if (upload.wipes) "csync_replacing" else "csync_importing")
                    else -> plural(
                        when {
                            upload.wipes -> "csync_import_replace_n"
                            upload.withCues -> "csync_import_n_cues"
                            else -> "csync_import_n"
                        },
                        n, "n" to n,
                    )
                }
                ZillitButton(
                    label,
                    onClick = { upload.runImport(meta) },
                    variant = if (upload.wipes) ButtonVariant.Danger else ButtonVariant.Primary,
                    enabled = n > 0 && !upload.importing,
                    loading = upload.importing,
                )
            }
        }
    }
}

@Composable
private fun CuesPhase(upload: ScriptUpload) {
    ZillitNotice(t("csync_cues_imported_reading"), tone = StatusTone.Ready)
    CueProgressView(upload.cues.progress)
    if (!upload.cues.progress.running && !upload.cues.progress.failure) MutedText(
        t("csync_cues_review_hint"),
        maxLines = 3
    )
}

@Composable
private fun ReplaceConfirm(upload: ScriptUpload) {
    val body = plural(
        "csync_replace_body",
        upload.existingScenes,
        "count" to upload.existingScenes,
        "file" to upload.pickedName
    )
    ZillitNotice(body, tone = StatusTone.Pending)
    MutedText(t("csync_replace_kept"), maxLines = 3)
}

@Composable
private fun PickPhase(upload: ScriptUpload, docs: ProjectDocuments) {
    ZillitNotice(
        t(if (upload.existingScenes > 0) "csync_upload_script_intro" else "csync_upload_script_intro_first"),
        tone = StatusTone.Progress
    )
    // No file needed for the usual case: the project's latest script is already in Zillit.
    // Reading one is only a preview — nothing is imported until the review is confirmed.
    ZillitText(
        t("csync_doc_from_zillit_script"),
        style = ZillitTheme.typography.label,
        color = ZillitTheme.colors.textSecondary
    )
    DocumentPickList(
        docs.docs, docs.loading, upload::pickDoc, if (upload.parsing && upload.doc != null) upload.doc?.id else null,
        t("csync_doc_read_this"), t("csync_doc_none_script"),
    )
    ZillitText(t("csync_doc_or_upload"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
    DropZone(
        t("csync_upload_drop"),
        t("csync_upload_formats"),
        upload.parsing,
        t("csync_upload_reading", "file" to upload.pickedName),
        upload::chooseFile
    )
    MutedText(t("csync_upload_reupload_note"), maxLines = 3)
}

@Suppress("LongMethod")
@Composable
private fun ReviewPhase(upload: ScriptUpload, result: Rec, meta: Rec?) {
    val scenes = upload.scenes
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZillitText(
            result.str("file"),
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false)
        )
        ZillitStatusPill(result.str("format").uppercase(), tone = StatusTone.Progress)
        MutedText(
            t(
                "csync_upload_file_stats", "scenes" to scenes.size, "chars" to upload.detected.size,
                "cues" to (result.rec("stats")?.long("cues") ?: 0L),
            ),
        )
        ZillitButton(
            t("csync_upload_choose_another"),
            onClick = upload::reset,
            size = ButtonSize.Small,
            variant = ButtonVariant.Secondary
        )
    }
    if (upload.replacing) {
        ZillitNotice(
            if (upload.wipes) plural("csync_upload_replaces_n", upload.existingScenes, "n" to upload.existingScenes)
            else plural("csync_upload_merge_kept", upload.kept, "n" to upload.kept),
            tone = StatusTone.Pending,
        )
    }
    result.strings("warnings").forEach { ZillitNotice(it, tone = StatusTone.Pending) }
    // `.csync-upload-meta`: the revision name beside the summary, equal halves.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextInput(
            upload.revision,
            { upload.revision = it },
            t("csync_field_revision"),
            Modifier.weight(1f),
            help = t("csync_field_revision_hint")
        )
        ZillitNotice(summaryText(upload), Modifier.weight(1f).fillMaxHeight(), tone = StatusTone.Ready)
    }
    InkTabs(
        listOf(
            "scenes" to "${t("csync_tab_scenes")} (${scenes.size})",
            "characters" to "${t("csync_tab_characters")} (${upload.detected.size})"
        ),
        upload.tab,
        badges = if (upload.newCharacters > 0) mapOf("characters" to t(
            "csync_char_n_new",
            "n" to upload.newCharacters
        )) else emptyMap(),
        onSelect = { upload.tab = it },
    )
    if (upload.tab == "scenes") ScriptScenesTable(upload, meta) else CharacterConfirm(upload)
    val costNote = if (cueEngineOf(meta) == "ai") t("csync_cues_cost_note") else ""
    ZillitCheckbox(
        checked = upload.withCues,
        onCheckedChange = { upload.withCues = it },
        label = "${t("csync_also_extract_cues")} (${engineLabel(meta)}$costNote)",
    )
}

private fun summaryText(upload: ScriptUpload): String {
    val included = upload.included
    val kept = upload.kept
    val edited = upload.editedCount
    val ignored = upload.rows.count { it.deleted }
    return plural(
        "csync_upload_summary", included.size,
        "new" to included.count { it.str("change") == "new" },
        "updated" to included.count { it.str("change") == "updated" },
        "unchanged" to included.count { it.str("change") == "unchanged" },
        "kept" to if (kept > 0) t("csync_upload_kept_suffix", "n" to kept) else "",
        "edited" to if (edited > 0) t("csync_upload_edited_suffix", "n" to edited) else "",
        "chars" to upload.newCharacters,
        "ignored" to if (ignored > 0) t("csync_upload_ignored_suffix", "n" to ignored) else "",
    )
}
