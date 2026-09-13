// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.productionreport.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.rememberHorizontalResizeCursor
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.ui.EditorEvent
import com.zillit.desktop.feature.productionreport.ui.EditorState
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * The report editor — `ProductionReportApp.jsx:2931-3486`: the header and its
 * save family, the template tips, and the split between the live A4 preview
 * and the editing pane.
 */
@Composable
internal fun EditorView(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(editor.focusMode) {
        if (editor.focusMode) runCatching { focus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgPrimary)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape && editor.focusMode && state.dialog == null) {
                    onEvent(EditorEvent.ToggleFocus)
                    true
                } else {
                    false
                }
            },
    ) {
        EditorHeader(state, editor, onEvent)
        AnimatedVisibility(
            visible = !editor.focusMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TemplateTips(editor.sidebarVisible || editor.selection != null) { onEvent(EditorEvent.ShowSections) }
        }
        SplitPane(state, editor, onEvent)
    }
}

// Header ---------------------------------------------------------------------------------------

@Composable
private fun EditorHeader(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.headerBg)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackButton { onEvent(EditorEvent.Back) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${state.kind.title} Editor",
                    style = reportText(14.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                val name = editor.name.trim()
                if (!editor.isNew && name.isNotEmpty()) {
                    Text(
                        name,
                        style = reportText(14.sp),
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (editor.dirty) UnsavedPill()
            }
            Text(
                "Select a section from preview to edit",
                style = reportText(12.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusToggle(editor.focusMode) { onEvent(EditorEvent.ToggleFocus) }
            ReportButton(
                if (editor.savingTemplate) "Saving…" else "Save as Template",
                { onEvent(EditorEvent.SaveAsTemplate) },
                kind = ButtonKind.Outline,
                enabled = !editor.savingTemplate,
                height = 36.dp,
                trailing = if (editor.savingTemplate) ({ SmallSpinner(colors.accent) }) else null,
            )
            editor.template?.let { template ->
                ZillitTooltip("Overwrites \"${template.name}\"") {
                    ReportButton(
                        "Update Template",
                        { onEvent(EditorEvent.UpdateTemplate) },
                        kind = ButtonKind.Outline,
                        enabled = !editor.savingTemplate,
                        height = 36.dp,
                    )
                }
            }
            if (editor.isNew) {
                ReportButton(
                    if (editor.saving) "Saving…" else "Save As",
                    { onEvent(EditorEvent.SaveAs) },
                    kind = ButtonKind.Accent,
                    enabled = !editor.saving,
                    height = 36.dp,
                    horizontalPadding = 20.dp,
                )
            } else {
                SaveSplit(editor, onEvent)
            }
            if (editor.offersSend) {
                ReportButton(
                    "Send for Approval",
                    { onEvent(EditorEvent.OpenSend) },
                    kind = ButtonKind.Outline,
                    enabled = !editor.saving,
                    height = 36.dp,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip("Back") {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) colors.navyHover else colors.navy)
                .hoverable(source)
                .plainClick(source = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.ChevronLeft,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun UnsavedPill() {
    val colors = ReportTheme.colors
    Row(
        Modifier.clip(CircleShape).background(colors.accentLight).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
        Text("Unsaved changes", style = reportText(10.sp, FontWeight.Medium, 14.sp), color = colors.chipOnText)
    }
}

@Composable
private fun FocusToggle(active: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    val tint = if (active || hovered) colors.accent else colors.textSecondary
    ZillitTooltip(if (active) "Exit focus (Esc)" else "Focus — hide the tips and give the page more room") {
        Row(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) colors.accentLight else colors.surface)
                .border(1.dp, if (active || hovered) colors.accent else colors.borderStrong, RoundedCornerShape(8.dp))
                .hoverable(source)
                .plainClick(source = source, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (active) ZillitIcons.Collapse else ZillitIcons.Expand,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            Text(if (active) "Exit Focus" else "Focus", style = reportText(13.sp, FontWeight.Medium), color = tint)
        }
    }
}

/** Save ▾ — Save, or Save As a new draft. */
@Composable
private fun SaveSplit(editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Box {
        ReportButton(
            if (editor.saving) "Saving…" else "Save",
            { onEvent(EditorEvent.ToggleSaveMenu) },
            kind = ButtonKind.Accent,
            enabled = !editor.saving,
            height = 36.dp,
            horizontalPadding = 18.dp,
            trailing = {
                Icon(
                    ZillitIcons.ChevronDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            },
        )
        DropdownMenu(
            expanded = editor.saveMenuOpen,
            onDismissRequest = { onEvent(EditorEvent.ToggleSaveMenu) },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = colors.surface,
            modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            Column(Modifier.widthIn(min = 180.dp).padding(horizontal = 6.dp)) {
                SaveMenuItem("Save", "Update this draft", ZillitIcons.Save) { onEvent(EditorEvent.Save) }
                SaveMenuItem(
                    "Save As",
                    "Keep this one and save a copy",
                    ReportIcons.FileDone,
                ) { onEvent(EditorEvent.SaveAs) }
            }
        }
    }
}

@Composable
private fun SaveMenuItem(
    label: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) colors.accentLight else Color.Transparent)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(colors.accentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        }
        Column {
            Text(label, style = reportText(13.sp, FontWeight.Medium), color = colors.textPrimary)
            Text(hint, style = reportText(11.sp), color = colors.textTertiary)
        }
    }
}

@Composable
internal fun SmallSpinner(color: Color, size: androidx.compose.ui.unit.Dp = 12.dp) {
    CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(size))
}

// Tips ------------------------------------------------------------------------------------------

/** `TemplateTipsBar`: how to add a field, how templates save, and — with the pane shut — the Sections button. */
@Composable
private fun TemplateTips(paneBusy: Boolean, onShowSections: () -> Unit) {
    val colors = ReportTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.tipsBg).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.tipsIconBg)
                    .border(1.dp, Color(0x40F99300), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("i", style = reportText(11.sp, FontWeight.Bold, 12.sp), color = Color(0xFFF99300))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "TEMPLATE TIPS",
                    style = reportText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
                    color = colors.tipsText,
                )
                TipLine(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("Place the cursor in empty space between boxes or at the end of a box, then click ")
                        pushStyle(
                            androidx.compose.ui.text.SpanStyle(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF99300),
                            ),
                        )
                        append("+")
                        pop()
                        append(" to add a field.")
                    },
                )
                TipLine(androidx.compose.ui.text.AnnotatedString("Created templates can only be saved as a Draft."))
            }
        }
        if (!paneBusy) {
            Row(
                Modifier.padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.width(1.dp).height(44.dp).background(colors.border))
                Column(Modifier.widthIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "LAYOUT",
                        style = reportText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
                        color = colors.tipsText,
                    )
                    Text(
                        "Drag sections to reorder and customize their position on the page.",
                        style = reportText(12.sp, lineHeight = 18.sp),
                        color = colors.tipsText,
                    )
                }
                ReportButton(
                    "Sections",
                    onShowSections,
                    kind = ButtonKind.Accent,
                    icon = ReportIcons.Table,
                    height = 34.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 18.dp,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun TipLine(text: androidx.compose.ui.text.AnnotatedString) {
    val colors = ReportTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(4.dp).clip(CircleShape).background(Color(0xFFF99300)))
        Text(text, style = reportText(12.sp, lineHeight = 18.sp), color = colors.tipsText)
    }
}

