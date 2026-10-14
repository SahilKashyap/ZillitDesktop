// The sidebar and the row it is built from; one file, as the web keeps them.
@file:Suppress("MatchingDeclarationName")

package com.zillit.desktop.feature.transporthub.ui

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** One destination in the hub's rail. */
internal data class HubRailRow(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val badge: Int = 0,
    val active: Boolean = false,
)

/**
 * The hub's sidebar — the web's own (`transportationHub/common/Layout.jsx`).
 *
 * Four pieces, in the web's order: a way back to Film Tools, the title with
 * the TEMP DRIVER tag under it when the viewer holds that flag, the role's
 * destinations with a badge where unread work sits, and the viewer's own
 * profile card at the bottom.
 *
 * It collapses to an icon strip, as the web's does, and every row keeps a
 * tooltip while collapsed — the only thing that tells an icon apart then. The
 * generic [com.zillit.desktop.core.designsystem.component.ZillitSideNav] does
 * neither, and the hub is the one tool whose sidebar the web gives a profile
 * block and a collapse handle.
 */
@Composable
internal fun HubRail(
    rows: List<HubRailRow>,
    onSelect: (String) -> Unit,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    isTempDriver: Boolean,
    profileName: String,
    profileDesignation: String,
    profileUserId: String,
    profileIsAdmin: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val width by animateDpAsState(if (collapsed) COLLAPSED_WIDTH else EXPANDED_WIDTH)
    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .background(colors.surfaceSunken),
        ) {
            RailHeader(
                collapsed = collapsed,
                isTempDriver = isTempDriver,
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .zillitVerticalScroll()
                    .padding(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                rows.forEach { row ->
                    RailRow(row = row, collapsed = collapsed, onClick = { onSelect(row.id) })
                }
            }
            RailProfile(
                collapsed = collapsed,
                name = profileName,
                designation = profileDesignation,
                userId = profileUserId,
                isAdmin = profileIsAdmin,
            )
        }
        // The handle sits on the rail's own edge, as the web's circular
        // button does, so it is reachable at either width.
        Column(
            modifier = Modifier.fillMaxHeight().padding(top = ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZillitTooltip(
                text = if (collapsed) {
                    str(S.desktop_hub_expand_menu)
                } else {
                    str(S.desktop_hub_collapse_menu)
                },
            ) {
                ZillitIconButton(
                    icon = if (collapsed) ZillitIcons.ChevronRight else ZillitIcons.ChevronLeft,
                    contentDescription = if (collapsed) {
                        str(S.desktop_hub_expand_menu)
                    } else {
                        str(S.desktop_hub_collapse_menu)
                    },
                    onClick = onToggleCollapse,
                )
            }
        }
    }
}

@Composable
private fun RailHeader(collapsed: Boolean, isTempDriver: Boolean, onBack: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        onBack?.let { back ->
            if (collapsed) {
                ZillitTooltip(text = str(S.desktop_hub_back_to_film_tools)) {
                    ZillitIconButton(
                        icon = ZillitIcons.ArrowLeft,
                        contentDescription = str(S.desktop_hub_back_to_film_tools),
                        onClick = back,
                    )
                }
            } else {
                ZillitButton(
                    text = str(S.desktop_film_tools),
                    onClick = back,
                    leadingIcon = ZillitIcons.ArrowLeft,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!collapsed) {
            ZillitText(
                text = str(S.desktop_hub_title),
                style = ZillitTheme.typography.titleMedium,
            )
            // The web puts this tag under the title for anyone a coordinator
            // has made a temporary driver: it is the only thing on screen
            // that explains why the driver rows appeared in their sidebar.
            if (isTempDriver) {
                ZillitTooltip(text = str(S.desktop_hub_temp_driver_hint)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                    ) {
                        Box(
                            Modifier
                                .size(TEMP_DOT)
                                .clip(ZillitTheme.shapes.pill)
                                .background(colors.accent),
                        )
                        ZillitText(
                            text = str(S.desktop_hub_temp_driver),
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.accentText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailRow(row: HubRailRow, collapsed: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        row.active -> colors.railActive
        hovered -> colors.surfaceHover
        else -> Color.Transparent
    }
    val content = if (row.active) colors.accentText else colors.textSecondary
    val body = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) {
                Arrangement.Center
            } else {
                Arrangement.spacedBy(ZillitTheme.spacing.sm)
            },
        ) {
            ZillitIcon(row.icon, tint = content, size = ZillitDimens.iconSmall)
            if (!collapsed) {
                ZillitText(
                    text = row.label,
                    style = ZillitTheme.typography.bodyMedium.copy(
                        fontWeight = if (row.active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (row.active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.badge > 0) ZillitBadge(count = row.badge)
        }
    }
    // Collapsed, the icon is all there is — so the label becomes the tooltip,
    // with the count in it, exactly as the web's collapsed menu does.
    if (collapsed) {
        val label = if (row.badge > 0) "${row.label} (${row.badge})" else row.label
        ZillitTooltip(text = label) { body() }
    } else {
        body()
    }
}

@Composable
private fun RailProfile(
    collapsed: Boolean,
    name: String,
    designation: String,
    userId: String,
    isAdmin: Boolean,
) {
    if (name.isBlank()) return
    val colors = ZillitTheme.colors
    // The designation arrives as the untranslated label key the crew list
    // stores (`designation_driver_transport`), so it goes through the same
    // resolver the rest of the app reads a designation with.
    val designationLabel = designation.localised()
    val subtitle = listOfNotNull(
        designationLabel.takeIf { it.isNotBlank() },
        str(S.admin).takeIf { isAdmin },
    ).joinToString(" · ")
    if (collapsed) {
        Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm), Alignment.Center) {
            ZillitTooltip(text = listOf(name, subtitle).filter { it.isNotBlank() }.joinToString(" · ")) {
                ZillitAvatar(name = name, userId = userId, size = AVATAR_COLLAPSED)
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, userId = userId, size = AVATAR_EXPANDED)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                ZillitText(
                    text = subtitle,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val EXPANDED_WIDTH = 208.dp
private val COLLAPSED_WIDTH = 72.dp
private val AVATAR_EXPANDED = 36.dp
private val AVATAR_COLLAPSED = 32.dp
private val TEMP_DOT = 6.dp
