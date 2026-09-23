package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A drawer sliding in from the right — the web's antd `Drawer`, which every
 * Drive form uses (upload, create folder, edit, share, activity log).
 *
 * Same contract as `ZillitDialogShell`: keep it composed and drive [visible],
 * so the exit can play; a click on the scrim dismisses, a click inside does
 * not. [actions] pin beneath the scrolling body, so a long form never hides
 * its own Save button below the fold.
 */
@Composable
@Suppress("LongMethod") // The whole drawer chrome: scrim, header, body, actions.
internal fun DriveSideSheet(
    title: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    width: Dp = SHEET_WIDTH,
    subtitle: String? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    val colors = ZillitTheme.colors

    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(tween(ENTER_MS)),
        exit = fadeOut(tween(EXIT_MS)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .animateEnterExit(
                        enter = slideInHorizontally(tween(ENTER_MS)) { it },
                        exit = slideOutHorizontally(tween(EXIT_MS)) { it },
                    )
                    .shadow(SHEET_SHADOW, clip = false)
                    .background(colors.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(ICON_DISC)
                                .clip(ZillitTheme.shapes.medium)
                                .background(colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitIcon(icon = icon, tint = colors.accent)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(text = title, style = ZillitTheme.typography.titleMedium, maxLines = 1)
                        if (subtitle != null) {
                            ZillitText(
                                text = subtitle,
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    headerTrailing?.invoke(this)
                    ZillitIconButton(icon = ZillitIcons.Close, contentDescription = str(S.close), onClick = onDismiss)
                }
                ZillitDivider()
                if (scrollable) {
                    ZillitScrollColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                        content = content,
                    )
                } else {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
                }
                if (actions != null) {
                    ZillitDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceSunken)
                            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/** A boxed section inside a sheet — the web's bordered form groups. */
@Composable
internal fun SheetSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title != null) {
                    ZillitText(
                        text = title,
                        style = ZillitTheme.typography.label,
                        modifier = Modifier.weight(1f),
                    )
                }
                trailing?.invoke(this)
            }
        }
        content()
    }
}

internal val SHEET_WIDTH = 480.dp
private val ICON_DISC = 32.dp
private val SHEET_SHADOW = 16.dp
private const val ENTER_MS = 220
private const val EXIT_MS = 160
private const val SCRIM_ALPHA = 0.45f
