package com.zillit.desktop.feature.dealmemo.ui.pages.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.HolidayPayView
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoBlock
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoCardView
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoColumn
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoField
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoValue
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

/** The deal memo card (`DMDealPreviewPage.jsx:3405-4415`), drawn from [MemoCardView]. */
@Composable
internal fun MemoCardView(card: MemoCardView, onEvent: (DealMemoEvent) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pv.card)
            .border(1.dp, pv.cardBorder, shape),
    ) {
        Masthead(card)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            ZillitText(
                text = card.crewHeading,
                style = DmType.sans(26.sp, FontWeight.ExtraBold, (-0.01).em).copy(lineHeight = 31.sp),
                color = pv.ink,
            )
            if (card.crewSubtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ZillitText(text = card.crewSubtitle, style = DmType.sans(13.5.sp), color = pv.muted)
            }
            Spacer(Modifier.height(20.dp))
            FieldGrid(card.identity, onEvent)
            card.blocks.forEach { block -> MemoBlockView(block, onEvent) }
        }
    }
}

@Composable
private fun Masthead(card: MemoCardView) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
            ZillitText(text = card.projectName, style = DmType.sans(13.sp), color = pv.muted, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText(
                    text = "Crew Deal Memo",
                    style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp),
                    color = pv.ink,
                    maxLines = 1,
                )
                if (card.external) ExternalPill()
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(pv.mastheadRule))
    }
}

@Composable
private fun ExternalPill() {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(pv.amberBg)
            .border(1.dp, pv.amberBorder, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ZillitText(
            text = "EXTERNAL",
            style = DmType.sans(10.sp, FontWeight.Bold, 0.07.em),
            color = PreviewInk.Action,
            maxLines = 1,
        )
    }
}

