@file:Suppress("LongMethod") // A script card and its pages table read best whole.

package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.ScriptsState
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.components.CountChip
import com.zillit.desktop.feature.sides.ui.components.Hairline
import com.zillit.desktop.feature.sides.ui.components.MetaCell
import com.zillit.desktop.feature.sides.ui.components.MetaDot
import com.zillit.desktop.feature.sides.ui.components.SidesBadge
import com.zillit.desktop.feature.sides.ui.components.SidesLoader
import com.zillit.desktop.feature.sides.ui.components.SidesTile
import com.zillit.desktop.feature.sides.ui.components.hand
import com.zillit.desktop.feature.sides.ui.components.hexColor

/** The scripts manager — the web's `ScriptsManagerPage` below its header. */
@Composable
internal fun ScriptsPage(scripts: ScriptsState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText("Upload & manage scripts", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "Manage script versions and their pages — these are used to generate sides.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(end = RAIL_GUTTER),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when {
                scripts.loading && scripts.scripts.isEmpty() -> SidesLoader("Loading scripts…")
                scripts.scripts.isEmpty() -> ZillitEmptyState(
                    title = "No scripts yet",
                    message = "Add a script (PDF or .fdx) to get started, then manage its pages.",
                    icon = ZillitIcons.File,
                    action = {
                        ZillitButton(
                            "Add Script",
                            onClick = { onEvent(SidesEvent.AskAddScript) },
                            leadingIcon = ZillitIcons.Add,
                        )
                    },
                )
                else -> scripts.scripts.forEach { script -> ScriptCard(script, scripts, onEvent) }
            }
        }
    }
}

