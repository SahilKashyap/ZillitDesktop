package com.zillit.desktop.feature.email.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The frame every settings sub-page shares: a back arrow, a title, and a
 * scrolling body.
 *
 * Sub-pages replace the card list rather than opening beside it — the window
 * is sized for one column, and Android's settings are a stack of screens for
 * the same reason.
 */
@Composable
internal fun SettingsPage(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = "Back", onClick = onBack)
            Column(Modifier.weight(1f)) {
                ZillitText(text = title, style = ZillitTheme.typography.titleMedium)
                subtitle?.let {
                    ZillitText(
                        text = it,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
            actions?.invoke(this)
        }
        content()
    }
}

/**
 * One entry on the settings list — Android's card
 * (`res/layout/email_fragment_settings.xml`): an icon, a title, a line under
 * it, and either a chevron or a control at the trailing edge.
 */
@Composable
internal fun SettingsCard(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon, tint = colors.accent, size = ZillitDimens.iconSmall)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = title, style = ZillitTheme.typography.bodyMedium)
            ZillitText(text = detail, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> ZillitIcon(
                ZillitIcons.ChevronRight,
                tint = colors.textMuted,
                size = ZillitDimens.iconSmall,
            )
        }
    }
}

/** A row-shaped placeholder: loading, empty, or nothing to show yet. */
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().heightIn(min = HINT_HEIGHT).padding(ZillitTheme.spacing.lg),
    )
}

/**
 * A failure or a confirmation, in one line, dismissable.
 *
 * Inline rather than a toast: a settings window is often behind the mailbox,
 * and a toast that fires while it is covered is a toast nobody sees.
 */
@Composable
internal fun SettingsMessage(
    text: String,
    tone: StatusTone,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitNotice(
        text = text,
        tone = tone,
        modifier = modifier,
        action = {
            ZillitIconButton(icon = ZillitIcons.Close, contentDescription = "Dismiss", onClick = onDismiss)
        },
    )
}

/** A card-shaped row for a list item — a group, a preset, a credential. */
@Composable
internal fun SettingsRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

internal val HAIRLINE = 1.dp
private val HINT_HEIGHT = 80.dp
