package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
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
 * One card per sidebar module that has a section on documentation.zillit.com,
 * grouped under the sidebar's own headings. A card opens that section in the
 * browser (the view model carries the production's `project_type` on the link).
 * The page reads no data, so it holds no loading or error state.
 */
@Composable
fun GuidePage(
    @Suppress("UNUSED_PARAMETER") state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
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
                    GuideGrid(section.items) { item -> onEvent(AccountHubEvent.OpenGuide(item.id)) }
                }
            }
        }
    }
}

/**
 * The web's `grid-cols-1 sm:grid-cols-2 lg:grid-cols-4`, by the width the page
 * actually has — the sidebar takes a share of the window, so the window's own
 * width would pick too many columns.
 */
@Composable
private fun GuideGrid(items: List<HubItem>, onOpen: (HubItem) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < TWO_COLUMNS_FROM -> 1
            maxWidth < FOUR_COLUMNS_FROM -> 2
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                    row.forEach { item -> GuideCard(item, { onOpen(item) }, Modifier.weight(1f)) }
                    // A short last row keeps its cards the width of the rest.
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GuideCard(item: HubItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, if (hovered) colors.accent else colors.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.lg),
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
        ZillitIcon(
            icon = ZillitIcons.ArrowRight,
            tint = colors.accentText,
            size = 14.dp,
            modifier = Modifier.alpha(if (hovered) 1f else 0f),
        )
    }
}

private val ICON_BLOCK = 40.dp
private const val ICON_RULE_ALPHA = 0.2f
private val TWO_COLUMNS_FROM = 560.dp
private val FOUR_COLUMNS_FROM = 900.dp
