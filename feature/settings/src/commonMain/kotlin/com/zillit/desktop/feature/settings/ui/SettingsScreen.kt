package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.strings.AppLanguage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.Strings
import com.zillit.desktop.core.strings.str

/**
 * Profile Settings — the reader's own preferences.
 *
 * One scrolling column of sections rather than a sidebar of categories: there
 * are three sections, and a navigation pane for three destinations is furniture.
 * It grows into one when there is something to navigate.
 *
 * Holds the web's profile tab (account, recovery email, invite, leave, App
 * Preferences) and the desktop's own additions to it: appearance, the desktop
 * widgets and the build.
 *
 * @param showTitle false under [SettingsTabsFrame], which carries the title.
 * @param preferences the web's App Preferences cards (Default Home Unit,
 *   Notifications, Location), when a production is open to hold them.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    preferences: (@Composable () -> Unit)? = null,
) {
    // Held here, not beside the rows: the dialog is no popup, so it has to be
    // composed at the page root or it draws inline inside the scrolling column.
    var info by remember { mutableStateOf<SettingsEntry?>(null) }

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                if (showTitle) Row(
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
                        ZillitText(text = str(S.settings), style = ZillitTheme.typography.displayLarge)
                        ZillitText(
                            text = str(S.desktop_settings_page_subtitle),
                            style = ZillitTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }

                AccountCard(state, onEvent)
                Destinations(state, onEvent, atEnd = false) { info = it }
                preferences?.let { cards -> AppPreferencesGroup(cards) }
                AppearanceSection(state, onEvent)
                DesktopWidgetsSection(state, onEvent)
                Destinations(state, onEvent, atEnd = true) { info = it }
            }
        }

        // Composed always so the exit can play; the flag drives visibility.
        SignOutDialog(visible = state.isConfirmingSignOut, unsent = state.unsentChanges, onEvent = onEvent)

        EntryInfoDialog(
            entry = info,
            projectType = state.admin.production.projectType,
            forAdmin = state.account.isAdmin,
            onOpenUrl = { onEvent(SettingsEvent.OpenLink(it)) },
            onDismiss = { info = null },
        )
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
        account.email.ifBlank { str(S.desktop_signed_in_on_this_device) }
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
        ZillitAvatar(name = displayName, userId = account.userId, size = ACCOUNT_AVATAR)
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
                if (account.isAdmin) ZillitTag(str(S.admin), tone = TagTone.Accent)
            }
            val detail = listOfNotNull(
                account.email.takeIf { it.isNotBlank() && it != displayName },
                account.productionName.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
                .ifBlank { str(S.desktop_sign_out_removes_local_data) }
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitButton(
            text = str(S.logout),
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
 * reading, and it is the Admin Settings tab now.
 */
@Composable
private fun Destinations(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    /** The groups that sit at the foot of the page, or the ones at its head. */
    atEnd: Boolean,
    onInfo: (SettingsEntry) -> Unit,
) {
    val isAdmin = state.account.isAdmin
    val production = state.admin.production
    val groups = remember(isAdmin, atEnd) { settingsEntries(isAdmin).filter { it.atEnd == atEnd } }
    groups.forEach { group ->
        SettingsEntryGroup(
            group = group,
            onOpen = { onEvent(SettingsEvent.OpenEntry(it)) },
            // The web draws no ⓘ on a personal production.
            onInfo = onInfo.takeUnless { production.isPersonal },
            // The version heads the foot-of-page group: it is what support
            // asks for, and it sits with Help.
            leading = if (group.atEnd) {
                { VersionRow(state.about, onEvent) }
            } else {
                null
            },
        )
    }
}

/**
 * The web's App Preferences, one card like the sections below it: settings the
 * server keeps for this person on every device, unlike Appearance and the
 * widgets, which are this computer's.
 */