/** One script: header (title, version, date), View/Download/Replace/Delete, and its pages. */
@Composable
private fun ScriptCard(script: Script, state: ScriptsState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val version = script.currentVersion
    val replacing = script.id in state.replacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SidesTile(size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(
                    script.title,
                    style = ZillitTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (version != null) {
                        MetaCell(ZillitIcons.File, "${version.pageCount} pages")
                        MetaDot()
                        VersionPill(
                            script,
                            version,
                            state.versionsOf(script.id),
                            open = state.versionMenu == script.id,
                            onEvent,
                        )
                    } else {
                        ZillitText(
                            text = "No script file yet — add pages or upload a file",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.warning,
                        )
                    }
                    MetaDot()
                    MetaCell(ZillitIcons.Calendar, SidesRules.formatDate(script.updatedAt))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (version != null) {
                    ZillitButton(
                        text = "View",
                        onClick = { onEvent(SidesEvent.ViewVersion(script, version)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Eye,
                    )
                    ZillitButton(
                        text = "Download",
                        onClick = { onEvent(SidesEvent.DownloadVersion(script, version)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Download,
                    )
                }
                ZillitButton(
                    text = if (replacing) "Uploading…" else if (version != null) "Replace" else "Upload Script",
                    onClick = { onEvent(SidesEvent.ReplaceScript(script)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                    loading = replacing,
                )
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(SidesEvent.AskDeleteScript(script)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
        Hairline()
        PagesSection(script.id, state, onEvent)
    }
}

/**
 * The version label; with more than one version it drops down to a menu
 * where any version can be viewed or downloaded — previous scripts stay
 * reachable after a Replace (ZL-19668).
 */
@Composable
private fun VersionPill(
    script: Script,
    current: ScriptVersion,
    versions: List<ScriptVersion>,
    open: Boolean,
    onEvent: (SidesEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    if (versions.size <= 1) {
        MetaCell(ZillitIcons.Tag, current.label)
        return
    }
    Box {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(if (open) colors.accentSoft else colors.surfaceSunken)
                .border(1.dp, if (open) colors.accent else colors.border, ZillitTheme.shapes.pill)
                .clickable { onEvent(SidesEvent.VersionMenu(if (open) null else script.id)) }
                .hand()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ZillitIcon(icon = ZillitIcons.Tag, tint = colors.textMuted, size = 12.dp)
            ZillitText(current.label, style = ZillitTheme.typography.labelSmall)
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = 11.dp)
        }
        if (open) {
            Popup(
                onDismissRequest = { onEvent(SidesEvent.VersionMenu(null)) },
                properties = PopupProperties(focusable = true),
                offset = androidx.compose.ui.unit.IntOffset(0, 28),
            ) {
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .clip(ZillitTheme.shapes.medium)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                        .padding(4.dp),
                ) {
                    versions.forEach { version ->
                        val isCurrent = version.id == current.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.small)
                                .background(if (isCurrent) colors.accentSoft else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ZillitText(
                                version.label,
                                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                            )
                            if (isCurrent) SidesBadge("Current")
                            ZillitText(
                                text = "${version.pageCount} pp · ${SidesRules.formatDate(version.createdAt)}",
                                style = ZillitTheme.typography.labelSmall,
                                color = colors.textMuted,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ZillitIconButton(ZillitIcons.Eye, "View", onClick = {
                                onEvent(SidesEvent.VersionMenu(null))
                                onEvent(SidesEvent.ViewVersion(script, version))
                            })
                            ZillitIconButton(ZillitIcons.Download, "Download", onClick = {
                                onEvent(SidesEvent.VersionMenu(null))
                                onEvent(SidesEvent.DownloadVersion(script, version))
                            })
                        }
                    }
                }
            }
        }
    }
}

/** "Pages" — the scene folders under a script — the web's `PagesSection`. */
@Composable
private fun PagesSection(scriptId: String, state: ScriptsState, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val collapsed = scriptId in state.collapsedPages
    val pages = state.pagesOf(scriptId)
    val visible = state.visiblePages(scriptId)
    val query = state.pageSearch[scriptId].orEmpty()
    val caret by animateFloatAsState(if (collapsed) -90f else 0f, label = "pagesCaret")
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .clickable { onEvent(SidesEvent.TogglePages(scriptId)) }
                        .hand(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitIcon(
                        icon = ZillitIcons.ChevronDown,
                        tint = colors.textMuted,
                        size = 14.dp,
                        modifier = Modifier.rotate(caret),
                    )
                    ZillitText("Pages", style = ZillitTheme.typography.titleSmall)
                    if (pages.isNotEmpty()) CountChip(pages.size)
                }
                if (!collapsed) {
                    ZillitText(
                        text = "Manage and organize all pages in this script.",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            if (!collapsed && pages.isNotEmpty()) {
                ZillitSearchField(
                    value = query,
                    onValueChange = { onEvent(SidesEvent.PageSearch(scriptId, it)) },
                    placeholder = "Search pages…",
                    modifier = Modifier.width(220.dp),
                )
            }
            ZillitButton(
                text = "Add Page",
                onClick = { onEvent(SidesEvent.AskAddPage(scriptId)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        if (collapsed) return@Column
        when {
            scriptId in state.pagesLoading && pages.isEmpty() -> SidesLoader("Loading pages…")
            pages.isEmpty() -> ZillitText(
                text = "No pages yet. Add scene folders (color, scene no., description, PDF) to pull into sides.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            else -> PagesTable(scriptId, visible, query, onEvent)
        }
    }
}

@Composable
private fun PagesTable(scriptId: String, rows: List<ScenePage>, query: String, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText("PAGE", style = ZillitTheme.typography.columnHeader, modifier = Modifier.weight(1f))
            ZillitText("PAGE COUNT", style = ZillitTheme.typography.columnHeader, modifier = Modifier.width(90.dp))
            ZillitText("ACTIONS", style = ZillitTheme.typography.columnHeader, modifier = Modifier.width(210.dp))
        }
        if (rows.isEmpty()) {
            ZillitText(
                text = "No pages match “$query”",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(12.dp),
            )
        }
        rows.forEachIndexed { index, page ->
            if (index > 0) Hairline()
            PageRow(scriptId, page, onEvent)
        }
    }
}

@Composable
private fun PageRow(scriptId: String, page: ScenePage, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = hexColor(page.color) ?: colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitText("⠿", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = page.sceneNumber,
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = tint,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZillitText(
                    text = page.fileName.ifBlank { "Page ${page.sceneNumber}" },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PageNote(page.description)
            }
        }
        ZillitText("${page.pageCount} pg", style = ZillitTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
        Row(
            Modifier.width(210.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                "View",
                onClick = { onEvent(SidesEvent.ViewPage(page)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Eye,
            )
            ZillitButton(
                text = "Edit",
                onClick = { onEvent(SidesEvent.AskEditPage(scriptId, page)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
            ZillitTooltip("Delete page") {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete page",
                    onClick = { onEvent(SidesEvent.AskDeletePage(page)) },
                    tint = colors.danger,
                )
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}

/** The note, clipped past 120 characters with the whole text in a tooltip. */
@Composable
private fun PageNote(text: String) {
    if (text.isBlank()) return
    val colors = ZillitTheme.colors
    if (text.length <= SidesRules.NOTE_LIMIT) {
        ZillitText(
            text,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(
            text = text.take(SidesRules.NOTE_LIMIT).trimEnd() + "…",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        ZillitTooltip(text) {
            ZillitText("more", style = ZillitTheme.typography.labelSmall, color = colors.info)
        }
    }
}

/** Keeps the cards clear of the scroll rail. */
private val RAIL_GUTTER = 14.dp