@Composable
private fun MemoBlockView(block: MemoBlock, onEvent: (DealMemoEvent) -> Unit) {
    MemoSection(block.title) {
        when (block) {
            is MemoBlock.Fields -> {
                FieldGrid(block.fields, onEvent)
                if (block.dga.isNotEmpty()) DgaBlock(block.dga, onEvent)
            }
            is MemoBlock.HolidayPay -> HolidayPayBlock(block.view)
            is MemoBlock.Table -> MemoTable(block.columns, block.rows, block.emptyText)
            is MemoBlock.Conditions -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZillitText(
                            text = "${index + 1}.",
                            style = DmType.sans(12.sp),
                            color = pv.faint,
                            modifier = Modifier.widthIn(min = 14.dp),
                        )
                        ZillitText(text = item, style = DmType.sans(12.sp).copy(lineHeight = 19.5.sp), color = pv.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldGrid(fields: List<MemoField>, onEvent: (DealMemoEvent) -> Unit) {
    TwoColumnGrid(fields, isWide = { it.wide }) { field -> MemoFieldView(field, onEvent) }
}

/** `MF`: the label over its value — 14.5 px text, 13 px DM Mono for figures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoFieldView(field: MemoField, onEvent: (DealMemoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MemoLabel(field.label)
        Spacer(Modifier.height(2.dp))
        val style = if (field.mono) DmType.mono(13.sp) else DmType.sans(14.5.sp).copy(lineHeight = 21.75.sp)
        when (val value = field.value) {
            is MemoValue.Text -> ZillitText(text = value.text, style = style, color = pv.ink)
            is MemoValue.Figure -> Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(text = value.text, style = style, color = pv.ink, maxLines = 1)
                value.suffix?.let {
                    ZillitText(
                        text = " $it",
                        style = style,
                        color = pv.faint,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            is MemoValue.Bank -> BankIdentity(value)
            is MemoValue.Link -> ZillitText(
                text = value.text,
                style = style.copy(textDecoration = TextDecoration.Underline),
                color = PreviewInk.Action,
            )
            is MemoValue.Passport -> PassportChips(value.files, onEvent)
            is MemoValue.Parts -> FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                value.parts.forEach { ZillitText(text = it, style = style, color = pv.ink, maxLines = 1) }
            }
        }
    }
}

/** `BankIdentity`: the bank's name, then "Sort:" and "Acc:" lines in DM Mono; the dash when none. */
@Composable
private fun BankIdentity(bank: MemoValue.Bank) {
    if (bank.name.isEmpty() && bank.sortCode.isEmpty() && bank.account.isEmpty()) {
        ZillitText(text = MemoFormat.DASH, style = DmType.sans(14.5.sp), color = Color(0xFF8A8D95))
        return
    }
    Column {
        if (bank.name.isNotEmpty()) {
            ZillitText(
                text = bank.name,
                style = DmType.sans(13.5.sp, FontWeight.Bold),
                color = pv.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (bank.sortCode.isNotEmpty()) {
            ZillitText(
                text = "Sort: ${bank.sortCode}",
                style = DmType.mono(12.sp),
                color = Color(0xFF8A8D95),
                maxLines = 1,
            )
        }
        if (bank.account.isNotEmpty()) {
            ZillitText(
                text = "Acc: ${bank.account}",
                style = DmType.mono(12.sp),
                color = Color(0xFF8A8D95),
                maxLines = 1,
            )
        }
    }
}

/** One chip per passport file; View opens it — only when its bytes can be fetched. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassportChips(files: List<DealAttachment>, onEvent: (DealMemoEvent) -> Unit) {
    if (files.isEmpty()) {
        ZillitText(
            text = "Not provided",
            style = DmType.sans(14.5.sp).copy(fontStyle = FontStyle.Italic),
            color = Color(0xFF9CA3AF),
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            val shape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(Color(0xFFFFF8F0))
                    .border(1.dp, Color(0xFFF3C89A), shape)
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FileTypeBadge(file.extension)
                ZillitText(
                    text = file.name ?: "Passport / ID",
                    style = DmType.sans(14.sp, FontWeight.SemiBold),
                    color = Color(0xFF111827),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                if (file.intact) ViewChip(onClick = { onEvent(PreviewEvent.ViewPassport(file)) })
            }
        }
    }
}

/** A small file-type mark: PDF red, images teal, anything else grey. */
@Composable
internal fun FileTypeBadge(extension: String) {
    val tone = when (extension.lowercase()) {
        "pdf" -> Color(0xFFDC2626)
        "png", "jpg", "jpeg", "gif", "webp" -> Color(0xFF0D9488)
        "doc", "docx" -> Color(0xFF2563EB)
        "xls", "xlsx", "csv" -> Color(0xFF16A34A)
        else -> Color(0xFF6B7280)
    }
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(tone.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = extension.uppercase().take(4).ifEmpty { "FILE" },
            style = DmType.mono(8.5.sp, FontWeight.Bold),
            color = tone,
            maxLines = 1,
        )
    }
}

@Composable
private fun ViewChip(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(7.dp)
    MaybeTooltip("View passport / ID") {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(if (hovered) Color(0xFFF9FAFB) else Color.White)
                .border(1.dp, Color(0xFFECECEA), shape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(ZillitIcons.Eye, size = 10.dp, tint = Color(0xFF4A4D55))
            ZillitText(
                text = "View",
                style = DmType.sans(11.sp, FontWeight.Bold),
                color = Color(0xFF4A4D55),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DgaBlock(fields: List<MemoField>, onEvent: (DealMemoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pv.divider))
        Spacer(Modifier.height(12.dp))
        ZillitText(
            text = "DGA PRODUCTION FEE",
            style = DmType.display(9.sp, FontWeight.Bold, 0.04.em),
            color = pv.faint,
        )
        Spacer(Modifier.height(8.dp))
        FieldGrid(fields, onEvent)
    }
}

/** Holiday pay: the tag and treatment, the formula, then the derived rate lines. */
@Suppress("LongMethod")
@Composable
private fun HolidayPayBlock(view: HolidayPayView) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "Holiday Pay (HP) Treatment",
                style = DmType.sans(11.sp, FontWeight.SemiBold),
                color = pv.muted,
                modifier = Modifier.weight(1f),
            )
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.clip(shape).background(pv.redTagBg).border(1.dp, pv.redTagBorder, shape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                ZillitText(
                    text = view.tag,
                    style = DmType.sans(10.sp, FontWeight.SemiBold),
                    color = pv.redTagInk,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.size(12.dp))
            ZillitText(
                text = if (view.inclusive) "Inclusive" else "Exclusive (on top)",
                style = DmType.sans(11.sp, FontWeight.SemiBold),
                color = pv.body,
            )
        }
        val alertShape = RoundedCornerShape(5.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(alertShape)
                .background(pv.blueBg)
                .border(1.dp, pv.blueBorder, alertShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitIcon(ZillitIcons.Info, size = 13.dp, tint = pv.blueInk, modifier = Modifier.padding(top = 2.dp))
            ZillitText(text = view.alert, style = DmType.sans(12.sp).copy(lineHeight = 19.5.sp), color = pv.blueInk)
        }
        Spacer(Modifier.height(12.dp))
        view.lines.forEachIndexed { index, line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = line.label,
                    style = DmType.sans(12.sp),
                    color = pv.muted,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = line.value,
                    style = DmType.mono(13.sp, FontWeight.Medium),
                    color = if (line.derived) pv.teal else pv.ink,
                    maxLines = 1,
                )
            }
            if (index < view.lines.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pv.divider))
        }
    }
}

/** `DataTable`: Syne heads on the pale band, DM Mono figures, italic empty text instead of an empty table. */
@Composable
private fun MemoTable(columns: List<MemoColumn>, rows: List<List<String>>, emptyText: String) {
    if (rows.isEmpty()) {
        ZillitText(
            text = emptyText,
            style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
            color = pv.muted,
        )
        return
    }
    val shape = RoundedCornerShape(6.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, pv.divider, shape)) {
        Row(modifier = Modifier.fillMaxWidth().background(pv.tableHead).padding(horizontal = 0.dp, vertical = 8.dp)) {
            columns.forEach { column ->
                ZillitText(
                    text = column.title.uppercase(),
                    style = DmType.display(10.sp, FontWeight.Bold, 0.04.em),
                    color = pv.label,
                    maxLines = 2,
                    textAlign = if (column.alignEnd) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
            }
        }
        rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(pv.divider))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEachIndexed { index, cell ->
                    val column = columns.getOrNull(index)
                    ZillitText(
                        text = cell,
                        style = if (column?.mono == true) DmType.mono(12.5.sp) else DmType.sans(13.5.sp),
                        color = pv.ink,
                        textAlign = if (column?.alignEnd == true) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}