@Composable
private fun AppPreferencesGroup(rows: @Composable () -> Unit) {
    Section(str(S.app_preferences), ZillitIcons.Settings) { rows() }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    Section(str(S.dd_watermark_appearance), ZillitIcons.Sun) {
        SettingRow(
            title = str(S.theme_mode),
            detail = str(S.desktop_theme_detail),
            icon = ZillitIcons.Moon,
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

        LanguageRow(state.language) { onEvent(SettingsEvent.LanguageChanged(it)) }

        SettingRow(
            title = str(S.desktop_interface_size),
            // Says what it affects. "Scale" alone reads as a display setting
            // and people expect it to change their monitor resolution.
            detail = str(S.desktop_interface_size_detail),
            icon = ZillitIcons.Expand,
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
                        text = str(S.reset),
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
 * The desktop widgets — small always-on-top windows onto Drive and Chat — each
 * on or off. The tray offers the same switches; both write one preference.
 */
@Composable
private fun DesktopWidgetsSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    if (state.widgets.isEmpty()) return
    Section(str(S.desktop_desktop_widgets), ZillitIcons.Monitor) {
        state.widgets.forEach { widget ->
            NotifyToggle(
                title = widget.label,
                detail = widget.detail,
                icon = widgetIcon(widget.id),
                on = widget.on,
                onChange = { onEvent(SettingsEvent.WidgetChanged(widget.id, it)) },
            )
        }
    }
}

/** Each widget's glyph, by its `ZillitWidget` name. */
private fun widgetIcon(id: String): ImageVector = when (id) {
    "Drive" -> ZillitIcons.Drive
    "Chat" -> ZillitIcons.Chat
    else -> ZillitIcons.Monitor
}

/**
 * Which build this is, and a way to ask whether it is the newest — the first
 * row of "This project", beside Help: nobody opens Settings for it until
 * something is wrong, and then it is the first thing support asks for. The
 * version line is a single string on purpose — it gets copied into a message.
 *
 * The check's answer is written under the row rather than shown as a toast:
 * a toast is gone before the reader has decided whether to click Download,
 * and "you are up to date" is worth leaving on screen.
 */
@Composable
private fun VersionRow(about: AboutInfo, onEvent: (SettingsEvent) -> Unit) {
    val check = about.updateCheck
    val answered = check != UpdateCheck.Idle && check != UpdateCheck.Checking
    SettingRow(
        title = str(S.desktop_zillit_desktop_version, about.version).trimEnd(),
        detail = listOfNotNull(
            about.build.takeIf { it.isNotBlank() }?.let { str(S.desktop_build_number, it) },
            about.platform.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        icon = ZillitIcons.Monitor,
        body = if (answered) {
            { UpdateCheckResult(check, onEvent) }
        } else {
            null
        },
    ) {
        ZillitButton(
            text = str(S.desktop_check_for_updates),
            onClick = { onEvent(SettingsEvent.CheckForUpdates) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            loading = check == UpdateCheck.Checking,
        )
    }
}

/** The line under the version row once a check has answered. */
@Composable
private fun UpdateCheckResult(check: UpdateCheck, onEvent: (SettingsEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UPDATE_RESULT_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        val (text, color) = when (check) {
            UpdateCheck.UpToDate -> str(S.desktop_update_up_to_date) to colors.textMuted
            is UpdateCheck.Available -> if (check.mandatory) {
                str(S.desktop_update_required_stop_working, check.version) to colors.danger
            } else {
                str(S.desktop_update_available, check.version) to colors.info
            }
            UpdateCheck.Unavailable ->
                str(S.desktop_update_check_failed) to colors.textMuted
            UpdateCheck.Idle, UpdateCheck.Checking -> "" to colors.textMuted
        }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
        (check as? UpdateCheck.Available)?.downloadUrl?.let { url ->
            ZillitButton(
                text = str(S.download),
                onClick = { onEvent(SettingsEvent.DownloadUpdate(url)) },
                variant = if (check.mandatory) ButtonVariant.Danger else ButtonVariant.Primary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
            )
        }
    }
}

/**
 * One on/off preference, as a switch.
 *
 * It was a checkbox with its state spelled beside it — "on" in Android's
 * lower case, "Off" in ours — so a column of them read as a ticked list
 * with a word of noise after each. A switch says "this is on" by itself.
 */
@Composable
private fun NotifyToggle(
    title: String,
    detail: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    SettingRow(title = title, detail = detail, icon = icon) {
        ZillitSwitch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun SignOutDialog(visible: Boolean, unsent: Int, onEvent: (SettingsEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.logout),
        subtitle = str(S.desktop_sign_out_subtitle),
        icon = ZillitIcons.User,
        visible = visible,
        onDismiss = { onEvent(SettingsEvent.DismissSignOut) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = str(S.desktop_sign_out_dialog_body),
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
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(SettingsEvent.DismissSignOut) },
            )
            ZillitButton(
                text = str(S.logout),
                variant = ButtonVariant.Danger,
                onClick = { onEvent(SettingsEvent.ConfirmSignOut) },
            )
        }
    }
}

/**
 * A titled group of rows, the title carrying the section's icon.
 *
 * The rows are divided by a hairline, inset to the text, as the destination
 * rows above already are: without one a card of seven switches read as one
 * block of prose with the controls hanging off its edge. Each row draws its
 * own, at its top (see [SettingRow]).
 */
@Composable
private fun Section(
    title: String,
    icon: ImageVector,
    /**
     * Lets the group fold behind its heading. Starts open, like every other
     * group; for the long ones — Desktop and Notifications — which otherwise
     * push everything below them off the screen. Remembered for as long as
     * the screen is.
     */
    collapsible: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            // The whole heading is the target, not just the chevron: a 16dp icon
            // is a hard thing to hit, and the heading is what people aim at.
            modifier = if (collapsible) {
                Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) str(S.desktop_collapse) else str(S.desktop_expand),
                    ) { expanded = !expanded }
                    .testTag(SECTION_HEADING_TAG)
            } else {
                Modifier
            },
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
            if (collapsible) {
                Spacer(Modifier.weight(1f))
                ZillitIcon(
                    icon = if (expanded) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textMuted,
                    size = SECTION_ICON,
                )
            }
        }
        if (expanded) {
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
}

/** Marks a foldable section's heading, for tests. */
internal const val SECTION_HEADING_TAG = "settings-section-heading"

/**
 * One setting: what it is, what it does, and the control.
 *
 * The explanation is not optional. A row reading "Interface size" with two
 * buttons is a guess; one that says what it changes is a decision.
 */
@Composable
internal fun SettingRow(
    title: String,
    detail: String,
    /** The row's glyph on its tinted disc, as the destination rows wear one. */
    icon: ImageVector? = null,
    /** What sits under the row, aligned to its text — the location schedule. */
    body: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val divider = ZillitTheme.colors.border
    val inset = ZillitTheme.spacing.md
    val textInset = if (icon != null) SETTINGS_TEXT_INSET else inset
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A hairline above every row, inset to the text. The first row's
            // lands on the card's own top border, in the same colour, and
            // vanishes into it — so no row needs to know it is the first.
            .drawBehind {
                drawLine(divider, Offset(textInset.toPx(), 0f), Offset(size.width - inset.toPx(), 0f), HAIRLINE.toPx())
            }
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            icon?.let { SettingsIconDisc(it, avatarHue(title)) }
            SettingRowText(title, detail, trailing)
        }
        body?.let { content ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = textInset - inset),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                content = content,
            )
        }
    }
}

