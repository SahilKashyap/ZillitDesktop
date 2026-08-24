package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.localization.localised

/**
 * Application settings.
 *
 * One scrolling column of sections rather than a sidebar of categories: there
 * are three sections, and a navigation pane for three destinations is furniture.
 * It grows into one when there is something to navigate.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .zillitVerticalScroll()
                .padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    Box(
                        Modifier
                            .width(TITLE_ACCENT_WIDTH)
                            .height(TITLE_ACCENT_HEIGHT)
                            .clip(ZillitTheme.shapes.pill)
                            .background(ZillitTheme.colors.accent),
                    )
                    Column {
                        ZillitText(text = "Settings", style = ZillitTheme.typography.displayLarge)
                        ZillitText(
                            text = "How this computer runs Zillit.",
                            style = ZillitTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }

                AccountCard(state, onEvent)
                Destinations(onEvent)
                AppearanceSection(state, onEvent)
                ProductionSection(state, onEvent)
                NotificationsSection(state, onEvent)
            }
        }

        // Composed always so the exit can play; the flag drives visibility.
        SignOutDialog(visible = state.isConfirmingSignOut, unsent = state.unsentChanges, onEvent = onEvent)
    }
}

/**
 * Who is signed in, before anything about how — identity first, the way every
 * modern settings page opens. Sign out lives with the identity it ends.
 */
@Composable
private fun AccountCard(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val account = state.account
    // Sign out must survive a profile that failed to load — the card shows
    // what it knows, even when that is only "someone is signed in here".
    val displayName = account.fullName.ifBlank {
        account.email.ifBlank { "Signed in on this device" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = displayName, size = ACCOUNT_AVATAR)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = displayName,
                    style = ZillitTheme.typography.titleSmall,
                )
                if (account.isAdmin) ZillitTag("Admin", tone = TagTone.Accent)
            }
            val detail = listOfNotNull(
                account.email.takeIf { it.isNotBlank() && it != displayName },
                account.productionName.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
                .ifBlank { "Signing out removes the mail and production data stored here." }
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitButton(
            text = "Sign out",
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            onClick = { onEvent(SettingsEvent.AskSignOut) },
        )
    }
}

/**
 * Where this page can send you, above the switches it applies itself.
 *
 * Placed under the account card rather than at the foot of the page: these are
 * the rows someone opens Settings *for*, and the theme switch is not worth
 * scrolling past them to reach. The phone clients make the same call, and put
 * the whole page in this order.
 *
 * Takes no state: administration was the only part that varied by who was
 * reading, and it has its own rail destination now.
 */
@Composable
private fun Destinations(onEvent: (SettingsEvent) -> Unit) {
    val groups = remember { settingsEntries() }
    groups.forEach { group ->
        SettingsEntryGroup(
            group = group,
            onOpen = { onEvent(SettingsEvent.OpenEntry(it)) },
        )
    }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    Section("Appearance", ZillitIcons.Sun) {
        SettingRow(
            title = "Theme",
            detail = "Follows the system unless you choose otherwise.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ThemeMode.entries.forEach { mode ->
                    ZillitChoiceChip(
                        label = mode.label,
                        selected = mode == state.themeMode,
                        onClick = { onEvent(SettingsEvent.ThemeChanged(mode)) },
                    )
                }
            }
        }

        SettingRow(
            title = "Interface size",
            // Says what it affects. "Scale" alone reads as a display setting
            // and people expect it to change their monitor resolution.
            detail = "Makes text and controls throughout the app larger or smaller. " +
                "Takes effect immediately.",
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitButton(
                    text = "−",
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = state.canDecreaseScale,
                    onClick = { onEvent(SettingsEvent.ScaleChanged(-SettingsUiState.SCALE_STEP)) },
                )
                ZillitText(
                    text = "${state.uiScalePercent}%",
                    style = ZillitTheme.typography.bodyMedium,
                )
                ZillitButton(
                    text = "+",
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = state.canIncreaseScale,
                    onClick = { onEvent(SettingsEvent.ScaleChanged(SettingsUiState.SCALE_STEP)) },
                )
                if (state.uiScalePercent != SettingsUiState.DEFAULT_SCALE) {
                    ZillitButton(
                        text = "Reset",
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        onClick = { onEvent(SettingsEvent.ScaleReset) },
                    )
                }
            }
        }
    }
}

/**
 * The production unit this user is on.
 *
 * Absent entirely when there is nothing to choose — a production with one unit,
 * or none loaded. A dropdown holding a single option is furniture, and an empty
 * one reads as something that failed.
 */
