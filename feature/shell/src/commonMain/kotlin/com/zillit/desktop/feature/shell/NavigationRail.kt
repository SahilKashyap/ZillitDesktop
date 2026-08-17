package com.zillit.desktop.feature.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * One entry in the left rail.
 *
 * `route` is what the entry *opens*. Rail items are not screens — they open
 * windows (plan §3.3). That single decision is what makes the app window-native
 * rather than a phone navigation model with tabs bolted on.
 */
data class RailItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val route: WorkspaceRoute,
    val badge: Int = 0,
)

/**
 * The rail: the app's fixed sections.
 *
 * ## Not derived from `project/tools`
 *
 * That endpoint returns the production's **Film Tools** — accounting, catering,
 * forms and so on. It does not mention Home, Chat, Email or Settings, because
 * those are not project tools; they are parts of the application. Deriving the
 * rail from it produced an empty rail, verified against QA.
 *
 * So this is the rail, and permissions gate the **grid** behind Film Tools.
 */
val DefaultRailItems: List<RailItem> = listOf(
    RailItem("home", "Home", ZillitIcons.Home, WorkspaceRoute.Home),
    RailItem("tools", "Film Tools", ZillitIcons.Tools, WorkspaceRoute.Tool("/home/tools")),
    RailItem("cnc", "Chat & Calls", ZillitIcons.Chat, WorkspaceRoute.Tool("/cnc")),
    RailItem("email", "Email", ZillitIcons.Mail, WorkspaceRoute.Tool("/email")),
    RailItem("settings", "Settings", ZillitIcons.Settings, WorkspaceRoute.Tool("/settings")),
)

/**
 * Administration, for this production's coordinators.
 *
 * Out here rather than inside Settings because the two are different jobs:
 * Settings is the reader's own preferences, this changes the production for
 * everybody on it. A coordinator uses it constantly and it was two clicks and a
 * scroll behind a theme switch.
 *
 * Paths are literals here, as they are for every other entry — the rail is
 * `feature:shell` and deliberately does not depend on the feature modules whose
 * windows it opens.
 */
val AdminRailItem: RailItem =
    RailItem("admin", "Admin", ZillitToolIcons.Production, WorkspaceRoute.Tool("/settings/admin"))

/**
 * The rail for this reader.
 *
 * Admin is **absent** for non-admins rather than present and disabled: the rail
 * is the app's statement of what exists, and advertising a room someone may not
 * enter is worse than not mentioning it. Rights change mid-session — they are
 * granted and revoked while people are signed in — so this is read per
 * composition rather than fixed at sign-in.
 */
fun railItemsFor(isAdmin: Boolean): List<RailItem> =
    if (isAdmin) DefaultRailItems + AdminRailItem else DefaultRailItems

/**
 * The left rail, collapsed to icons until the pointer enters it.
 *
 * ## Hover to expand, rather than a pinned toggle
 *
 * The web keeps a click-toggled collapse state in `localStorage`
 * (`SideMenu.jsx`), which means a user who wants a label has to spend a click,
 * change a persistent setting, and spend another click to put it back. On
 * desktop the pointer is already there, so hovering is free — the labels appear
 * when you go looking for them and the rail is narrow the rest of the time.
 *
 * The icon column keeps its exact width and position in both states, so nothing
 * moves under the pointer as the panel grows. The web has an open CSS bug here
 * (icons shift left when collapsed) precisely because its icons live inside the
 * flexible label slot.
 */
@Composable
fun NavigationRail(
    items: List<RailItem>,
    activePath: String?,
    onOpen: (WorkspaceRoute) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnFooterScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val expanded by interaction.collectIsHoveredAsState()

    val width by animateDpAsState(
        targetValue = if (expanded) ZillitDimens.railWidthExpanded else ZillitDimens.railWidth,
        animationSpec = tween(RAIL_ANIMATION_MILLIS),
        label = "railWidth",
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(ZillitTheme.colors.railBackground)
            .hoverable(interaction)
            .padding(vertical = ZillitTheme.spacing.sm),
        // Start, not centre: the icon must sit at the same x in both states, and
        // centring would slide every icon right as the rail grows.
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // Longest prefix wins, rather than every entry the path starts with:
        // `/settings/admin` also starts with `/settings`, and two lit entries
        // tell the reader they are in two places at once.
        val activeId = remember(items, activePath) {
            activePath?.let { path ->
                items.filter { path.startsWith(it.route.path) }
                    .maxByOrNull { it.route.path.length }
                    ?.id
            }
        }

        items.forEach { item ->
            RailButton(
                item = item,
                isActive = item.id == activeId,
                expanded = expanded,
                onClick = { onOpen(item.route) },
            )
        }
        Box(Modifier.weight(1f))
        ColumnFooterScope.footer()
    }
}

/** Marker scope so the rail footer cannot be mistaken for a rail item. */
object ColumnFooterScope

@Composable
private fun RailButton(
    item: RailItem,
    isActive: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        when {
            isActive -> colors.railActive
            hovered -> colors.surfaceHover
            else -> Color.Transparent
        },
        label = "railBackground",
    )
    val contentColor = when {
        isActive -> colors.accent
        hovered -> colors.textPrimary
        else -> colors.textSecondary
    }

    Row(
        modifier = Modifier
            .padding(horizontal = RAIL_GUTTER)
            .fillMaxWidth()
            .height(RAIL_BUTTON)
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = item.label,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed-width slot: the icon is centred in it and never moves, whatever
        // the rail is doing.
        Box(
            modifier = Modifier.width(RAIL_BUTTON).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                size = ZillitDimens.iconLarge,
            )
            // Collapsed, the count rides the icon. Expanded, it moves to the
            // end of the row where it has space to be read.
            if (item.badge > 0 && !expanded) {
                ZillitBadge(
                    count = item.badge,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 2.dp),
                )
            }
        }

        RailLabel(item = item, visible = expanded, contentColor = contentColor)
    }
}

/**
 * The label half of a rail entry.
 *
 * `AnimatedVisibility` rather than an `if`: the label fades in step with the
 * width instead of appearing the instant the animation starts.
 */
@Composable
private fun RailLabel(item: RailItem, visible: Boolean, contentColor: Color) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(RAIL_ANIMATION_MILLIS)),
        exit = fadeOut(tween(RAIL_LABEL_FADE_OUT_MILLIS)),
    ) {
        Row(
            modifier = Modifier.padding(end = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = item.label,
                style = ZillitTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ZillitBadge(count = item.badge)
        }
    }
}

private val RAIL_BUTTON = 40.dp

/** Left/right breathing room, so the button does not touch the rail edge. */
private val RAIL_GUTTER = 10.dp

private const val RAIL_ANIMATION_MILLIS = 180

/** Faster out than in: a rail shrinking behind the pointer should not linger. */
private const val RAIL_LABEL_FADE_OUT_MILLIS = 90
