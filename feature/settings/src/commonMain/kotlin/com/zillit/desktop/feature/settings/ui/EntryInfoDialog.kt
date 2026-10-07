package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVideoView
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The web's `text-blue-500`, the colour of its ⓘ and of the More link. */
internal val INFO_BLUE = Color(0xFF3B82F6)

/**
 * The ⓘ's answer: what the row is for, with the web's **More** (its page on the
 * documentation site) and **Watch video** (its tutorial clip, played in the
 * app) — `TooltipInfo` and `ShowVideoForTools`.
 *
 * @param forAdmin whether the docs link says `for=admin`, as the web sets it for
 *   an admin.
 */
@Composable
internal fun EntryInfoDialog(
    entry: SettingsEntry?,
    projectType: String,
    forAdmin: Boolean,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var video by remember(entry) { mutableStateOf<String?>(null) }
    val info = entry?.info
    ZillitDialogShell(
        title = entry?.title.orEmpty(),
        visible = entry != null && video == null,
        onDismiss = onDismiss,
        width = INFO_WIDTH,
        actions = {
            info?.let { shown ->
                ZillitButton(
                    text = str(S.desktop_ft_watch_video),
                    onClick = { video = shown.guide.videoUrl },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Play,
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ok), onClick = onDismiss)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = info?.message.orEmpty(),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            info?.let { shown ->
                ZillitText(
                    text = str(S.more),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = INFO_BLUE,
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .clickable { onOpenUrl(shown.guide.docsUrl(projectType, forAdmin)) },
                )
            }
        }
    }
    ZillitDialogShell(
        title = str(S.desktop_ft_watch_video),
        icon = ZillitIcons.Play,
        visible = video != null,
        onDismiss = { video = null },
        width = VIDEO_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ok), onClick = { video = null })
        },
    ) {
        ZillitVideoView(
            url = video,
            failed = false,
            onOpenOutside = video?.let { shown -> { onOpenUrl(shown) } },
            modifier = Modifier.fillMaxWidth().height(VIDEO_HEIGHT),
        )
    }
}

private val INFO_WIDTH = 460.dp
private val VIDEO_WIDTH = 900.dp
private val VIDEO_HEIGHT = 500.dp
