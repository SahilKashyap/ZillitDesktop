package com.zillit.desktop.feature.dealmemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.ui.BackSquare
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.NoticeTemplateEvent
import com.zillit.desktop.feature.dealmemo.ui.NoticeTemplateMode
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirm
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirmKind
import com.zillit.desktop.feature.dealmemo.ui.components.DmDropPanel
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

/** A placeholder the letter can carry, with the words and sample the editor shows for it. */
private enum class Placeholder(
    val key: String,
    private val labelKey: String,
    val sample: String,
    val icon: ImageVector,
) {
    CrewName("crew_name", S.dm_ph_crew_name, "Jordan Avery", ZillitIcons.User),
    ContractEnd("contract_end_date", S.dm_ph_contract_end_date, "12 Aug 2026", ZillitIcons.Calendar),
    LastPayDay("last_pay_day", S.dm_ph_last_pay_day, "28 Aug 2026", ZillitIcons.CreditCard),
    NoticePeriod("notice_period", S.dm_ph_notice_period, "2-week", ZillitIcons.Clock),
    ;

    val label: String get() = str(labelKey)

    val token: String get() = "{{$key}}"
}

private val TOKEN = Regex("\\{\\{(\\w+)\\}\\}")
private val EDITOR_AMBER = Color(0xFFEE8013)
private val EDITOR_AMBER_DEEP = Color(0xFFC4670A)
private val EDITOR_AMBER_WASH = Color(0xFFFDF2E2)

/** The notice template editor — the web's `DMNoticeTemplatePage`. */
@Composable
fun NoticeTemplatePage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val template = state.noticeTemplate
    Column(modifier = Modifier.fillMaxSize().background(dm.page)) {
        TemplateHeader(state, onEvent)
        if (template.loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 96.dp), contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitSpinner(size = 16.dp, color = dm.ink3)
                    ZillitText(text = str(S.dm_loading), style = DmType.sans(13.sp), color = dm.ink3)
                }
            }
        } else {
            TemplateBody(state, onEvent, Modifier.weight(1f))
        }
    }
    DmConfirm(
        visible = template.confirmReset,
        title = str(S.dm_notice_template_reset_title),
        message = str(S.dm_notice_template_reset_msg),
        confirmLabel = str(S.dm_filter_reset),
        onConfirm = { onEvent(NoticeTemplateEvent.ConfirmReset) },
        onCancel = { onEvent(NoticeTemplateEvent.CancelReset) },
        kind = DmConfirmKind.Warning,
    )
}

@Composable
private fun TemplateHeader(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val template = state.noticeTemplate
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(dm.page.copy(alpha = 0.92f))
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackSquare(onClick = { onEvent(NoticeTemplateEvent.Close) }, size = 36)
        ZillitText(text = "CONTRACTS", style = DmType.sans(11.sp, FontWeight.Bold, 0.08.em), color = dm.accent)
        ZillitText(text = "/", style = DmType.sans(12.5.sp), color = dm.placeholder)
        ZillitText(
            text = str(S.dm_notice_template_title),
            style = DmType.sans(12.5.sp, FontWeight.SemiBold),
            color = dm.ink2,
        )
        Spacer(Modifier.weight(1f))
        if (!template.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(
                        if (template.dirty) EDITOR_AMBER else Color(0xFF0C7A48),
                    ),
                )
                ZillitText(
                    text = if (template.dirty) str(S.dm_nda_unsaved) else str(S.dm_notice_template_status_saved),
                    style = DmType.sans(12.5.sp, FontWeight.Medium),
                    color = if (template.dirty) dm.ink2 else dm.ink3,
                )
            }
        }
        SaveTemplateButton(state, onEvent)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
}