@Composable
private fun RowScope.SettingRowText(title: String, detail: String, trailing: @Composable () -> Unit) {
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
    // Capped, so no control can take the row from its words. The language
    // select had no width of its own and filled the row, leaving
    // "Language" and its explanation one letter wide, a page tall.
    Box(modifier = Modifier.widthIn(max = TRAILING_MAX_WIDTH), contentAlignment = Alignment.CenterEnd) {
        trailing()
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.Light -> str(S.theme_light)
        ThemeMode.Dark -> str(S.theme_dark)
        ThemeMode.System -> str(S.desktop_language_system_short)
    }

/**
 * The language, as a select rather than the bar's globe menu: a settings row
 * shows its current value in words, and a globe says nothing about which
 * language is on. "System" is a real option at the top, as in the bar.
 */
@Composable
private fun LanguageRow(selected: String, onSelect: (String) -> Unit) {
    SettingRow(
        title = str(S.desktop_language),
        detail = str(S.desktop_language_detail),
        icon = ZillitIcons.Globe,
    ) {
        LanguageSelect(selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun LanguageSelect(selected: String, onSelect: (String) -> Unit) {
    val options = remember { listOf("") + AppLanguage.all.map { it.code } }
    // A stored code we no longer ship reads as "System" rather than as a
    // blank field; the store falls back the same way.
    val value = selected.takeIf { code -> AppLanguage.all.any { it.code == code } }.orEmpty()
    ZillitSelect(
        value = value,
        options = options,
        onSelect = onSelect,
        // Fixed, like the unit select below: a select sized by its content
        // fills the row and crushes the title column.
        modifier = Modifier.width(LANGUAGE_SELECT_WIDTH),
        label = { code ->
            if (code.isBlank()) {
                str(S.desktop_language_system_short) + " · " + Strings.language.nativeName
            } else {
                AppLanguage.byCode(code)?.let { "${it.nativeName} · ${it.englishName}" } ?: code
            }
        },
    )
}

private val DIALOG_WIDTH = 420.dp
private val HAIRLINE = 1.dp
private val CONTENT_MAX_WIDTH = 780.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val ACCOUNT_AVATAR = 48.dp
private val SECTION_ICON = 14.dp
private val LANGUAGE_SELECT_WIDTH = 240.dp
private val TRAILING_MAX_WIDTH = 320.dp

/** The version row's answer line — so a test can read what the check said. */
internal const val UPDATE_RESULT_TAG = "settings-update-result"

/** The sentence the sign-out question adds when unsent work would be lost. */
internal fun unsentChangesWarning(count: Int): String =
    if (count == 1) str(S.desktop_unsent_changes_warning_one) else str(S.desktop_unsent_changes_warning_other, count)
