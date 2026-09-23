package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.ImportState
import com.zillit.desktop.feature.bankrec.ui.ImportStep
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrFieldLabel
import com.zillit.desktop.feature.bankrec.ui.components.BrInitialTile
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.statementDrop

/**
 * Bringing a statement in — the web's two screens in one dialog.
 *
 * First the account, then the file, chosen by browsing or dropped from the
 * desktop; nothing is uploaded until "Import & Auto-Match". Then the five
 * steps as the service works through them, and what it found.
 */
@Composable
internal fun ImportDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val flow = rememberLast(state.import.takeIf { it.open }) ?: return
    val done = flow.result != null
    ZillitDialogShell(
        title = if (flow.processing) {
            str(S.desktop_br_importing_statement)
        } else {
            str(S.desktop_br_import_bank_statement)
        },
        onDismiss = { if (!flow.processing || done) onEvent(BankRecEvent.CloseImport) },
        visible = state.import.open,
        icon = ZillitIcons.Upload,
        width = 560.dp,
        actions = {
            when {
                done -> ZillitButton(text = str(S.done_text), onClick = { onEvent(BankRecEvent.CloseImport) })
                flow.processing -> ZillitText(
                    str(S.txt_processing),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )

                else -> {
                    ZillitButton(
                        text = str(S.cancel),
                        onClick = { onEvent(BankRecEvent.CloseImport) },
                        variant = ButtonVariant.Tertiary,
                    )
                    ZillitButton(
                        text = str(S.desktop_br_import_auto_match),
                        onClick = { onEvent(BankRecEvent.StartImport) },
                        leadingIcon = BankRecIcons.Bolt,
                        enabled = flow.file != null && flow.bankAccountId.isNotBlank(),
                    )
                }
            }
        },
    ) {
        if (flow.processing) {
            Processing(flow, state.account(flow.bankAccountId))
        } else {
            Choose(flow, state, onEvent)
        }
    }
}

