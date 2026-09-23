// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.productionreport.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * The report dialogs' shell — the web's call-sheet `Modal`: a 40 % scrim that
 * closes on click, a radius-12 card, a tinted header with the title and an
 * orange close disc, and a body. Escape closes too (the web's did not).
 */
@Composable
internal fun ReportModal(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 600.dp,
    maxHeight: Dp = 720.dp,
    scrollable: Boolean = true,
    closeOnScrim: Boolean = true,
    /** What Escape does — closing, unless the dialog has an inner step to back out of first. */
    onEscape: () -> Unit = onClose,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalScrim(onDismiss = if (closeOnScrim) onClose else null, onEscape = onEscape) {
        Column(
            modifier = modifier
                .widthIn(max = width)
                .fillMaxWidth(WIDTH_FRACTION)
                .heightIn(max = maxHeight)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(ReportTheme.colors.surface)
                .border(1.dp, ReportTheme.colors.border, RoundedCornerShape(12.dp))
                .swallowClicks(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ReportTheme.colors.elevated)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = reportText(14.sp, FontWeight.Medium),
                    color = ReportTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                CloseDisc(onClose)
            }
            Box(Modifier.fillMaxWidth().background(ReportTheme.colors.border).heightIn(min = 1.dp, max = 1.dp))
            if (scrollable) {
                val scroll = rememberScrollState()
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    Column(Modifier.fillMaxWidth().zillitVerticalScroll(scroll).padding(14.dp), content = content)
                    ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
                }
            } else {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(14.dp), content = content)
            }
        }
    }
}

/** The orange close disc of every report dialog. */
@Composable
internal fun CloseDisc(onClose: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (hovered) ReportTheme.colors.accentHover else Color(0xFFF99300))
            .hoverable(source)
            .plainClick(source = source, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            ZillitIcons.Close,
            contentDescription = str(S.close),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** The scrim every overlay shares, with the web's fade and the card's scale-in. */
@Composable
internal fun ModalScrim(onDismiss: (() -> Unit)?, onEscape: (() -> Unit)?, content: @Composable () -> Unit) {
    val appear = remember { MutableTransitionState(false) }.apply { targetState = true }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AnimatedVisibility(visibleState = appear, enter = fadeIn(tween(ENTER_MS)), exit = fadeOut(tween(ENTER_MS))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ReportTheme.colors.scrim)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && onEscape != null) {
                        onEscape()
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.animateEnterExit(
                    enter = scaleIn(initialScale = 0.96f, animationSpec = tween(ENTER_MS)),
                    exit = scaleOut(targetScale = 0.96f),
                ),
            ) {
                content()
            }
        }
    }
}

/** Stops a click inside a card from reaching the scrim behind it. */
@Composable
internal fun Modifier.swallowClicks(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})

/**
 * The web's `ConfirmModal`: a warning disc, a centred title and message, and
 * Cancel · confirm [· secondary] right-aligned in a tinted footer. With a
 * secondary action the destructive confirm steps back to an outline.
 */
@Composable
internal fun ConfirmModal(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    /** The request is in flight: the buttons disable and the confirm reads "Working…" until it settles. */
    busy: Boolean = false,
) {
    val colors = ReportTheme.colors
    ModalScrim(onDismiss = null, onEscape = { if (!busy) onCancel() }) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(WIDTH_FRACTION)
                .shadow(28.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .swallowClicks(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(colors.redBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ZillitIcons.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF04438),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    title,
                    style = reportText(18.sp, FontWeight.SemiBold, 26.sp),
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Text(
                    message,
                    style = reportText(14.sp, lineHeight = 22.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(colors.border))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.elevated)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ReportButton(str(S.cancel), onCancel, kind = ButtonKind.Outline, fontSize = 12.sp, enabled = !busy)
                val confirmKind = when {
                    secondaryLabel != null -> ButtonKind.DangerOutline
                    danger -> ButtonKind.Danger
                    else -> ButtonKind.Accent
                }
                ReportButton(
                    if (busy) str(S.desktop_working_ellipsis) else confirmLabel,
                    onConfirm,
                    kind = confirmKind,
                    fontSize = 12.sp,
                    enabled = !busy,
                )
                secondaryLabel?.let {
                    ReportButton(it, onSecondary, kind = ButtonKind.Accent, fontSize = 12.sp, enabled = !busy)
                }
            }
        }
    }
}

private const val ENTER_MS = 160
private const val WIDTH_FRACTION = 0.95f
