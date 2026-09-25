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
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import androidx.compose.foundation.layout.Spacer
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
import com.zillit.desktop.core.designsystem.icon.ZillitRailIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
    /** The `S` key of the label — held as a key so a language switch redraws the rail. */
    val labelKey: String,
    val icon: ImageVector,
    val route: WorkspaceRoute,
    val badge: Int = 0,
) {
    /** The label in the current language. Read in composition, it follows the language. */
    val label: String get() = str(labelKey)
}

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
    // Android's bottom bar order (BottomNavigationActivity.kt:555-563,
    // labels AppHelper.kt:134-172): Home, Email, Tools, C&C, Settings.
    // The icons are the web side menu's own SVGs (`ZillitRailIcons`); Email
    // keeps the app's envelope, as the web draws that one from Material too.
    RailItem("home", S.home, ZillitRailIcons.Home, WorkspaceRoute.Home),
    RailItem("email", S.email, ZillitIcons.Mail, WorkspaceRoute.Tool("/email")),
    RailItem("tools", S.desktop_film_tools, ZillitRailIcons.Tools, WorkspaceRoute.Tool("/home/tools")),
    RailItem("cnc", S.desktop_chat_calls, ZillitRailIcons.Cnc, WorkspaceRoute.Tool("/cnc")),
    RailItem("settings", S.settings, ZillitRailIcons.Settings, WorkspaceRoute.Tool("/settings")),
)

/**
 * The two app pages, below the production's sections.
 *
 * They sit in the run rather than at the rail's foot: the foot is for Logout
 * alone, and an entry parked down there on its own reads as an afterthought
 * next to the sign-out it shares a corner with. Pin to Start is gone — a native
 * app is already installed, and the web's page only existed to install one.
 */
val AppRailItems: List<RailItem> = listOf(
    RailItem("sos", S.sos, ZillitRailIcons.Sos, WorkspaceRoute.Tool("/sos")),
    RailItem("help", S.zillit_help, ZillitIcons.Help, WorkspaceRoute.Tool("/settings/help")),
)

/**
 * The rail for this reader.
 *
 * Administration is not an entry of its own: it is the Admin Settings tab of
 * the Settings window, as it is on the web (`SettingsTabs.jsx`), and that tab
 * is offered only to admins. [isAdmin] is kept so the frame's call site reads
 * the same if the rail ever varies by rights again.
 */
@Suppress("UNUSED_PARAMETER")
fun railItemsFor(isAdmin: Boolean): List<RailItem> = DefaultRailItems + AppRailItems

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
    /**
     * Null hides Logout. The click does not sign anyone out — it asks the
     * frame to confirm, and [SignOutDialog] is drawn there (see its note on
     * why it cannot be drawn here).
     */
    onSignOut: (() -> Unit)? = null,
    /**
     * Whether the workspace is in classic full-page view; null hides the
     * switch. As the web's sidebar item, it is named for the view it switches
     * TO — "Classic view" while windowed, "Windowed view" while classic.
     */
    classicView: Boolean? = null,
    onToggleViewMode: () -> Unit = {},
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
        // `/settings/help` also starts with `/settings`, and two lit entries
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
        // Below the spacer: the view switch, then Logout — the web's order.
        // Neither goes anywhere; they change how, or whether, the app runs.
        Box(Modifier.weight(1f))
        classicView?.let { classic ->
            RailButton(
                item = RailItem(
                    id = "view-mode",
                    labelKey = if (classic) S.desktop_windowed_view else S.desktop_classic_view,
                    icon = if (classic) ZillitIcons.LayoutTabs else ZillitIcons.Maximize,
                    route = WorkspaceRoute.Tool("/toggle-view-mode"),
                ),
                isActive = false,
                expanded = expanded,
                onClick = onToggleViewMode,
            )
        }
        onSignOut?.let { requestSignOut ->
            RailButton(
                item = RailItem("logout", S.logout, ZillitIcons.Logout, WorkspaceRoute.Tool("/logout")),
                isActive = false,
                expanded = expanded,
                onClick = requestSignOut,
            )
        }
        ColumnFooterScope.footer()
    }
}

/**
 * The question Logout asks first — the same one Settings asks (and Android:
 * "Logout" / "Are you sure?") — because a stray click at the rail's foot would
 * sign the person out of every production at once.
 *
 * Drawn by the frame, not by the rail. The dialog fills its parent, and the
 * rail is a 60pt column: composed inside it the card came out rail-width, its
 * buttons crushed to a few points high, and the foot items were pushed up the
 * rail to make room for it.
 */
@Composable
fun SignOutDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_sign_out_title),
        subtitle = str(S.desktop_sign_out_body),
        icon = ZillitIcons.Logout,
        visible = visible,
        onDismiss = onDismiss,
        width = SIGN_OUT_DIALOG_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Secondary, onClick = onDismiss)
            ZillitButton(text = str(S.desktop_sign_out), onClick = onConfirm)
        },
    ) {
        ZillitText(
            text = str(S.desktop_sign_out_note),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
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
            // A shaped background rather than `clip` + background: the clip
            // also cut the badge, which rides just past the icon slot's
            // corner when the rail is collapsed.
            .background(background, ZillitTheme.shapes.medium)
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
private val SIGN_OUT_DIALOG_WIDTH = 380.dp

/** Left/right breathing room, so the button does not touch the rail edge. */
private val RAIL_GUTTER = 10.dp

private const val RAIL_ANIMATION_MILLIS = 180

/** Faster out than in: a rail shrinking behind the pointer should not linger. */
private const val RAIL_LABEL_FADE_OUT_MILLIS = 90
