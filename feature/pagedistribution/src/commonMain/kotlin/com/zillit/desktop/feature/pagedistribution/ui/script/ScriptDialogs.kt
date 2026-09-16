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
            title = "Delete page",
            body = "Are you sure you want to delete \"${document.scriptName()}\"? It moves to the history.",
            confirm = "Yes",
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
            title = "Publish to Doc Distribution",
            body = "File \"${document.scriptName()}\" under ${path.joinToString(" / ")}?",
            confirm = "Publish",
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
        title = "Pages",
        subtitle = "Scene No : ${open.folder.key.ifBlank { "—" }}" + when {
            !live -> "  ·  Deleted pages"
            count == 0 -> ""
            else -> "  ·  $count page${if (count == 1) "" else "s"}"
        },
        icon = ZillitIcons.Folder,
        onDismiss = { onEvent(DistributionEvent.CloseFolder) },
        visible = true,
        scrollable = false,
        width = PAGES_DIALOG_WIDTH,
        actions = {
            if (live) {
                ZillitButton(
                    text = "Upload here",
                    onClick = { onEvent(DistributionEvent.PickPdf()) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Paperclip,
                )
            }
            ZillitButton(
                text = "Close",
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
                    title = "No data found",
                    message = if (live) "Upload a page here to start this scene." else null,
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
    val uploader = resolveUser(document.createdBy) ?: document.createdBy.ifBlank { "Unknown user" }
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
            Ribbon(text = "Uploaded On: ${DistributionDates.dateTime(document.createdMs)}")
            Row(
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (document.replaced) ZillitStatusPill(label = "Replaced", tone = StatusTone.Neutral)
                if (document.deleted) ZillitStatusPill(label = "Deleted", tone = StatusTone.Rejected)
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
                    Fact(label = "Uploaded By", value = uploader)
                    if (state.viewer.isTelevision) Fact(label = "Episode", value = document.episode.ifBlank { "—" })
                    Fact(label = "Scene number", value = document.sceneNumber.ifBlank { "—" })
                    if (document.pageNumber.isNotBlank()) Fact(label = "Page Number", value = document.pageNumber)
                    val pageDate = DistributionDates.date(document.userSelectedDateMs).ifBlank { "—" }
                    Fact(label = "Page Date", value = pageDate)
                } else {
                    if (state.viewer.isTelevision) Fact(label = "Episode", value = document.episode.ifBlank { "—" })
                    if (document.dateMs > 0) {
                        Fact(label = "Script Date", value = DistributionDates.date(document.dateMs))
                    }
                    if (document.name.isNotBlank()) Fact(label = "Script Name", value = document.name)
                    Fact(label = "Uploaded By", value = uploader)
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
                text = "More +",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textOnAccent,
            )
        }
        val entries = buildList {
            add(
                ZillitMenuEntry.Action("View", ZillitIcons.Eye, ZillitMenuTone.Primary) {
                    onEvent(DistributionEvent.View(document))
                },
            )
            if (live && state.viewer.mayPublish) {
                add(
                    ZillitMenuEntry.Action("Publish to Doc Distribution", ZillitIcons.Send, ZillitMenuTone.Info) {
                        onEvent(DistributionEvent.Publish(document))
                    },
                )
            }
            if (live && !page && state.viewer.mayPost) {
                add(
                    ZillitMenuEntry.Action("Replace", ZillitIcons.Upload, ZillitMenuTone.Approve) {
                        onEvent(DistributionEvent.PickPdf(replaces = document))
                    },
                )
            }
            if (live && page) {
                add(
                    ZillitMenuEntry.Action("Delete", ZillitIcons.Trash, ZillitMenuTone.Danger) {
                        onEvent(DistributionEvent.Delete(document))
                    },
                )
            }
            add(
                ZillitMenuEntry.Action("Download", ZillitIcons.Download, ZillitMenuTone.Neutral) {
                    onEvent(DistributionEvent.Download(document))
                },
            )
            if (live && state.viewer.isAdmin) {
                add(ZillitMenuEntry.Divider)
                add(
                    ZillitMenuEntry.Action("Download Count", ZillitIcons.BarChart, ZillitMenuTone.Neutral) {
                        onEvent(DistributionEvent.ShowCounts(document, downloads = true))
                    },
                )
                add(
                    ZillitMenuEntry.Action("View Count", ZillitIcons.Users, ZillitMenuTone.Neutral) {
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
            page -> "Upload page"
            replacing -> "Replace full script"
            else -> "Upload full script"
        },
        subtitle = when {
            page -> "Filed under its scene number"
            replacing -> "The current script moves to the history"
            else -> "The crew sees one current copy"
        },
        icon = ZillitIcons.Upload,
        onDismiss = { onEvent(DistributionEvent.CancelUpload) },
        visible = true,
        width = UPLOAD_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(DistributionEvent.CancelUpload) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (replacing && !page) "Replace" else "Upload",
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
                    text = "PDF",
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
                    text = "File size: ${formatBytes(editor.bytes.size.toString())}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (replacing && !page) ZillitStatusPill(label = "Replaces the current script", tone = StatusTone.Pending)
        }

        if (page) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.sceneNumber,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(sceneNumber = it)) },
                    label = "Scene number *",
                    placeholder = "12 or 12A",
                    // Blank is not yet a mistake — the disabled Upload says so; a typed
                    // scene that breaks the rule is.
                    errorText = if (!replacing && editor.sceneNumber.isNotBlank()) sceneProblem else null,
                    helperText = when {
                        replacing -> "Kept from the page being replaced."
                        editor.sceneNumber.isBlank() -> "Starts with a number, up to 15 characters."
                        else -> null
                    },
                    enabled = !replacing,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.pageNumber,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(pageNumber = it)) },
                    label = "Page Number (Optional)",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitDateField(
                value = editor.dateYmd,
                onValueChange = { onEvent(DistributionEvent.UploadChanged(dateYmd = it)) },
                label = if (page) "Page date (Optional)" else "Script date (Optional)",
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isTelevision) {
                ZillitTextField(
                    value = editor.episode,
                    onValueChange = { onEvent(DistributionEvent.UploadChanged(episode = it)) },
                    label = if (episodeRequired) "Episode number *" else "Episode number",
                    placeholder = "1 or 1,2",
                    helperText = if (episodeRequired) "Required on a television production." else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (page) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = "Select a color (Optional)",
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
            "scene $scene" + pageNumber.ifBlank { null }?.let { " page $it" }.orEmpty()
        }
        ?: "Document"

/** The web's `validatSceneNo` plus its `required` rule, as the field's error line. */
internal fun sceneProblem(scene: String): String? {
    val trimmed = scene.trim()
    return when {
        trimmed.isEmpty() -> "Scene number is required"
        !trimmed.first().isDigit() -> "Scene Number should start with a number"
        trimmed.length > MAX_SCENE -> "Scene Number not greater than $MAX_SCENE characters"
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
