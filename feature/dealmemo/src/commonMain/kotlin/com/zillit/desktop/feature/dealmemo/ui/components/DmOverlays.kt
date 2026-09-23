package com.zillit.desktop.feature.dealmemo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The web's `OverlayModal`: a dimmed, blurred-looking backdrop, a white panel
 * with a Syne title and a round ✕, and an optional grey footer for the
 * buttons. Escape, the ✕ and the backdrop all dismiss — unless [dismissible]
 * is off while work is in flight.
 */
@Suppress("LongMethod")
@Composable
fun DmModal(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 480.dp,
    dismissible: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    /** Fill the window's height (the document viewers) instead of fitting the content. */
    fullHeight: Boolean = false,
    /** Actions beside the close button — Download on the viewers. */
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    /** A title richer than one line of text; replaces [title] when set. */
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    showClose: Boolean = true,
    /** Escape and the backdrop — false leaves only the modal's own buttons to close it. */
    closeOnBackdrop: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    val close = { if (dismissible) onDismiss() }
    val backdropClose = { if (dismissible && closeOnBackdrop) onDismiss() }
    AnimatedVisibility(visibleState = transition, enter = fadeIn(tween(ENTER_MS)), exit = fadeOut(tween(EXIT_MS))) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = BACKDROP_ALPHA))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = backdropClose,
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .then(if (fullHeight) Modifier.fillMaxHeight() else Modifier.heightIn(max = 760.dp))
                    .animateEnterExit(
                        enter = scaleIn(initialScale = SCALE_FROM, animationSpec = tween(ENTER_MS)),
                        exit = scaleOut(targetScale = SCALE_FROM, animationSpec = tween(EXIT_MS)),
                    )
                    .shadow(24.dp, shape)
                    .clip(shape)
                    .background(dm.card)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (titleContent != null) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            content = titleContent,
                        )
                    } else {
                        ZillitText(
                            text = title,
                            style = DmType.display(14.sp, FontWeight.SemiBold),
                            color = dm.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    }
                    if (headerActions != null) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = headerActions,
                        )
                    }
                    if (showClose) DmRoundIcon(
                        ZillitIcons.Close,
                        tooltip = str(S.dm_close),
                        onClick = close,
                        iconSize = 12.dp,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(dm.hairline))
                Column(modifier = Modifier.weight(1f, fill = fullHeight), content = content)
                if (footer != null) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.hairline))
                    Row(
                        modifier = Modifier.fillMaxWidth().background(dm.modalFooter).padding(
                            horizontal = 24.dp,
                            vertical = 16.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = footer,
                    )
                }
            }
        }
    }
}

/** The kinds of `ConfirmModal`: a red delete, or an amber warning. */
enum class DmConfirmKind { Danger, Warning }

/**
 * The web's `ConfirmModal`: an icon and a Syne title on a grey header, the
 * question, then Cancel beside the confirm — red for danger, amber for a warning.
 */
