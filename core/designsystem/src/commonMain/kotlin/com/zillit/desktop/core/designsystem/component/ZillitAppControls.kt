package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Updates, language and theme: the controls that do something to the app
 * itself, in the shell's top bar and on the production list — one group, so
 * the first screen after sign-in and the signed-in frame cannot drift apart.
 *
 * All wear their words: a globe and a monitor side by side were two grey
 * glyphs nobody could tell apart without clicking one. [onCheckForUpdates]
 * null leaves the update button out.
 */
@Composable
fun ZillitAppControls(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCheckForUpdates: (suspend () -> UpdateCheckOutcome)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // First in the group: its label grows while it reports, and the
        // group's right edge is anchored, so the growth pushes leftward
        // into empty bar rather than shoving language and theme about.
        onCheckForUpdates?.let { CheckForUpdatesButton(onCheck = it) }
        ZillitLanguageMenu(
            selected = language,
            onSelect = onLanguageChange,
            label = str(S.desktop_switch_language),
        )
        ThemeToggle(themeMode = themeMode, onChange = onThemeModeChange)
    }
}

/**
 * Cycles Light → Dark → System.
 *
 * A three-state cycle rather than a two-state switch, because "follow the OS"
 * is a real preference and a binary toggle silently drops it.
 */
@Composable
private fun ThemeToggle(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val (icon, mode, next) = when (themeMode) {
        ThemeMode.Light -> Triple(ZillitIcons.Sun, str(S.desktop_theme_light), ThemeMode.Dark)
        ThemeMode.Dark -> Triple(ZillitIcons.Moon, str(S.desktop_theme_dark), ThemeMode.System)
        ThemeMode.System -> Triple(ZillitIcons.Monitor, str(S.desktop_theme_system), ThemeMode.Light)
    }
    // The label says what the button does; the icon says which theme is on.
    // A reader who cannot see the icon would be left with a button that never
    // changes, so the mode is the button's state: the tooltip names it in
    // words on hover, and `stateDescription` announces it.
    ZillitTooltip(text = str(S.theme_mode) + " · " + mode) {
        ZillitButton(
            text = str(S.desktop_switch_theme),
            onClick = { onChange(next) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = icon,
            modifier = Modifier.semantics { stateDescription = mode },
        )
    }
}

/**
 * Asks for a newer build now, and says how it went on the button itself.
 *
 * Up to date replaces the label for a few seconds — the answer someone clicked
 * for, where they are looking. A newer build needs no word here: the strip
 * under the bar is the answer, with its download. A failed check keeps the
 * label and turns the icon to a warning, its reason in the tooltip: the reason
 * is a sentence, too long for a button in the bar.
 */
@Composable
private fun CheckForUpdatesButton(onCheck: suspend () -> UpdateCheckOutcome) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<UpdateCheckOutcome?>(null) }
    LaunchedEffect(outcome) {
        if (outcome != null) {
            delay(UPDATE_RESULT_SHOWN_MILLIS)
            outcome = null
        }
    }

    val label = str(S.desktop_check_for_updates)
    val upToDate = str(S.desktop_update_up_to_date)
    val failed = str(S.desktop_update_check_failed)
    val (text, icon, tip) = when (outcome) {
        UpdateCheckOutcome.UpToDate -> Triple(upToDate, ZillitIcons.Check, upToDate)
        UpdateCheckOutcome.Failed -> Triple(label, ZillitIcons.Warning, failed)
        UpdateCheckOutcome.Available, null -> Triple(label, ZillitIcons.Reload, label)
    }
    ZillitTooltip(text = tip) {
        ZillitButton(
            text = text,
            onClick = {
                // A second click while one is in flight is ignored: the
                // answer it would get is the one already coming.
                if (!checking) {
                    checking = true
                    outcome = null
                    scope.launch {
                        try {
                            outcome = onCheck().takeIf { it != UpdateCheckOutcome.Available }
                        } finally {
                            checking = false
                        }
                    }
                }
            },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = icon,
            loading = checking,
            modifier = Modifier.semantics { if (outcome != null) stateDescription = tip },
        )
    }
}

/** How long "You're on the latest version." stands in for the button's label. */
private const val UPDATE_RESULT_SHOWN_MILLIS = 5_000L
