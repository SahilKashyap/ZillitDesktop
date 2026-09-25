package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.abs
import kotlin.math.round

/** A tone's fill — the design system keeps its own mapping internal. */
@Composable
internal fun toneBackground(tone: StatusTone): Color {
    val colors = ZillitTheme.colors
    return when (tone) {
        StatusTone.Pending -> colors.warningSoft
        StatusTone.Progress -> colors.infoSoft
        StatusTone.Ready, StatusTone.Done -> colors.successSoft
        StatusTone.Rejected -> colors.dangerSoft
        StatusTone.Escalated -> colors.violetSoft
        else -> colors.surfaceSunken
    }
}

/** A tone's ink. */
@Composable
internal fun toneContent(tone: StatusTone): Color {
    val colors = ZillitTheme.colors
    return when (tone) {
        StatusTone.Pending -> colors.warning
        StatusTone.Progress -> colors.info
        StatusTone.Ready, StatusTone.Done -> colors.success
        StatusTone.Rejected -> colors.danger
        StatusTone.Escalated -> colors.violet
        else -> colors.textSecondary
    }
}

/**
 * The web's `Notice`: an icon, a bold title and a line of explanation, and
 * an optional action at the end (History's Export).
 */
@Composable
internal fun TitledNotice(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Pending,
    action: (@Composable () -> Unit)? = null,
) {
    val ink = toneContent(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(toneBackground(tone))
            .border(1.dp, ink.copy(alpha = NOTICE_BORDER), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon, tint = ink, size = 16.dp)
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ink,
            )
            ZillitText(
                text = "  $body",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        action?.invoke()
    }
}

/** The red count beside a row — the web's `<Badge count>`. */
@Composable
internal fun UnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .height(BADGE_HEIGHT)
            .widthIn(min = BADGE_HEIGHT)
            .clip(ZillitTheme.shapes.pill)
            .background(ZillitTheme.colors.danger)
            .padding(horizontal = ZillitTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

/**
 * The batch's three steps — Submitted, Coded, Posted — the web's
 * `ProcessStepper`. [stage] is the step in progress; 3 is all done.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Three steps, each drawn in its state.
@Composable
internal fun ProcessStepper(stage: Int, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val steps = listOf(str(S.txt_submitted), str(S.desktop_ce_coded), str(S.ah_step_posted))
    Row(modifier = modifier.widthIn(max = STEPPER_WIDTH), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, label ->
            val done = index < stage
            val current = index == stage
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(STEP_DOT)
                        .clip(ZillitTheme.shapes.pill)
                        .background(
                            when {
                                done -> colors.success
                                current -> colors.accent
                                else -> colors.surface
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = if (done || current) Color.Transparent else colors.border,
                            shape = ZillitTheme.shapes.pill,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        done -> ZillitIcon(ZillitIcons.Check, tint = Color.White, size = 12.dp)
                        current -> Box(Modifier.size(6.dp).clip(ZillitTheme.shapes.pill).background(Color.White))
                        else -> ZillitText(
                            text = (index + 1).toString(),
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
                ZillitText(
                    text = label,
                    style = ZillitTheme.typography.label.copy(
                        fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = when {
                        current -> colors.textPrimary
                        done -> colors.success
                        else -> colors.textMuted
                    },
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = ZillitTheme.spacing.sm)
                        .height(2.dp)
                        .clip(ZillitTheme.shapes.pill)
                        .background(if (index < stage) colors.success else colors.border),
                )
            }
        }
    }
}

/**
 * "BATCH GROSS £x | CODED £y | Matched" — the web's `GrossMatchBar`: the
 * batch total from the server beside the live coded total, and what is left
 * to allocate or over.
 */
@Composable
internal fun GrossMatchBar(backend: Double, live: Double, format: (Double) -> String) {
    val diff = round((live - backend) * HUNDRED) / HUNDRED
    val tone = when {
        abs(diff) <= TOLERANCE -> StatusTone.Done
        diff < 0 -> StatusTone.Pending
        else -> StatusTone.Rejected
    }
    val ink = toneContent(tone)
    val diffText = when (tone) {
        StatusTone.Done -> str(S.desktop_matched)
        StatusTone.Pending -> str(S.desktop_inv_to_allocate, format(abs(diff)))
        else -> str(S.desktop_inv_amount_over, format(diff))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.large)
                .background(toneBackground(tone))
                .border(1.dp, ink.copy(alpha = NOTICE_BORDER), ZillitTheme.shapes.large)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldLabel(str(S.desktop_pc_batch_gross))
            ZillitText(
                text = format(backend),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            )
            Divider()
            FieldLabel(str(S.desktop_ce_coded))
            ZillitText(
                text = format(live),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ink,
            )
            Divider()
            if (tone == StatusTone.Done) ZillitIcon(ZillitIcons.Check, tint = ink, size = 12.dp)
            ZillitText(
                text = diffText,
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = ink,
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(12.dp).background(ZillitTheme.colors.border))
}

/** A figure with its small-caps label above, right-aligned — the batch header's Receipts and Batch total. */
@Composable
internal fun HeaderFigure(label: String, value: String, color: Color? = null) {
    Column(horizontalAlignment = Alignment.End) {
        FieldLabel(label)
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = color ?: ZillitTheme.colors.textPrimary,
        )
    }
}

private const val NOTICE_BORDER = 0.2f
private const val MAX_BADGE = 99
private const val HUNDRED = 100.0
private const val TOLERANCE = 0.01
private val BADGE_HEIGHT = 16.dp
private val STEP_DOT = 20.dp
private val STEPPER_WIDTH = 440.dp
