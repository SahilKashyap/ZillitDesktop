// One composable per dialog; the document card and the upload form are long by nature.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.pagedistribution.ui.script

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.plural
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.dod.ColourSwatches
import com.zillit.desktop.feature.pagedistribution.ui.dod.DodCountsDialog
import com.zillit.desktop.feature.pagedistribution.ui.dod.Fact
import com.zillit.desktop.feature.pagedistribution.ui.dod.Ribbon
import com.zillit.desktop.feature.pagedistribution.ui.dod.fileName
import com.zillit.desktop.feature.pagedistribution.ui.dod.formatBytes
import com.zillit.desktop.feature.pagedistribution.ui.dod.rememberDodFace
import com.zillit.desktop.feature.pagedistribution.ui.pages.ConfirmDialog
import com.zillit.desktop.feature.pagedistribution.ui.pages.PdfDialog
import com.zillit.desktop.feature.pagedistribution.ui.tint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Every overlay the Script & Pages page opens: a scene's pages, the upload
 * form, the PDF viewer, the tallies, and the two confirms. The viewer, the
 * tallies and the confirms are the engine's own; the rest wear the web's
 * `RenderScript.jsx` face.
 */
@Composable
internal fun ScriptDialogs(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    state.openFolder?.let { PagesDialog(state, onEvent, resolveUser) }
    state.upload?.let { ScriptUploadDialog(state, onEvent) }
    state.pdf?.let { PdfDialog(state, onEvent) }
    state.counts?.let { DodCountsDialog(state, onEvent, resolveUser) }
    state.confirmDelete?.let { document ->
        ConfirmDialog(
            title = str(S.desktop_delete_page),
            body = str(S.desktop_dist_delete_confirm_body_long, document.scriptName()),
            confirm = str(S.yes),
            danger = true,
            busy = state.busy,
            onConfirm = { onEvent(DistributionEvent.ConfirmDelete) },
            onDismiss = { onEvent(DistributionEvent.CancelDelete) },
        )
    }
    state.confirmPublish?.let { document ->
        // The web's `handleDistributeToDocDist`: [Tool, Tab, episode (TV) else scene].
        val path = listOfNotNull(
            state.tool.publishRoot,
            state.activeTab.publishSubFolder,
            document.episode.ifBlank { document.sceneNumber }.ifBlank { null },
        )
        ConfirmDialog(
            title = str(S.dd_publish_to_distribution),
            body = str(S.desktop_dist_publish_confirm_body, document.scriptName(), path.joinToString(" / ")),
            confirm = str(S.publish),
            danger = false,
            busy = state.busy,
            onConfirm = { onEvent(DistributionEvent.ConfirmPublish) },
            onDismiss = { onEvent(DistributionEvent.CancelPublish) },
        )
    }
}

// Pages ----------------------------------------------------------------------

/**
 * A scene opened — the web's 800-wide "Pages" modal listing its pages, paged
 * as the reader scrolls (`handleScroll` → `getNextScriptPagesData`), closed
 * by its Close button.
 */
@Composable
private fun PagesDialog(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val open = state.openFolder ?: return
    val live = state.mode == ListMode.Live
    val listState = rememberLazyListState()
    LaunchedEffect(listState, open.documents.size, open.exhausted) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 1 && info.totalItemsCount > 0
        }
            .distinctUntilChanged()
            .filter { it && !open.exhausted && !open.loadingMore && open.documents.isNotEmpty() }
            .collect { onEvent(DistributionEvent.LoadMore) }
    }
    val count = open.documents.size
    ZillitDialogShell(
        title = str(S.pages),
        subtitle = str(S.desktop_script_scene_no_value, open.folder.key.ifBlank { "—" }) + when {
            !live -> "  ·  " + str(S.desktop_dist_deleted_pages)
            count == 0 -> ""
            else -> "  ·  " + plural(S.docusign_template_detail_doc_pages, count)
        },
        icon = ZillitIcons.Folder,
        onDismiss = { onEvent(DistributionEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = PAGES_DIALOG_WIDTH,
        actions = {
            if (live) {
                ZillitButton(
                    text = str(S.desktop_location_upload_here),
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Paperclip,
                )
            }
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DistributionEvent.CloseFolder) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        val colors = ZillitTheme.colors
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PAGES_LIST_MIN_HEIGHT, max = PAGES_LIST_MAX_HEIGHT)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.sm),
        ) {
            when {
                state.loading && open.documents.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                open.documents.isEmpty() -> ZillitEmptyState(
                    title = str(S.no_data_found),
                    message = if (live) str(S.desktop_sched_scene_empty_message) else null,
                    icon = ZillitIcons.File,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(open.documents, key = { it.id }) { document ->
                        ScriptDocumentCard(state, document, onEvent, resolveUser)
                    }
                    if (open.loadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
                                contentAlignment = Alignment.Center,
                            ) { ZillitSpinner() }
                        }
                    }
                }
            }
        }
    }
}

