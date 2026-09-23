package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The one dialog chrome: scrim, centred card, header, entrance.
 *
 * Every dialog was hand-rolling the same scrim-plus-clipped-column and coming
 * out slightly different — different paddings, no elevation, titles styled
 * three ways. This shell owns the barrier (a click outside dismisses, a click
 * inside does not), the card's shadow and border, a header with an icon on the
 * accent disc, and both ends of the transition: a scale-and-fade entrance so
 * the dialog arrives rather than appears, and the reverse on the way out.
 * Callers keep the shell composed and drive [visible] — an `if` around it
 * would unmount the dialog before the exit could play. Content below the
 * header is the caller's.
 *
 * ## Overflow, and why the buttons go in [actions]
 *
 * A body past [maxHeight] used to be **clipped**, silently, and the first
 * casualty is always the action row at the bottom — a dialog you cannot submit
 * and cannot tell why. The composer's Send button was half off its own card.
 *
 * [scrollable] fixes the clipping, but scrolling alone is not enough: it makes
 * a button *reachable*, not *visible*, and one below the fold of a dialog is
 * one most people never find. So anything that can grow — a composer, a form
 * with a variable number of rows — should pass its buttons as [actions], which
 * are pinned beneath the scrolling body. Short dialogs can keep theirs in
 * [content] and never notice.
 *
 * Pass `scrollable = false` when the body hosts its own scrolling list: a
 * `LazyColumn` or a virtualised [ZillitDataTable] inside a scrolling parent is
 * measured against an unbounded height, which Compose refuses outright.
 * `InvitationsPanel` is the one such caller today.
 */
@Composable
fun ZillitDialogShell(
    title: String,
    onDismiss: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    width: Dp = DIALOG_WIDTH,
    maxHeight: Dp = DIALOG_MAX_HEIGHT,
    scrollable: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Starts false so the first frame with visible=true animates in; flipping
    // back animates out, and AnimatedVisibility keeps the content alive until
    // the exit finishes, then removes it — no ghost barrier eating clicks.
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible

    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(tween(ENTRANCE_MS)),
        exit = fadeOut(tween(EXIT_MS)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = width)
                    .heightIn(max = maxHeight)
                    // The card scales inside the parent's fade — the scrim
                    // only fades, the card also grows in and shrinks away.
                    .animateEnterExit(
                        enter = scaleIn(
                            initialScale = ENTRANCE_SCALE_FROM,
                            animationSpec = tween(ENTRANCE_MS),
                        ),
                        exit = scaleOut(
                            targetScale = ENTRANCE_SCALE_FROM,
                            animationSpec = tween(EXIT_MS),
                        ),
                    )
                    .shadow(DIALOG_ELEVATION, ZillitTheme.shapes.large)
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.surface)
                    .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                    // Swallows the barrier's click so the dialog stays open.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                ShellHeader(title = title, subtitle = subtitle, icon = icon, onDismiss = onDismiss)
                ShellRule()
                ShellBody(scrollable, content)
                actions?.let { ShellActions(it) }
            }
        }
    }
}

/** Icon on its accent disc, title and subtitle, and the close affordance. */
/** The hairline between the shell's three bands. */
@Composable
private fun ShellRule() {
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
}

/**
 * The scrolling body.
 *
 * `fill = false` on the weight so a short dialog still hugs its content —
 * weighting alone stretches every dialog to the full max height with its
 * buttons stranded at the bottom of a mostly empty card.
 */
@Composable
private fun ColumnScope.ShellBody(
    scrollable: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.zillitVerticalScroll(scroll) else Modifier)
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            content = content,
        )
        if (scrollable) ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

/** The pinned action row and its rule. See the `actions` parameter. */
@Composable
private fun ShellActions(actions: @Composable RowScope.() -> Unit) {
    ShellRule()
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
    )
}

@Composable
private fun ShellHeader(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(HEADER_DISC)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = ZillitTheme.colors.accentText,
                    size = HEADER_ICON,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleLarge)
            if (subtitle != null) {
                ZillitText(
                    text = subtitle,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.close),
            onClick = onDismiss,
        )
    }
}

private val DIALOG_WIDTH = 460.dp
private val DIALOG_MAX_HEIGHT = 720.dp
private val DIALOG_ELEVATION = 24.dp
private val HAIRLINE = 1.dp
private val HEADER_DISC = 40.dp
private val HEADER_ICON = 20.dp
private const val SCRIM_ALPHA = 0.55f
private const val ENTRANCE_MS = 180
private const val EXIT_MS = 140
private const val ENTRANCE_SCALE_FROM = 0.94f