@Composable
fun DmConfirm(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    kind: DmConfirmKind = DmConfirmKind.Danger,
    loading: Boolean = false,
    loadingLabel: String = confirmLabel,
    cancelLabel: String = str(S.dm_cancel),
) {
    DmModal(
        visible = visible,
        title = title,
        onDismiss = onCancel,
        maxWidth = 420.dp,
        dismissible = !loading,
        footer = {
            DmButton(cancelLabel, onClick = onCancel, style = DmButtonStyle.ModalNeutral, enabled = !loading)
            DmButton(
                text = if (loading) loadingLabel else confirmLabel,
                onClick = onConfirm,
                style = if (kind == DmConfirmKind.Danger) DmButtonStyle.ModalDanger else DmButtonStyle.ModalPrimary,
                loading = loading,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val (ring, ink) = if (kind == DmConfirmKind.Danger) {
                Color(0xFFFEE2E2) to Color(0xFFEF4444)
            } else {
                Color(0xFFFFEDD5) to Color(0xFFEA580C)
            }
            Box(Modifier.size(28.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
                ZillitIcon(ZillitIcons.Warning, size = 14.dp, tint = ink)
            }
            ZillitText(
                text = message,
                style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
                color = dm.ink2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A popup panel dropped [offsetY] below its anchor's top-right corner. */
@Composable
fun DmDropPanel(
    open: Boolean,
    onDismiss: () -> Unit,
    offsetY: Int,
    width: Dp = 320.dp,
    alignEnd: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .width(width)
                .shadow(18.dp, shape, ambientColor = Color(0x66140F0A), spotColor = Color(0x66140F0A))
                .clip(shape)
                .background(dm.card)
                .border(
                    1.dp,
                    if (ZillitTheme.colors.isDark) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        Color(0xFFE5E5E5)
                    },
                    shape,
                )
                .padding(7.dp),
            content = content,
        )
    }
}

/** One group title inside a drop panel — `DEAL REGISTER`. */
@Composable
fun DmMenuGroupTitle(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.sans(10.sp, FontWeight.Bold, 0.14.em),
        color = Color(0xFFA3A3A3),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun DmMenuSeparator() {
    Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(dm.track))
}

/**
 * A menu item: a gradient badge, a label with a line of description, and — on
 * hover — the file extension it produces.
 */
@Composable
fun DmMenuItem(
    label: String,
    description: String,
    badge: String,
    gradient: Pair<Color, Color>,
    onClick: () -> Unit,
    extension: String? = null,
) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) dm.controlHoverBg else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DmGradientBadge(badge, gradient.first, gradient.second)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = DmType.sans(14.sp, FontWeight.SemiBold), color = dm.ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            ZillitText(text = description, style = DmType.sans(12.sp), color = dm.ink3, maxLines = 2)
        }
        if (extension != null) {
            Box(
                modifier = Modifier
                    .alpha(if (hovered) 1f else 0f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(dm.soft)
                    .border(1.dp, dm.track, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                ZillitText(text = extension, style = DmType.mono(10.sp), color = dm.ink3, maxLines = 1)
            }
        }
    }
}

/**
 * The shared history drawer (`ui/HistoryPanel.jsx`): a panel that slides in
 * from the right over a dim backdrop and lists a deal's audit trail, newest
 * first, as a timeline.
 */
@Composable
fun DmSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    AnimatedVisibility(visibleState = transition, enter = fadeIn(tween(PANEL_MS)), exit = fadeOut(tween(PANEL_MS))) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = PANEL_BACKDROP_ALPHA))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val shape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .animateEnterExit(
                        enter = slideInHorizontally(tween(PANEL_MS)) { it / 2 },
                        exit = slideOutHorizontally(tween(PANEL_MS)) { it / 2 },
                    )
                    .shadow(24.dp, shape)
                    .clip(shape)
                    .background(dm.card)
                    .border(1.dp, dm.hairline, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                content = content,
            )
        }
    }
}

/** A header row for [DmSidePanel]: a small caps title over a bold subtitle, and a close button. */
@Composable
fun DmPanelHeader(title: String, subtitle: String, onClose: () -> Unit, icon: ImageVector = ZillitIcons.Close) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = title.uppercase(),
                style = DmType.sans(10.sp, FontWeight.SemiBold, 0.05.em),
                color = Color(0xFF9CA3AF),
            )
            Spacer(Modifier.height(2.dp))
            ZillitText(text = subtitle, style = DmType.sans(14.sp, FontWeight.Bold), color = dm.ink, maxLines = 1)
        }
        DmRoundIcon(icon, tooltip = str(S.desktop_close_history), onClick = onClose, iconSize = 12.dp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.hairline))
}

private const val ENTER_MS = 150
private const val EXIT_MS = 120
private const val PANEL_MS = 180
private const val SCALE_FROM = 0.96f
private const val BACKDROP_ALPHA = 0.40f
private const val PANEL_BACKDROP_ALPHA = 0.30f
