package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.PickerTheme
import java.util.Locale

/**
 * Installs the map picker for everything composed inside [content], and hosts
 * its dialog above them.
 *
 * One mount rather than two wiring points, because the two halves cannot be
 * separated: a `LocalLocationPicker` with nothing rendering its dialog is a
 * "Pick on map" button that suspends forever.
 *
 * Where there is no picker — no embedded browser in this build, or the graph
 * is not [AppGraph.Ready] yet — nothing is provided and every
 * `ZillitLocationField` quietly becomes a plain text field.
 */
@Composable
internal fun LocationPickerMount(graph: AppGraph, content: @Composable () -> Unit) {
    val host = (graph as? AppGraph.Ready)?.locationPicker
    if (host == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalLocationPicker provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            LocationPickerDialog(host)
        }
    }
}

/**
 * The modal: the Chromium map surface, what is currently picked, and the two
 * ways out.
 *
 * ## What is Compose here and what is not
 *
 * The search box is **not** here — it is inside the page. The CEF surface is a
 * heavyweight AWT component and paints above every Compose pixel in the
 * window, so an autocomplete dropdown drawn by Compose over the map would
 * simply be invisible. See `picker.html` for the full note.
 *
 * Everything outside the map's own rectangle is ordinary Compose and behaves
 * normally. Two consequences worth knowing: the shell's scale-in entrance does
 * not apply to the heavyweight child, so the map appears at its final size
 * while the card grows around it; and if the Maps tool is open behind the
 * dialog, its canvas is another heavyweight component on the same layer and
 * may show through the scrim.
 */
@Composable
private fun LocationPickerDialog(host: KcefLocationPickerHost) {
    val request by host.request.collectAsState()
    val picked by host.picked.collectAsState()
    val failure by host.failure.collectAsState()
    val colors = ZillitTheme.colors

    // The page paints its own search box, so it needs the app's colours. Sent
    // on every theme change, including the first composition.
    LaunchedEffect(colors) { host.useTheme(colors.asPickerTheme()) }

    ZillitDialogShell(
        title = request?.title ?: "Pick a location",
        onDismiss = host::cancel,
        visible = request != null,
        icon = ZillitIcons.Pin,
        subtitle = "Search, click the map, or drag the pin",
        width = DIALOG_WIDTH,
        maxHeight = DIALOG_HEIGHT,
        // The map takes a fixed height of its own; nothing here scrolls, and a
        // scrolling parent would only give the heavyweight surface a second
        // coordinate system to disagree with.
        scrollable = false,
        actions = {
            ZillitButton(text = "Cancel", onClick = host::cancel, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = "Use this location",
                onClick = host::confirm,
                enabled = picked != null,
            )
        },
    ) {
        MapSurface(host = host, failure = failure)
        PickedSummary(picked)
    }
}

/** The Chromium surface, or what is standing in for it. */
@Composable
private fun MapSurface(host: KcefLocationPickerHost, failure: String?) {
    val component by host.surface.collectAsState()
    val awtComponent = component
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failure != null -> ZillitText(
                text = failure,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )
            awtComponent == null -> ZillitSpinner()
            else -> {
                // Handed back when the dialog leaves the screen: the host parks
                // the component in a hidden window of its own, because a browser
                // component left with no parent is a browser that will not work
                // the next time the picker opens.
                DisposableEffect(awtComponent) {
                    onDispose { host.releaseSurface() }
                }
                SwingPanel(factory = { awtComponent }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The chosen place, under the map.
 *
 * The web shows the same three things on its green card — name, address, and
 * the coordinates in a monospace run (PlacePicker.jsx:249-289) — because the
 * pin alone does not tell you *which* of two similarly named places Google
 * settled on.
 */
@Composable
private fun PickedSummary(picked: PickedLocation?) {
    val colors = ZillitTheme.colors
    if (picked == null) {
        ZillitText(
            text = "No place chosen yet.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.successSoft)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitIcon(ZillitIcons.Pin, tint = colors.success)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = picked.name.ifBlank { picked.address }.ifBlank { "Dropped pin" },
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 2,
            )
            if (picked.address.isNotBlank() && picked.address != picked.name) {
                ZillitText(
                    text = picked.address,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            }
            ZillitText(
                text = "${format6(picked.lat)}, ${format6(picked.lng)}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * Six decimal places, the precision the web's card shows (PlacePicker.jsx:273).
 *
 * Explicitly `Locale.US`: these are coordinates to be read back to a driver or
 * pasted into another map, and a decimal comma makes a pair of them ambiguous.
 * Also why this is not `Double.toString` — a longitude just off the prime
 * meridian comes out of that in scientific notation.
 */
private fun format6(value: Double): String = String.format(Locale.US, "%.6f", value)

/** The design tokens the page's own chrome is painted from. */
private fun ZillitColors.asPickerTheme(): PickerTheme = PickerTheme(
    background = canvas.hex(),
    surface = surface.hex(),
    text = textPrimary.hex(),
    accent = accent.hex(),
    border = border.hex(),
    isDark = isDark,
)

/** `#RRGGBB`, the only colour syntax the page's stylesheet uses. */
private fun Color.hex(): String {
    val rgb = toArgb() and RGB_MASK
    return "#" + rgb.toString(HEX_RADIX).uppercase().padStart(RGB_DIGITS, '0')
}

private val DIALOG_WIDTH = 760.dp
private val DIALOG_HEIGHT = 720.dp
private val MAP_HEIGHT = 380.dp
private val HAIRLINE = 1.dp
private const val RGB_MASK = 0xFFFFFF
private const val HEX_RADIX = 16
private const val RGB_DIGITS = 6
