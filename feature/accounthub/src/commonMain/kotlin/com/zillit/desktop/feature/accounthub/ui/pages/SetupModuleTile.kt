package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * A downstream module's setup, previewed rather than edited here.
 *
 * The web's `HubModuleCard`: an icon, a title with a configured/not pill, a
 * line of description and one button through to the real editor. It reads as
 * a *pointer*, which is the honest shape — the settings behind it belong to
 * that module, not to Production Setup.
 *
 * [configured] is null when nothing on this wire says either way. The tile
 * then shows no pill at all rather than claiming "Setup required" about a
 * state it cannot see — the mistake the web documents on its own Timecard
 * tile, which is hardcoded to unconfigured.
 */
@Composable
internal fun SetupModuleTile(
    title: String,
    description: String,
    icon: ImageVector,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    configured: Boolean? = null,
    actionText: String = "Configure",
    enabled: Boolean = true,
) {
    ZillitSectionCard(modifier = modifier.fillMaxWidth(), title = title, icon = icon) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                configured?.let {
                    ZillitStatusPill(
                        label = if (it) "Configured" else "Setup required",
                        tone = if (it) StatusTone.Done else StatusTone.Pending,
                    )
                }
                ZillitText(
                    text = description,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitButton(
                text = actionText,
                onClick = onConfigure,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = enabled,
            )
        }
    }
}