@Composable
private fun Choose(flow: ImportState, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BrFieldLabel(str(S.desktop_br_select_bank_account))
        if (state.bankAccounts.isEmpty()) {
            ZillitText(
                str(S.desktop_br_no_bank_accounts),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().dashed(colors.border).padding(vertical = 18.dp),
            )
        }
        state.bankAccounts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { account ->
                    AccountCard(account, account.id == flow.bankAccountId) {
                        onEvent(BankRecEvent.SelectImportAccount(account.id))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    // The file is asked for only once the account is known, as on the web.
    if (flow.bankAccountId.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BrFieldLabel(str(S.drive_upload_file))
            DropZone(flow, onEvent)
            ZillitText(
                str(S.desktop_br_supported_formats),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
    flow.error?.let {
        ZillitText(
            it,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.danger,
        )
    }
}

@Composable
private fun RowScope.AccountCard(account: BankAccountRef, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val edge by animateColorAsState(if (selected || hovered) colors.accent else colors.border)
    val shape = RoundedCornerShape(10.dp)
    val sub = listOf(BankRecFormat.sortCode(account.sortCode), account.accountNumber).filter { it.isNotBlank() }
        .joinToString(" · ").ifBlank { account.holderName }
    Row(
        Modifier.weight(1f).clip(shape).background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, edge, shape).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrInitialTile(account.displayName, size = 32.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(
                account.displayName.ifBlank { str(S.dm_pay_card_bank) },
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (sub.isNotBlank()) ZillitText(sub, style = mono(10.5.sp), color = colors.textMuted, maxLines = 1)
        }
        if (selected) ZillitIcon(ZillitIcons.Check, tint = colors.accent, size = 14.dp)
    }
}

@Suppress("LongMethod") // Three looks — idle, dragged over, chosen — and the click and drop behind them.
@Composable
private fun DropZone(flow: ImportState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val file = flow.file
    val line by animateColorAsState(
        when {
            flow.dragOver -> colors.accent
            file != null -> colors.success
            hovered -> colors.accent
            else -> colors.borderStrong
        },
    )
    val fill by animateColorAsState(
        when {
            flow.dragOver || (hovered && file == null) -> colors.accentSoft
            file != null -> colors.successSoft
            else -> colors.surface
        },
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fill).dashed(line, width = 2.dp)
            .statementDrop(
                enabled = !flow.processing,
                onHover = { onEvent(BankRecEvent.ImportDragOver(it)) },
                onFile = { onEvent(BankRecEvent.DropStatement(it)) },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onEvent(BankRecEvent.BrowseStatement) }
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            flow.picking -> ZillitSpinner(size = 24.dp)
            else -> ZillitIcon(
                ZillitIcons.Upload,
                tint = if (file != null) colors.success else colors.textMuted,
                size = 26.dp,
            )
        }
        if (file != null) {
            ZillitText(
                file.name,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.success,
                textAlign = TextAlign.Center,
            )
            ZillitText(
                str(S.desktop_br_click_to_choose_another, sizeLabel(file.bytes.size.toLong())),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        } else {
            ZillitText(
                str(S.desktop_br_drop_statement_here),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            ZillitText(
                str(S.desktop_br_or_click_to_browse),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun Processing(flow: ImportState, account: BankAccountRef?) {
    val colors = ZillitTheme.colors
    val result = flow.result
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.File, tint = colors.accentText, size = 18.dp)
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                flow.file?.name.orEmpty(),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            ZillitText(
                str(S.desktop_br_named_statement, account?.displayName?.ifBlank { null } ?: str(S.desktop_bank)),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        if (result != null) {
            ZillitText(
                str(S.desktop_card_transactions_count, result.imported),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.success,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.successSoft)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        ImportStep.entries.forEachIndexed { index, step ->
            StepRow(step, index, flow.step, finished = result != null)
        }
    }
    if (result != null) ResultTiles(result)
}

@Suppress("CyclomaticComplexMethod") // Three states, each with its own colour, weight and glyph.
@Composable
private fun StepRow(step: ImportStep, index: Int, current: Int, finished: Boolean) {
    val colors = ZillitTheme.colors
    val done = index < current || (index == current && finished)
    val active = index == current && !finished
    val circle by animateColorAsState(
        when {
            done -> colors.success
            active -> colors.accent
            else -> colors.surfaceHover
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(28.dp).alpha(if (active) pulse() else 1f).clip(CircleShape).background(circle),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    done -> ZillitIcon(ZillitIcons.Check, tint = colors.surface, size = 13.dp)
                    active -> ZillitSpinner(size = 13.dp, color = colors.textOnAccent)
                    else -> ZillitIcon(step.icon, tint = colors.textMuted, size = 13.dp)
                }
            }
            if (index < ImportStep.entries.lastIndex) {
                Box(
                    Modifier.width(1.dp).height(22.dp)
                        .background(if (done) colors.success.copy(alpha = CONNECTOR_ALPHA) else colors.border),
                )
            }
        }
        ZillitText(
            step.label,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = when {
                    active -> FontWeight.SemiBold
                    done -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
            ),
            color = when {
                done -> colors.success
                active -> colors.textPrimary
                else -> colors.textMuted
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun pulse(): Float = rememberInfiniteTransition().animateFloat(
    initialValue = 1f,
    targetValue = PULSE_LOW,
    animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
).value

@Composable
private fun ResultTiles(result: ImportResult) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ResultTile(result.matched, str(S.desktop_matched), BrTone.Green)
        ResultTile(result.suggested, str(S.desktop_suggested), BrTone.Amber)
        ResultTile(result.unmatched, str(S.desktop_dm_unmatched), BrTone.Gray)
        ResultTile(result.fraud, str(S.desktop_fraud_flags), BrTone.Red)
    }
}

@Composable
private fun RowScope.ResultTile(count: Int, label: String, tone: BrTone) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.weight(1f).clip(shape).background(tone.bg()).border(1.dp, tone.edge(), shape)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(count.toString(), style = mono(18.sp, FontWeight.Bold), color = tone.fg())
        ZillitText(label.uppercase(), style = eyebrow(9.5.sp), color = tone.fg(), maxLines = 1)
    }
}

private val ImportStep.icon: ImageVector
    get() = when (this) {
        ImportStep.Upload -> ZillitIcons.Upload
        ImportStep.Parse -> ZillitIcons.File
        ImportStep.Match -> BankRecIcons.Bolt
        ImportStep.Validate -> ZillitIcons.Shield
        ImportStep.Done -> ZillitIcons.Check
    }

/** A dashed outline — the web's drop zone and its empty account list. */
private fun Modifier.dashed(color: Color, width: Dp = 1.dp) =
    drawBehind {
        val stroke = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(DASH_CORNER.toPx()),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF))),
        )
    }

/** `1.2 MB`, `840 KB` — the file's size, for the line under its name. */
private fun sizeLabel(bytes: Long): String = when {
    bytes >= MEGABYTE -> "${(bytes * TENTHS / MEGABYTE) / TENTHS.toDouble()} MB"
    bytes >= KILOBYTE -> "${bytes / KILOBYTE} KB"
    else -> "$bytes B"
}

private val DASH_CORNER = 12.dp
private const val DASH_ON = 10f
private const val DASH_OFF = 7f
private const val PULSE_LOW = 0.55f
private const val PULSE_MILLIS = 700
private const val CONNECTOR_ALPHA = 0.5f
private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L
private const val TENTHS = 10L