// Cards ----------------------------------------------------------------------

/**
 * One document — the web's `Badge.Ribbon` over an antd `Card`: the
 * "Uploaded On" ribbon, the uploader's face, and the facts each tab shows.
 * A full script lists Episode (television), Script Date, Script Name and
 * Uploaded By; a page lists Uploaded By, Episode, Scene number, Page Number
 * and Page Date, painted its revision colour. The "More +" pill holds every
 * action.
 */
@Composable
internal fun ScriptDocumentCard(
    state: DistributionUiState,
    document: DistDocument,
    onEvent: (DistributionEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    val page = state.isFolderTab
    val uploader = resolveUser(document.createdBy) ?: document.createdBy.ifBlank { str(S.unkone_user) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (page) tint(document.colour, colors.surface) else colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Ribbon(text = str(S.desktop_script_uploaded_on_colon, DistributionDates.dateTime(document.createdMs)))
            Row(
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (document.replaced) {
                    ZillitStatusPill(label = str(S.desktop_dist_replaced_pill), tone = StatusTone.Neutral)
                }
                if (document.deleted) ZillitStatusPill(label = str(S.drive_deleted_default), tone = StatusTone.Rejected)
                ScriptMoreMenu(state, document, onEvent)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.md, bottom = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            ZillitAvatar(
                name = uploader.substringBefore(" ("),
                image = rememberDodFace(document.createdBy),
                userId = document.createdBy,
                size = CARD_AVATAR,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                if (page) {
                    Fact(label = str(S.txt_uploaded_by), value = uploader)
                    if (state.viewer.isTelevision) {
                        Fact(label = str(S.episode), value = document.episode.ifBlank { "—" })
                    }
                    Fact(label = str(S.txt_scene_number), value = document.sceneNumber.ifBlank { "—" })
                    if (document.pageNumber.isNotBlank()) {
                        Fact(label = str(S.desktop_dist_page_number), value = document.pageNumber)
                    }
                    val pageDate = DistributionDates.date(document.userSelectedDateMs).ifBlank { "—" }
                    Fact(label = str(S.page_date), value = pageDate)
                } else {
                    if (state.viewer.isTelevision) {
                        Fact(label = str(S.episode), value = document.episode.ifBlank { "—" })
                    }
                    if (document.dateMs > 0) {
                        Fact(label = str(S.desktop_script_date_label), value = DistributionDates.date(document.dateMs))
                    }
                    if (document.name.isNotBlank()) Fact(label = str(S.script_name), value = document.name)
                    Fact(label = str(S.txt_uploaded_by), value = uploader)
                }
                // A listing row may carry no attachment on the wire; only a
                // name or size that is actually known earns the line.
                val fileFacts = listOfNotNull(document.fileName(), formatBytes(document.attachment?.fileSize))
                if (fileFacts.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitIcon(icon = ZillitIcons.File, tint = colors.textMuted, size = FACT_ICON)
                        ZillitText(
                            text = fileFacts.joinToString("  ·  "),
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The web's `MorePopoverContent`, entry for entry and in its order: View,
 * Publish to Doc Distribution, Replace (full script, with posting rights),
 * Delete (pages), Download, Download count, View count. History keeps View
 * and Download only — replaced and deleted documents are not publishable
 * (ZL-20141), and the tallies left the history in ZL-14038.
 *
 * Delete stays visible without the right, as on the web: the press answers
 * with "only an admin or the uploader can delete" instead of a control that
 * is simply not there.
 */
@Composable
private fun ScriptMoreMenu(state: DistributionUiState, document: DistDocument, onEvent: (DistributionEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val live = state.mode == ListMode.Live
    val page = state.isFolderTab
    val colors = ZillitTheme.colors
    Box {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(colors.accent)
                .clickable { open = true }
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(icon = ZillitIcons.Paperclip, tint = colors.textOnAccent, size = FACT_ICON)
            ZillitText(
                text = str(S.desktop_script_more_pill),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textOnAccent,
            )
        }
        val entries = buildList {
            add(
                ZillitMenuEntry.Action(str(S.view), ZillitIcons.Eye, ZillitMenuTone.Primary) {
                    onEvent(DistributionEvent.View(document))
                },
            )
            if (live && state.viewer.mayPublish) {
                add(
                    ZillitMenuEntry.Action(str(S.dd_publish_to_distribution), ZillitIcons.Send, ZillitMenuTone.Info) {
                        onEvent(DistributionEvent.Publish(document))
                    },
                )
            }
            if (live && !page && state.viewer.mayPost) {
                add(
                    ZillitMenuEntry.Action(str(S.replace), ZillitIcons.Upload, ZillitMenuTone.Approve) {
                        onEvent(DistributionEvent.PickPdf(replaces = document))
                    },
                )
            }
            if (live && page) {
                add(
                    ZillitMenuEntry.Action(str(S.delete), ZillitIcons.Trash, ZillitMenuTone.Danger) {
                        onEvent(DistributionEvent.Delete(document))
                    },
                )
            }
            add(
                ZillitMenuEntry.Action(str(S.download), ZillitIcons.Download, ZillitMenuTone.Neutral) {
                    onEvent(DistributionEvent.Download(document))
                },
            )
            if (live && state.viewer.isAdmin) {
                add(ZillitMenuEntry.Divider)
                add(
                    ZillitMenuEntry.Action(str(S.download_count), ZillitIcons.BarChart, ZillitMenuTone.Neutral) {
                        onEvent(DistributionEvent.ShowCounts(document, downloads = true))
                    },
                )
                add(
                    ZillitMenuEntry.Action(str(S.view_count), ZillitIcons.Users, ZillitMenuTone.Neutral) {
                        onEvent(DistributionEvent.ShowCounts(document, downloads = false))
                    },
                )
            }
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = entries.map { entry ->
                if (entry is ZillitMenuEntry.Action) {
                    entry.copy(
                        onClick = {
                            open = false
                            entry.onClick()
                        },
                    )
                } else {
                    entry
                }
            },
        )
    }
}

// Upload ---------------------------------------------------------------------

/**
 * The web's `DocumentModal` in its two Script shapes:
 *  - Full Script: the file, the optional script date, and the episode on
 *    television (required). No name — the web's name input is gated off on
 *    this tab. With a script already up, this replaces it.
 *  - Pages: the file, the episode on television (required), the optional
 *    page number, the required scene number (starts with a digit, at most
 *    15 characters), the optional page date, and the optional page colour.
 */
@Composable
private fun ScriptUploadDialog(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val editor = state.upload ?: return
    val colors = ZillitTheme.colors
    val page = state.isFolderTab
    val replacing = editor.replaces != null
    val sceneProblem = sceneProblem(editor.sceneNumber)
    // The web's `EpisodeInput status={true}`: on television, required on a
    // script (new or replacing) and on a new page; a page replace has none.
    val episodeRequired = state.viewer.isTelevision && !(page && replacing)
    val episodeMissing = episodeRequired && editor.episode.isBlank()
    val ready = !editor.saving && !episodeMissing && (!page || replacing || sceneProblem == null)
    ZillitDialogShell(
        title = when {
            page -> str(S.upload_page)
            replacing -> str(S.desktop_script_replace_full_script)
            else -> str(S.desktop_script_upload_full_script)
        },
        subtitle = when {
            page -> str(S.desktop_script_filed_under_scene)
            replacing -> str(S.desktop_script_current_moves_to_history)
            else -> str(S.desktop_script_crew_sees_one_copy)
        },
        icon = ZillitIcons.Upload,
        onDismiss = { onEvent(DistributionEvent.CancelUpload) },
        visible = true,
        width = UPLOAD_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DistributionEvent.CancelUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (replacing && !page) str(S.replace) else str(S.upload),
                onClick = { onEvent(DistributionEvent.SubmitUpload) },
                leadingIcon = ZillitIcons.Upload,
                loading = editor.saving,
                enabled = ready,
            )
        },
    ) {
        // The file, as the web's preview strip names it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(FILE_TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.dangerSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = str(S.av_pdf),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.danger,
                )
            }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = editor.fileName,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    text = str(S.desktop_dist_file_size, formatBytes(editor.bytes.size.toString())),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (replacing && !page) {
                ZillitStatusPill(label = str(S.desktop_script_replaces_current), tone = StatusTone.Pending)
            }
        }

        if (page) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.sceneNumber,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(sceneNumber = it)) },
                    label = str(S.desktop_dist_scene_number_required_star),
                    placeholder = str(S.desktop_dist_scene_placeholder),
                    // Blank is not yet a mistake — the disabled Upload says so; a typed
                    // scene that breaks the rule is.
                    errorText = if (!replacing && editor.sceneNumber.isNotBlank()) sceneProblem else null,
                    helperText = when {
                        replacing -> str(S.desktop_script_scene_kept_from_replaced)
                        editor.sceneNumber.isBlank() -> str(S.desktop_script_scene_helper)
                        else -> null
                    },
                    enabled = !replacing,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.pageNumber,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(pageNumber = it)) },
                    label = str(S.desktop_dist_page_number_optional_caps),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitDateField(
                value = editor.dateYmd,
                onValueChange = { onEvent(DistributionEvent.UploadChanged(dateYmd = it)) },
                label = if (page) {
                    str(S.desktop_dist_page_date_optional_caps)
                } else {
                    str(S.desktop_dist_script_date_optional_caps)
                },
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isTelevision) {
                ZillitTextField(
                    value = editor.episode,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(episode = it)) },
                    label = if (episodeRequired) str(S.dd_publish_episode_hint) else str(S.txt_episode_number),
                    placeholder = str(S.desktop_dist_episode_placeholder),
                    helperText = if (episodeRequired) str(S.desktop_dist_required_on_television) else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (page) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = str(S.desktop_script_select_colour_optional),
                    style = ZillitTheme.typography.label,
                    color = colors.textSecondary,
                )
                ColourSwatches(
                    selected = editor.colour,
                    onSelect = { onEvent(DistributionEvent.UploadChanged(colour = it)) },
                )
                ZillitText(
                    text = editor.colour.label,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/**
 * What a confirm calls the document. A folder listing carries no attachment
 * or `original_name` on the wire (seen live), so a page falls back to its
 * scene and page number, a script to its `script_name`.
 */
internal fun DistDocument.scriptName(): String =
    fileName()
        ?: name.ifBlank { null }
        ?: sceneNumber.ifBlank { null }?.let { scene ->
            pageNumber.ifBlank { null }
                ?.let { str(S.desktop_script_name_scene_page, scene, it) }
                ?: str(S.desktop_script_name_scene, scene)
        }
        ?: str(S.document)

/** The web's `validatSceneNo` plus its `required` rule, as the field's error line. */
internal fun sceneProblem(scene: String): String? {
    val trimmed = scene.trim()
    return when {
        trimmed.isEmpty() -> str(S.dd_publish_scene_number_required)
        !trimmed.first().isDigit() -> str(S.desktop_script_scene_start_with_number)
        trimmed.length > MAX_SCENE -> str(S.desktop_script_scene_not_greater_than, MAX_SCENE)
        else -> null
    }
}

/** The colour's own hex, at full strength, for its swatch. */
internal fun swatchColour(colour: PageColour): Color {
    val v = colour.hex.removePrefix("#").toLong(HEX_RADIX)
    return Color(
        red = ((v shr RED_SHIFT) and CHANNEL) / CHANNEL_MAX,
        green = ((v shr GREEN_SHIFT) and CHANNEL) / CHANNEL_MAX,
        blue = (v and CHANNEL) / CHANNEL_MAX,
    )
}

private val PAGES_DIALOG_WIDTH = 820.dp
private val PAGES_LIST_MIN_HEIGHT = 320.dp
private val PAGES_LIST_MAX_HEIGHT = 640.dp
private val UPLOAD_DIALOG_WIDTH = 600.dp
private val FILE_TILE = 40.dp
private val CARD_AVATAR = 48.dp
private val FACT_ICON = 14.dp
private const val MAX_SCENE = 15
private const val HEX_RADIX = 16
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFFL
private const val CHANNEL_MAX = 255f
