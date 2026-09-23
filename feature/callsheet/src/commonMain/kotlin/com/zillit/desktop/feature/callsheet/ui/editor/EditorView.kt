// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.rememberHorizontalResizeCursor
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.ui.EditorEvent
import com.zillit.desktop.feature.callsheet.ui.EditorState
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * The call sheet editor — `CallSheetApp.jsx:3310-3752`: the header and its
 * save family, the template tips, and the split between the live A4 preview
 * and the editing pane. The chrome follows the app theme; the sheet itself
 * stays paper.
 */
@Composable
internal fun EditorView(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    Box(Modifier.fillMaxSize().background(SheetTheme.colors.bgPrimary)) {
        Column(Modifier.fillMaxSize()) {
            EditorHeader(state, editor, onEvent)
            TemplateTips(editor.sidebarVisible) { onEvent(EditorEvent.ShowSections) }
            SplitPane(state, editor, onEvent)
        }
        editor.quickUndo?.let { QuickUndoToast(it, onEvent, Modifier.align(Alignment.BottomEnd).padding(24.dp)) }
    }
}

// Header -----------------------------------------------------------------------------------------------

@Composable
@Suppress("CyclomaticComplexMethod") // One branch per header button the web shows or hides.
private fun EditorHeader(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackButton { onEvent(EditorEvent.Back) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    str(S.desktop_cs_editor_title),
                    style = sheetText(14.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                if (editor.serialNo.isNotBlank()) {
                    Text("#${editor.serialNo}", style = sheetText(14.sp), color = colors.textMuted, maxLines = 1)
                }
                val name = editor.name.trim()
                if (!editor.isNew && name.isNotEmpty()) {
                    Text(
                        "· $name",
                        style = sheetText(13.sp),
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (editor.dirty) UnsavedPill()
            }
            Text(
                str(S.desktop_select_a_section_to_edit),
                style = sheetText(12.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val busy = editor.saving || state.busy
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ZL-21539: create-only — on an existing sheet the save would close the editor over unsaved edits.
            if (editor.offersSaveAsTemplate) {
                SheetButton(
                    if (editor.savingTemplate) str(S.ah_saving) else str(S.save_as_template),
                    { onEvent(EditorEvent.SaveAsTemplate) },
                    kind = ButtonKind.Csc,
                    enabled = !editor.savingTemplate && !busy,
                    radius = 10.dp,
                    height = 36.dp,
                    trailing = if (editor.savingTemplate) ({ SmallSpinner(colors.accent) }) else null,
                )
            }
            editor.template?.let { template ->
                ZillitTooltip(str(S.desktop_overwrites_named, template.name)) {
                    SheetButton(
                        str(S.update_template),
                        { onEvent(EditorEvent.UpdateTemplate) },
                        kind = ButtonKind.Csc,
                        enabled = !editor.savingTemplate && !busy,
                        radius = 10.dp,
                        height = 36.dp,
                    )
                }
            }
            if (editor.offersSignature) {
                SheetButton(
                    str(S.cs_send_for_signature),
                    { onEvent(EditorEvent.SendForSignature) },
                    kind = ButtonKind.Csc,
                    icon = ZillitIcons.Send,
                    enabled = !busy,
                    radius = 10.dp,
                    height = 36.dp,
                )
            }
            if (editor.offersComments) {
                SheetButton(
                    str(S.cs_send_for_comments),
                    { onEvent(EditorEvent.SendForComments) },
                    kind = ButtonKind.Csc,
                    icon = SheetIcons.UsersAdd,
                    enabled = !busy,
                    radius = 10.dp,
                    height = 36.dp,
                )
            }
            if (editor.isNew) {
                SheetButton(
                    if (editor.saving) str(S.ah_saving) else str(S.cs_save_as),
                    { onEvent(EditorEvent.SaveAs) },
                    kind = ButtonKind.Navy,
                    enabled = !busy,
                    height = 36.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            } else {
                SaveSplit(editor, busy, onEvent)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(str(S.back)) {
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
                contentDescription = str(S.back),
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun UnsavedPill() {
    val colors = SheetTheme.colors
    Row(
        Modifier.clip(CircleShape).background(colors.accentLight).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
        Text(str(S.cs_exit_title), style = sheetText(10.sp, FontWeight.Medium, 14.sp), color = colors.chipOnText)
    }
}

/** Save ▾ — the only dark button: Save this sheet, or Save As a new draft. */
@Composable
private fun SaveSplit(editor: EditorState, busy: Boolean, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Box {
        SheetButton(
            if (editor.saving) str(S.ah_saving) else str(S.save),
            { onEvent(EditorEvent.ToggleSaveMenu) },
            kind = ButtonKind.Navy,
            enabled = !busy,
            height = 36.dp,
            fontSize = 14.sp,
            horizontalPadding = 20.dp,
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
                SaveMenuItem(str(S.save), str(S.desktop_cs_save_hint), ZillitIcons.Save) { onEvent(EditorEvent.Save) }
                SaveMenuItem(str(S.cs_save_as), str(S.desktop_cs_save_as_hint), SheetIcons.FileDone) {
                    onEvent(EditorEvent.SaveAs)
                }
            }
        }
    }
}

@Composable
private fun SaveMenuItem(label: String, hint: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = SheetTheme.colors
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
            Text(label, style = sheetText(13.sp, FontWeight.Medium), color = colors.textPrimary)
            Text(hint, style = sheetText(11.sp), color = colors.textTertiary)
        }
    }
}

@Composable
internal fun SmallSpinner(color: Color, size: Dp = 12.dp) {
    CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(size))
}

// Tips -----------------------------------------------------------------------------------------------------

/** `TemplateTipsBar`: how to add a field, how templates save, and — with the list shut — the Sections button. */
@Composable
private fun TemplateTips(listOpen: Boolean, onShowSections: () -> Unit) {
    val colors = SheetTheme.colors
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
                Text("i", style = sheetText(11.sp, FontWeight.Bold, 12.sp), color = Color(0xFFF99300))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    str(S.desktop_template_tips_upper),
                    style = sheetText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
                    color = colors.tipsText,
                )
                TipLine(
                    buildAnnotatedString {
                        append(str(S.desktop_tip_place_cursor))
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFF99300)))
                        append("+")
                        pop()
                        append(" to add a field.")
                    },
                )
                TipLine(
                    AnnotatedString(
                        str(S.desktop_tip_save_as_template),
                    ),
                )
            }
        }
        if (!listOpen) {
            Row(
                Modifier.padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.width(1.dp).height(44.dp).background(colors.border))
                Column(Modifier.widthIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        str(S.desktop_layout_upper),
                        style = sheetText(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
                        color = colors.tipsText,
                    )
                    Text(
                        str(S.desktop_tip_drag_sections),
                        style = sheetText(12.sp, lineHeight = 18.sp),
                        color = colors.tipsText,
                    )
                }
                SheetButton(
                    str(S.desktop_sections),
                    onShowSections,
                    kind = ButtonKind.Accent,
                    icon = SheetIcons.Table,
                    height = 34.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun TipLine(text: AnnotatedString) {
    val colors = SheetTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(4.dp).clip(CircleShape).background(Color(0xFFF99300)))
        Text(text, style = sheetText(12.sp, lineHeight = 18.sp), color = colors.tipsText)
    }
}

