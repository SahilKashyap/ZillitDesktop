package com.zillit.desktop.feature.distribution.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.distribution.domain.DistributionColumn
import com.zillit.desktop.feature.distribution.domain.DistributionSection

/**
 * The Distribution List — the web's `DistributionAcessgrid.jsx`: one row per
 * accepted crew member or outsider, one column per Home unit or tool, a
 * checkbox in every cell. "Make selection to distribute content, via email,
 * that is uploaded in various sections of Home & Tools."
 */
@Composable
fun DistributionScreen(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** A profile picture by user id; null draws initials. */
    faces: suspend (String) -> ImageBitmap? = { null },
) {
    val copy = rememberDistributionCopy()

    Column(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        ZillitPageHeader(
            title = copy.title,
            description = str(S.desktop_dist_description),
            modifier = Modifier.padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.md),
            actions = {
                ZillitButton(
                    text = str(S.refresh_text),
                    onClick = { onEvent(DistributionEvent.Refresh) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    enabled = !state.isLoading,
                )
            },
        )

        if (state.viewer.isBlocked) {
            ZillitEmptyState(
                title = str(S.dd_publish_no_access_badge),
                message = str(S.desktop_dist_no_viewing_rights),
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        Controls(state, copy, onEvent)
        NoteBanner(copy, onEvent)
        if (state.viewer.isAdmin) ListingOrderBanner(copy, onEvent)

        Box(Modifier.weight(1f)) { Body(state, copy, faces, onEvent) }
    }
}

/** Which face the middle of the page wears — load, error, empty, matrix. */
@Composable
private fun Body(
    state: DistributionUiState,
    copy: DistributionCopy,
    faces: suspend (String) -> ImageBitmap?,
    onEvent: (DistributionEvent) -> Unit,
) {
    when {
        state.isLoading && state.users.isEmpty() -> Centred {
            ZillitSpinner()
            ZillitText(
                text = str(S.desktop_dist_loading),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }

        state.error != null && state.users.isEmpty() -> ZillitErrorState(
            message = state.error,
            onRetry = { onEvent(DistributionEvent.Refresh) },
        )

        state.users.isEmpty() -> ZillitEmptyState(
            title = str(S.desktop_dist_nobody_to_distribute_to),
            message = str(S.desktop_dist_nobody_to_distribute_to_message),
        )

        state.rows.isEmpty() -> ZillitEmptyState(
            title = str(S.dm_nda_no_one_matches),
            message = if (state.query.isBlank()) {
                str(S.desktop_dist_no_external_users_yet)
            } else {
                str(S.desktop_nobody_is_called, state.query.trim())
            },
        )

        else -> DistributionMatrix(state, copy, faces, onEvent)
    }
}

/** The web's control strip: search, Home/Tools, the unit filter, outsiders only. */
@Composable
private fun Controls(
    state: DistributionUiState,
    copy: DistributionCopy,
    onEvent: (DistributionEvent) -> Unit,
) {
    val options = remember(state.users, state.section, copy) { state.allColumns(copy::label) }
    val byId = remember(options) { options.associateBy(DistributionColumn::unitId) }
    val picked = remember(state.unitFilter, byId) { state.unitFilter.mapNotNull(byId::get) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING)
            .padding(bottom = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(DistributionEvent.Search(it)) },
            placeholder = copy.search,
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitSelect(
            value = state.section,
            options = DistributionSection.entries,
            onSelect = { onEvent(DistributionEvent.Section(it)) },
            label = { if (it == DistributionSection.Home) copy.home else copy.tools },
            modifier = Modifier.width(SECTION_WIDTH),
        )
        ZillitMultiSelect(
            selected = picked,
            options = options,
            label = { copy.label(it.unitName) },
            onChange = { onEvent(DistributionEvent.UnitFilter(it.map(DistributionColumn::unitId))) },
            placeholder = copy.filterUnits,
            emptyText = if (state.section == DistributionSection.Home) {
                str(S.desktop_dist_no_units_to_filter)
            } else {
                str(S.desktop_dist_no_tools_to_filter)
            },
            modifier = Modifier.widthIn(min = FILTER_MIN_WIDTH, max = FILTER_MAX_WIDTH),
        )
        ZillitCheckbox(
            checked = state.externalOnly,
            onCheckedChange = { onEvent(DistributionEvent.ExternalOnly(it)) },
            label = copy.filterExternal,
        )
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The gold NOTE the web pins above the grid (ZL-12581), with the three
 * "MUST READ" links into the documentation site's essential notes.
 */
@Composable
private fun NoteBanner(copy: DistributionCopy, onEvent: (DistributionEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING)
            .padding(bottom = ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.large)
            .background(colors.goldSoft)
            .border(HAIRLINE, colors.gold.copy(alpha = BANNER_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Info, tint = colors.gold, modifier = Modifier.padding(top = 1.dp))
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(verticalAlignment = Alignment.Top) {
                ZillitText(
                    text = "${copy.note.uppercase()}: ",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                ZillitText(
                    text = copy.noteBody,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textPrimary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                ESSENTIAL_NOTES.forEach { number ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(
                            text = "${copy.essentialNote} $number: ",
                            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                        )
                        LinkText(copy.mustRead) { onEvent(DistributionEvent.OpenEssentialNote(number)) }
                    }
                }
            }
        }
    }
}

/**
 * The admin-only reminder that the rows' order is the department listing
 * order (ZL-16934), with the web's "Click Here" into the editor (ZL-17040).
 */
@Composable
private fun ListingOrderBanner(copy: DistributionCopy, onEvent: (DistributionEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING)
            .padding(bottom = ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.large)
            .background(colors.infoSoft)
            .border(HAIRLINE, colors.info.copy(alpha = BANNER_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Info, tint = colors.info)
        ZillitText(
            text = "${copy.listingOrderHint} or",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textPrimary,
        )
        LinkText(copy.clickHere) { onEvent(DistributionEvent.OpenListingOrder) }
    }
}

/** The web's red underlined anchor, as text that behaves like one. */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = ZillitTheme.colors
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        ),
        color = if (hovered) colors.danger.copy(alpha = LINK_HOVER_ALPHA) else colors.danger,
        modifier = Modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            content()
        }
    }
}

internal val PAGE_PADDING = 24.dp
internal val HAIRLINE = 1.dp
private val SEARCH_WIDTH = 260.dp
private val SECTION_WIDTH = 140.dp
private val FILTER_MIN_WIDTH = 220.dp
private val FILTER_MAX_WIDTH = 360.dp
private const val BANNER_BORDER_ALPHA = 0.35f
private const val LINK_HOVER_ALPHA = 0.75f
private val ESSENTIAL_NOTES = listOf(1, 2, 3)