@Composable
private fun ProductionSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val unit = state.unit
    if (!unit.isOfferable) return

    Section("Production", ZillitIcons.Tools) {
        SettingRow(
            title = "Your unit",
            // Says what it changes. "Unit" alone is a word this industry uses
            // for four different things.
            detail = "Which unit's call sheets and notices you receive. " +
                "Change it if you move between units on this production.",
        ) {
            Column(horizontalAlignment = Alignment.End) {
                ZillitSelect(
                    value = unit.selected,
                    options = unit.options,
                    onSelect = { chosen -> chosen?.let { onEvent(SettingsEvent.UnitChanged(it.id)) } },
                    // `unit_name` is a translation key — `main_unit_label`.
                    label = { it?.name?.localised() ?: "Not set" },
                    enabled = unit.canChange,
                    // Fixed, or the select fills the row and crushes the
                    // title column to a letter a line — the calendar toolbar
                    // needed the same leash.
                    modifier = Modifier.width(UNIT_SELECT_WIDTH),
                )
                if (unit.error != null) {
                    ZillitText(
                        text = unit.error,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationsSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    Section("Notifications", ZillitIcons.Calendar) {
        SettingRow(
            title = "Event reminders",
            // Says what silence costs. "Mute notifications" alone leaves the
            // user guessing whether the reminders they set are still recorded.
            detail = "Reminders you set on calendar events appear as desktop " +
                "notifications. Muting stops them arriving; the events keep their reminders.",
        ) {
            ZillitCheckbox(
                checked = !state.muteNotifications,
                onCheckedChange = { onEvent(SettingsEvent.MuteNotificationsChanged(!it)) },
                label = if (state.muteNotifications) "Muted" else "On",
            )
        }

        SettingRow(
            title = "Chat messages",
            detail = "A banner when someone messages you and you are looking " +
                "elsewhere. Never for the conversation already on screen.",
        ) {
            ZillitCheckbox(
                checked = state.notifyMessages,
                onCheckedChange = { onEvent(SettingsEvent.NotifyMessagesChanged(it)) },
                label = if (state.notifyMessages) "On" else "Off",
            )
        }

        SettingRow(
            title = "Updates",
            detail = "A banner when someone posts to a notice board you can see. " +
                "Your own posts never notify you.",
        ) {
            ZillitCheckbox(
                checked = state.notifyUpdates,
                onCheckedChange = { onEvent(SettingsEvent.NotifyUpdatesChanged(it)) },
                label = if (state.notifyUpdates) "On" else "Off",
            )
        }

        SettingRow(
            title = "Email",
            detail = "A banner when mail lands in your inbox. Moving or " +
                "deleting mail elsewhere does not count as arriving.",
        ) {
            ZillitCheckbox(
                checked = state.notifyMail,
                onCheckedChange = { onEvent(SettingsEvent.NotifyMailChanged(it)) },
                label = if (state.notifyMail) "On" else "Off",
            )
        }

        SettingRow(
            title = "Production activity",
            detail = "A banner for everything else the production did — a " +
                "purchase order approved, a document shared, an SOS raised. " +
                "The bell list's rows, as they happen.",
        ) {
            ZillitCheckbox(
                checked = state.notifyActivity,
                onCheckedChange = { onEvent(SettingsEvent.NotifyActivityChanged(it)) },
                label = if (state.notifyActivity) "On" else "Off",
            )
        }

        SettingRow(
            title = "Calls",
            // Says plainly that this one outranks the mute above, because a
            // missed call is the one notification with someone waiting on it.
            detail = "A banner when a call rings this device. Muting everything " +
                "above does not silence calls — this switch does.",
        ) {
            ZillitCheckbox(
                checked = state.notifyCalls,
                onCheckedChange = { onEvent(SettingsEvent.NotifyCallsChanged(it)) },
                label = if (state.notifyCalls) "On" else "Off",
            )
        }
    }
}

@Composable
private fun SignOutDialog(visible: Boolean, unsent: Int, onEvent: (SettingsEvent) -> Unit) {
    ZillitDialogShell(
        title = "Sign out?",
        subtitle = "This computer forgets; the server does not.",
        icon = ZillitIcons.User,
        visible = visible,
        onDismiss = { onEvent(SettingsEvent.DismissSignOut) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = "Mail and production data stored on this computer will be removed. " +
                "Nothing on the server is affected.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        if (unsent > 0) {
            // The one thing sign-out does destroy for good: work the server has
            // not seen. Said in its own paragraph, in the danger colour.
            ZillitText(
                text = unsentChangesWarning(unsent),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.danger,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(SettingsEvent.DismissSignOut) },
            )
            ZillitButton(
                text = "Sign out",
                variant = ButtonVariant.Danger,
                onClick = { onEvent(SettingsEvent.ConfirmSignOut) },
            )
        }
    }
}

/** A titled group of rows, the title carrying the section's icon. */
@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = icon,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = SECTION_ICON,
            )
            ZillitText(
                text = title,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.surface)
                .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
        ) {
            content()
        }
    }
}

/**
 * One setting: what it is, what it does, and the control.
 *
 * The explanation is not optional. A row reading "Interface size" with two
 * buttons is a guess; one that says what it changes is a decision.
 */
@Composable
private fun SettingRow(
    title: String,
    detail: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(text = title, style = ZillitTheme.typography.bodyMedium)
            if (detail.isNotBlank()) {
                ZillitText(
                    text = detail,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        trailing()
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
        ThemeMode.System -> "System"
    }

private val DIALOG_WIDTH = 420.dp
private val HAIRLINE = 1.dp
private val CONTENT_MAX_WIDTH = 720.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val ACCOUNT_AVATAR = 48.dp
private val SECTION_ICON = 14.dp
private val UNIT_SELECT_WIDTH = 240.dp

/** The sentence the sign-out question adds when unsent work would be lost. */
internal fun unsentChangesWarning(count: Int): String {
    val what = if (count == 1) "1 change made offline that has" else "$count changes made offline that have"
    return "You have $what not reached the server yet. Signing out deletes them — " +
        "wait for \"Pending changes\" in the status bar to clear first."
}