// Split pane ------------------------------------------------------------------------------------

@Composable
private fun SplitPane(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxWidth
        val open = editor.showsPane
        val previewShare by animateFloatAsState(if (open) editor.splitPercent / 100f else 1f, tween(SPLIT_MS))
        val resizer = if (open) RESIZER_WIDTH else 0.dp
        val previewWidth = ((total - resizer) * previewShare).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(previewWidth).fillMaxHeight()) {
                PreviewArea(state, editor, onEvent)
            }
            if (open) {
                val totalPx = with(LocalDensity.current) { total.toPx() }
                Resizer(editor.splitPercent, totalPx) { onEvent(EditorEvent.Split(it)) }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    EditorPane(state, editor, onEvent)
                }
            }
        }
    }
}

private const val SPLIT_MS = 200
private val RESIZER_WIDTH = 18.dp

/** The 18 px drag handle: five dots and a vertical "DRAG". */
@Composable
private fun Resizer(percent: Float, totalPx: Float, onSplit: (Float) -> Unit) {
    val colors = ReportTheme.colors
    val current by rememberUpdatedState(percent)
    val (source, hovered) = rememberHover()
    val ink = if (hovered) colors.accent else Color(0xFFC0C5CC)
    Column(
        Modifier
            .width(RESIZER_WIDTH)
            .fillMaxHeight()
            .background(if (hovered) colors.accent.copy(alpha = 0.1f) else colors.sunken)
            .border(width = 1.dp, color = colors.border)
            .hoverable(source)
            .then(rememberHorizontalResizeCursor())
            .pointerInput(totalPx) {
                var running = current
                detectHorizontalDragGestures(onDragStart = { running = current }) { change, delta ->
                    change.consume()
                    if (totalPx > 0f) {
                        running = (running + delta / totalPx * 100f).coerceIn(
                            EditorState.MIN_SPLIT,
                            EditorState.MAX_SPLIT,
                        )
                        onSplit(running)
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        repeat(DOTS) {
            Box(Modifier.padding(bottom = 3.dp).size(3.dp).clip(CircleShape).background(ink))
        }
        Text(
            "DRAG",
            style = reportText(8.sp, FontWeight.Bold, 10.sp).copy(letterSpacing = 1.sp),
            color = ink,
            modifier = Modifier.padding(top = 4.dp).readsUpward(),
        )
    }
}

private const val DOTS = 5

/** "PREVIEW", the zoom group, and the page. */
@Composable
private fun PreviewArea(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Column(Modifier.fillMaxSize().background(colors.previewBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "PREVIEW",
                style = reportText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 1.5.sp),
                color = colors.textMuted,
            )
            Box(Modifier.weight(1f))
            if (editor.populating) PopulatingPill()
            ZoomGroup(editor.zoom, onEvent)
        }
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val zoom = editor.zoom / 100f
            val available = maxWidth
            Box(Modifier.fillMaxSize().zillitVerticalScroll(vertical).horizontalScroll(horizontal)) {
                val density = LocalDensity.current
                // CSS `zoom`: the page lays out again at the new scale, so scrollbars follow it.
                CompositionLocalProvider(LocalDensity provides Density(density.density * zoom, density.fontScale)) {
                    val fit = (available - 32.dp) / zoom
                    val pageWidth = if (zoom <= 1f) minOf(PAGE_WIDTH, fit.coerceAtLeast(320.dp)) else PAGE_WIDTH
                    Box(
                        Modifier.widthIn(min = available / zoom).padding(horizontal = 16.dp / zoom, vertical = 8.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Page(state, editor, onEvent, Modifier.width(pageWidth))
                    }
                }
            }
            ZillitScrollRail(vertical, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private val PAGE_WIDTH = 794.dp
private val PAGE_HEIGHT = 1123.dp

@Composable
private fun Page(state: ReportUiState, editor: EditorState, onEvent: (ReportEvent) -> Unit, modifier: Modifier) {
    val doc = docColors()
    val dim by animateFloatAsState(if (editor.populating) 0.55f else 1f, tween(180))
    Box(modifier.padding(bottom = 24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PAGE_HEIGHT)
                .shadow(
                    12.dp,
                    RoundedCornerShape(2.dp),
                    ambientColor = Color(0x1F000000),
                    spotColor = Color(0x1F000000),
                )
                .background(doc.page)
                .border(1.dp, doc.pageBorder)
                .padding(24.dp)
                .alpha(dim),
        ) {
            PreviewDocument(
                document = editor.document,
                members = state.members,
                selection = editor.selection,
                focusedLine = editor.focusedLine,
                focusedColumn = editor.focusedColumn,
                onEvent = if (editor.populating) ({}) else onEvent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (editor.populating) PopulatingCard(Modifier.align(Alignment.TopCenter).padding(top = 180.dp))
    }
}

@Composable
private fun PopulatingPill() {
    val colors = ReportTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.accentLight)
            .border(1.dp, Color(0xFFFED7AA), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallSpinner(colors.accent)
        Text(
            "Populating from last published call sheet…",
            style = reportText(11.sp, FontWeight.Medium),
            color = colors.accent,
        )
    }
}

@Composable
private fun PopulatingCard(modifier: Modifier) {
    val colors = ReportTheme.colors
    Row(
        modifier
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = 0.97f))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SmallSpinner(colors.accent, 20.dp)
        Column {
            Text("Loading call sheet data", style = reportText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
            Text(
                "Fetching the last published call sheet to pre-fill this report…",
                style = reportText(12.sp),
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun ZoomGroup(zoom: Int, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.segmentTrack)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ZoomButton(
            ReportIcons.Minus,
            "Reduce preview size",
            zoom > EditorState.MIN_ZOOM,
        ) { onEvent(EditorEvent.Zoom(-EditorState.ZOOM_STEP)) }
        val (source, hovered) = rememberHover()
        ZillitTooltip("Reset to 100%") {
            Text(
                "$zoom%",
                style = reportText(11.sp, FontWeight.SemiBold),
                color = if (hovered) colors.accent else colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .width(40.dp)
                    .hoverable(source)
                    .plainClick(source = source) { onEvent(EditorEvent.ResetZoom) },
            )
        }
        ZoomButton(
            ZillitIcons.Add,
            "Increase preview size",
            zoom < EditorState.MAX_ZOOM,
        ) { onEvent(EditorEvent.Zoom(EditorState.ZOOM_STEP)) }
    }
}

@Composable
private fun ZoomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(description) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .alpha(if (enabled) 1f else 0.3f)
                .hoverable(source)
                .plainClick(enabled = enabled, source = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (hovered && enabled) colors.accent else colors.textSecondary,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