// Split pane -------------------------------------------------------------------------------------------------

@Composable
private fun SplitPane(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit) {
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
    val colors = SheetTheme.colors
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
                        running = (running + delta / totalPx * PERCENT).coerceIn(
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
            str(S.desktop_drag_upper),
            style = sheetText(8.sp, FontWeight.Bold, 10.sp).copy(letterSpacing = 1.sp),
            color = ink,
            modifier = Modifier.padding(top = 4.dp).readsUpward(),
        )
    }
}

private const val DOTS = 5
private const val PERCENT = 100f

/** "PREVIEW" and the A4 page, clamped to the pane. */
@Composable
private fun PreviewArea(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Column(Modifier.fillMaxSize().background(colors.previewBg)) {
        Text(
            str(S.dd_preview),
            style = sheetText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 1.5.sp),
            color = colors.textMuted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        )
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val available = maxWidth
            Box(Modifier.fillMaxSize().zillitVerticalScroll(vertical).horizontalScroll(horizontal)) {
                val pageWidth = minOf(PAGE_WIDTH, (available - 40.dp).coerceAtLeast(MIN_PAGE_WIDTH))
                Box(
                    Modifier.widthIn(min = available).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Page(state, editor, onEvent, Modifier.width(pageWidth))
                }
            }
            ZillitScrollRail(vertical, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private val PAGE_WIDTH = 794.dp
private val PAGE_HEIGHT = 1123.dp
private val MIN_PAGE_WIDTH = 360.dp

@Composable
private fun Page(state: SheetUiState, editor: EditorState, onEvent: (SheetEvent) -> Unit, modifier: Modifier) {
    val doc = docColors()
    Box(
        modifier
            .heightIn(min = PAGE_HEIGHT)
            .shadow(12.dp, RoundedCornerShape(2.dp), ambientColor = Color(0x1F000000), spotColor = Color(0x1F000000))
            .background(doc.page)
            .border(1.dp, doc.pageBorder)
            .padding(24.dp),
    ) {
        PreviewDocument(
            document = editor.document,
            members = state.members,
            selection = editor.selection,
            focusedLine = editor.focusedLine,
            focusedColumn = editor.focusedColumn,
            onEvent = onEvent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
