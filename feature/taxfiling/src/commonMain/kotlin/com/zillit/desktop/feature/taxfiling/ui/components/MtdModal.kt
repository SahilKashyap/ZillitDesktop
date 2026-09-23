package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

private val ModalShape = RoundedCornerShape(18.dp)

/**
 * The filing surface's dialog — the web's `Modal`.
 *
 * A blurred-feeling scrim, a rounded card that scales in, a header with its
 * icon tile, a body, and a footer strip holding the buttons. Callers keep it
 * composed and drive [visible], so the exit animation plays; Escape and a
 * click on the scrim both dismiss, as on the web.
 */
@Composable
internal fun MtdModal(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Color? = null,
    iconWash: Color? = null,
    iconEdge: Color? = null,
    width: Dp = 540.dp,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible

    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(tween(ENTER_MS)),
        exit = fadeOut(tween(EXIT_MS)),
        modifier = modifier,
    ) {
        ModalScrim(onDismiss) {
            ModalCard(width = width, footer = footer, content = content) {
                ModalHeader(title, subtitle, onDismiss) {
                    if (icon != null) {
                        MtdIconTile(
                            icon = icon,
                            size = 40.dp,
                            iconSize = 20.dp,
                            radius = 11.dp,
                            tint = iconTone,
                            wash = iconWash,
                            edge = iconEdge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The dimmed page behind the card: a click on it dismisses, and it holds the
 * focus so Escape reaches it whatever is focused inside.
 */
@Composable
private fun ModalScrim(onDismiss: () -> Unit, card: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .onPreviewKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) onDismiss()
                escape
            }
            .focusRequester(focus)
            .focusable()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { card() }
}

/** The card itself: it grows in as the scrim fades, and swallows its own clicks. */
@Composable
private fun AnimatedVisibilityScope.ModalCard(
    width: Dp,
    footer: (@Composable RowScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
    header: @Composable () -> Unit,
) {
    val palette = mtdPalette()
    Column(
        modifier = Modifier
            .widthIn(max = width)
            .fillMaxWidth()
            .heightIn(max = MAX_HEIGHT)
            .animateEnterExit(
                enter = scaleIn(initialScale = SCALE_FROM, animationSpec = tween(ENTER_MS)),
                exit = scaleOut(targetScale = SCALE_FROM, animationSpec = tween(EXIT_MS)),
            )
            .shadow(28.dp, ModalShape, clip = false)
            .clip(ModalShape)
            .background(palette.surface)
            .border(1.dp, palette.border, ModalShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        header()
        MtdRule()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .zillitVerticalScroll(rememberScrollState())
                .padding(22.dp),
            content = content,
        )
        if (footer != null) {
            MtdRule()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface2)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }
}

@Composable
private fun ModalHeader(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        icon()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(
                text = title,
                style = mtdText(17.sp, FontWeight.Bold, tracking = (-0.02).em),
                color = palette.ink,
            )
            if (subtitle != null) ZillitText(text = subtitle, style = mtdText(13.sp), color = palette.ink3)
        }
        ZillitIconButton(icon = ZillitIcons.Close, contentDescription = str(S.close), onClick = onDismiss, size = 32.dp)
    }
}

/**
 * [value], or the last non-null one — what a closing dialog shows through its
 * exit animation, so its fields do not blank while it fades.
 */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value != null) last.value = value }
    return value ?: last.value
}

private val MAX_HEIGHT = 760.dp
private const val ENTER_MS = 180
private const val EXIT_MS = 140
private const val SCALE_FROM = 0.95f