@Composable
private fun SaveTemplateButton(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val template = state.noticeTemplate
    val (source, hovered) = rememberHover()
    val enabled = !template.loading && !template.saving
    val background = when {
        template.justSaved -> Color(0xFF0C7A48)
        hovered && enabled -> EDITOR_AMBER_DEEP
        else -> EDITOR_AMBER
    }
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .shadow(
                4.dp,
                shape,
                ambientColor = EDITOR_AMBER.copy(alpha = 0.5f),
                spotColor = EDITOR_AMBER.copy(alpha = 0.5f),
            )
            .height(36.dp)
            .clip(shape)
            .background(background.copy(alpha = if (enabled) 1f else 0.55f))
            .hoverable(source)
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                ) { onEvent(NoticeTemplateEvent.Save) } else Modifier,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            template.justSaved -> ZillitIcon(ZillitIcons.Check, size = 15.dp, tint = Color.White)
            template.saving -> {
                ZillitSpinner(size = 13.dp, color = Color.White)
                ZillitText(
                    text = str(S.dm_nda_saving),
                    style = DmType.sans(13.sp, FontWeight.SemiBold),
                    color = Color.White,
                )
            }
            else -> {
                ZillitIcon(ZillitIcons.Save, size = 15.dp, tint = Color.White)
                ZillitText(
                    text = str(S.dm_template_save),
                    style = DmType.sans(13.sp, FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun TemplateBody(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit, modifier: Modifier) {
    val template = state.noticeTemplate
    var field by remember { mutableStateOf(TextFieldValue(template.text)) }
    // Load and reset replace the text from outside the editor.
    LaunchedEffect(template.text) {
        if (template.text != field.text) field = TextFieldValue(template.text, TextRange(template.text.length))
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(horizontal = 28.dp, vertical = 30.dp)) {
            ZillitText(
                text = str(S.dm_notice_template_title),
                style = DmType.sans(25.sp, FontWeight.ExtraBold, (-0.025).em),
                color = dm.ink,
            )
            Spacer(Modifier.height(9.dp))
            ZillitText(
                text = str(S.dm_notice_template_subtitle),
                style = DmType.sans(15.sp).copy(lineHeight = 23.sp),
                color = dm.ink2,
                modifier = Modifier.widthIn(max = 660.dp),
            )
            Spacer(Modifier.height(24.dp))
            if (template.mode == NoticeTemplateMode.Edit) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AddPlaceholders(onPick = { placeholder ->
                        val insert = placeholder.token + " "
                        val start = field.selection.start.coerceIn(0, field.text.length)
                        val end = field.selection.end.coerceIn(start, field.text.length)
                        val text = field.text.replaceRange(start, end, insert)
                        field = TextFieldValue(text, TextRange(start + insert.length))
                        onEvent(NoticeTemplateEvent.Edit(text))
                    })
                }
            }
            EditorCard(
                mode = template.mode,
                field = field,
                onFieldChange = {
                    field = it
                    if (it.text != template.text) onEvent(NoticeTemplateEvent.Edit(it.text))
                },
                onEvent = onEvent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddPlaceholders(onPick: (Placeholder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(9.dp)
    val drop = with(LocalDensity.current) { 38.dp.roundToPx() }
    Box {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(if (hovered) Color(0xFFFCE6C8) else Color(0xFFFDF2E2))
                .border(1.dp, Color(0xFFF3C89A), shape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitIcon(ZillitIcons.Add, size = 13.dp, tint = EDITOR_AMBER_DEEP)
            ZillitText(
                text = str(S.dm_nda_add_placeholders),
                style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                color = EDITOR_AMBER_DEEP,
            )
        }
        DmDropPanel(open = open, onDismiss = { open = false }, offsetY = drop, width = 230.dp) {
            Placeholder.entries.forEach { placeholder ->
                PlaceholderItem(placeholder) {
                    open = false
                    onPick(placeholder)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderItem(placeholder: Placeholder, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) dm.controlHoverBg else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFFDF2E2)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(placeholder.icon, size = 14.dp, tint = EDITOR_AMBER_DEEP)
        }
        ZillitText(text = placeholder.label, style = DmType.sans(13.5.sp, FontWeight.SemiBold), color = dm.ink)
    }
}

@Composable
private fun EditorCard(
    mode: NoticeTemplateMode,
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    onEvent: (DealMemoEvent) -> Unit,
    modifier: Modifier,
) {
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                if (focused) 10.dp else 4.dp,
                shape,
                ambientColor = Color(0x240F1115),
                spotColor = Color(0x240F1115),
            )
            .clip(shape)
            .background(dm.card)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xFFF3C89A) else dm.cardBorder, shape),
    ) {
        EditorToolbar(mode, onEvent)
        Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 48.dp, bottom = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val letter = DmType.sans(15.5.sp).copy(color = dm.ink, lineHeight = 27.6.sp)
            if (mode == NoticeTemplateMode.Edit) {
                BasicTextField(
                    value = field,
                    onValueChange = onFieldChange,
                    textStyle = letter,
                    cursorBrush = SolidColor(EDITOR_AMBER),
                    interactionSource = focus,
                    visualTransformation = TokenPills(),
                    modifier = Modifier.widthIn(max = 624.dp).fillMaxWidth(),
                )
            } else {
                ZillitText(
                    text = preview(field.text),
                    style = letter,
                    modifier = Modifier.widthIn(max = 624.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EditorToolbar(mode: NoticeTemplateMode, onEvent: (DealMemoEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (ZillitTheme.colors.isDark) dm.tableHeader else Color(0xFFFAF9F6))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier.clip(shape).background(dm.track).border(1.dp, dm.cardBorder, shape).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SegmentTab(
                str(S.dm_notice_template_tab_edit),
                ZillitIcons.Edit,
                mode == NoticeTemplateMode.Edit,
            ) { onEvent(NoticeTemplateEvent.SetMode(NoticeTemplateMode.Edit)) }
            SegmentTab(
                str(S.dm_notice_template_tab_preview),
                ZillitIcons.Eye,
                mode == NoticeTemplateMode.Preview,
            ) { onEvent(NoticeTemplateEvent.SetMode(NoticeTemplateMode.Preview)) }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (mode == NoticeTemplateMode.Preview) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(22.dp, 14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(EDITOR_AMBER.copy(alpha = 0.14f)),
                    )
                    ZillitText(
                        text = str(S.dm_notice_template_preview_note),
                        style = DmType.sans(12.sp, FontWeight.Medium),
                        color = EDITOR_AMBER_DEEP,
                    )
                }
            }
            ToolbarLink(str(S.dm_filter_reset), ZillitIcons.Reload) { onEvent(NoticeTemplateEvent.AskReset) }
            ToolbarLink(str(S.dm_close), ZillitIcons.Close) { onEvent(NoticeTemplateEvent.Close) }
        }
    }
}

@Composable
private fun SegmentTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = Modifier
            .then(if (selected) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(if (selected) dm.card else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 15.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon, size = 14.dp, tint = if (selected) EDITOR_AMBER else dm.ink3)
        ZillitText(
            text = label,
            style = DmType.sans(12.5.sp, FontWeight.SemiBold),
            color = if (selected) dm.ink else dm.ink3,
        )
    }
}

@Composable
private fun ToolbarLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered) dm.track else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon, size = 13.dp, tint = dm.ink2)
        ZillitText(text = label, style = DmType.sans(12.sp, FontWeight.SemiBold), color = dm.ink2)
    }
}

/**
 * Shows every known `{{token}}` as an amber mono pill while leaving the text
 * editable exactly as it is stored — the web's non-editable token chips,
 * without a second model of the letter to keep in step.
 */
private class TokenPills : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            TOKEN.findAll(text.text).forEach { match ->
                if (Placeholder.entries.any { it.key == match.groupValues[1] }) {
                    addStyle(
                        SpanStyle(
                            color = EDITOR_AMBER_DEEP,
                            background = EDITOR_AMBER_WASH,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        match.range.first,
                        match.range.last + 1,
                    )
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/** The letter with example values in place of its tokens, highlighted; unknown tokens stay raw. */
private fun preview(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    TOKEN.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first).replace(' ', ' '))
        val placeholder = Placeholder.entries.firstOrNull { it.key == match.groupValues[1] }
        if (placeholder == null) {
            append(match.value)
        } else {
            withStyle(
                SpanStyle(background = EDITOR_AMBER.copy(alpha = 0.10f), fontWeight = FontWeight.Medium),
            ) { append(placeholder.sample) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor).replace(' ', ' '))
}
