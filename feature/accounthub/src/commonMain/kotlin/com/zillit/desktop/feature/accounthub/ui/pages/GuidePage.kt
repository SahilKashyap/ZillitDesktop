package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVideoView
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.HubGuides
import com.zillit.desktop.feature.accounthub.domain.HubItem
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.HubPageHeader
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.iconFor

/**
 * Setup → Guide — the web's `GuideModule`.
 *
 * One card per sidebar module that has a tutorial video or a section on
 * documentation.zillit.com, grouped under the sidebar's own headings. A card
 * offers **Watch Video** (played here, as the Tools grid's player is) and
 * **Read More** (that section in the browser, the view model carrying the
 * production's `project_type` on the link) for whichever the module has. The
 * page reads no data, so it holds no loading or error state.
 */
@Composable
fun GuidePage(
    @Suppress("UNUSED_PARAMETER") state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    var video by remember { mutableStateOf<String?>(null) }
    HubPage {
        HubPageHeader(
            eyebrow = str(S.desktop_setup),
            title = str(S.guide),
            description = str(S.desktop_hub_guide_description),
        )
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
        ) {
            HubGuides.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    MonoLabel(section.title)
                    GuideGrid(
                        items = section.items,
                        onWatch = { item -> video = HubGuides.guide(item.id)?.video },
                        onRead = { item -> onEvent(AccountHubEvent.OpenGuide(item.id)) },
                    )
                }
            }
        }
    }
    VideoDialog(video, onOpenOutside = { onEvent(AccountHubEvent.OpenLink(it)) }) { video = null }
}

/**
 * The web's auto-fill grid (`minmax(min(240px, 100%), 1fr)`), by the width the
 * page actually has — the sidebar takes a share of the window, so the window's
 * own width would pick too many columns, and a card's two buttons need room.
 */
@Composable
private fun GuideGrid(items: List<HubItem>, onWatch: (HubItem) -> Unit, onRead: (HubItem) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = ZillitTheme.spacing.lg
        val columns = ((maxWidth + gap) / (CARD_MIN_WIDTH + gap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item ->
                        GuideCard(item, { onWatch(item) }, { onRead(item) }, Modifier.weight(1f))
                    }
                    // A short last row keeps its cards the width of the rest.
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideCard(item: HubItem, onWatch: () -> Unit, onRead: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val guide = HubGuides.guide(item.id) ?: return
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(ICON_BLOCK)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.accentSoft)
                    .border(1.dp, colors.accent.copy(alpha = ICON_RULE_ALPHA), ZillitTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = iconFor(item), tint = colors.accentText, size = 20.dp)
            }
            ZillitText(
                text = item.label,
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (guide.video != null) {
                ZillitButton(
                    text = str(S.watch_video),
                    onClick = onWatch,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Play,
                )
            }
            if (guide.docs != null) {
                ZillitButton(
                    text = titleCased(str(S.read_more_txt)),
                    onClick = onRead,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.File,
                )
            }
        }
    }
}

/** The clip, played in the app — the web's `ShowVideoForTools`; the browser is the way out when it cannot. */
@Composable
private fun VideoDialog(url: String?, onOpenOutside: (String) -> Unit, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = str(S.watch_video),
        icon = ZillitIcons.Play,
        visible = url != null,
        onDismiss = onDismiss,
        width = VIDEO_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ok), onClick = onDismiss)
        },
    ) {
        ZillitVideoView(
            url = url,
            failed = false,
            onOpenOutside = url?.let { shown -> { onOpenOutside(shown) } },
            modifier = Modifier.fillMaxWidth().height(VIDEO_HEIGHT),
        )
    }
}

/** The web's `capitalize` on the button: "Read more" reads "Read More". */
private fun titleCased(text: String): String =
    text.trim().split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private val ICON_BLOCK = 40.dp
private const val ICON_RULE_ALPHA = 0.2f
private val CARD_MIN_WIDTH = 240.dp
private val VIDEO_WIDTH = 900.dp
private val VIDEO_HEIGHT = 500.dp
