package com.zillit.desktop.core.locationpicker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.MapsLink
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where something is: a pin, dropped on the map or read out of a pasted
 * Google Maps link.
 *
 * The map PIN is the single source of truth. The picture of the place, the
 * "Open in Google Maps" link and anything saved all derive from it; a URL
 * somebody typed is only ever read for its coordinates and then thrown away,
 * so two clients never disagree about what a place's link means.
 *
 * Richer than [ZillitLocationField], which asks for a place as *text* with
 * the map as an optional second route. This one is for the surfaces that
 * need the coordinates themselves — a recce stop, a scouted location — and
 * is what the web's `RecceLocationField` became when the Location tool
 * started reusing it (PR #6521).
 *
 * [header] is an optional slot above the map for whatever else the place
 * needs — Recce puts its name field there, the Location tool nothing.
 */
@Composable
@Suppress("LongParameterList")
fun ZillitPinField(
    place: PinnedPlace?,
    onPlaceChange: (PinnedPlace) -> Unit,
    modifier: Modifier = Modifier,
    label: String = str(S.location),
    required: Boolean = false,
    errorText: String? = null,
    /** The still of the map behind the pin; null while one is being fetched. */
    preview: ImageBitmap? = null,
    /** False means "a picture is on its way"; true with a null [preview] means there is none. */
    previewKnown: Boolean = true,
    onNeedPreview: (PinnedPlace) -> Unit = {},
    /** Opens the canonical link in a browser; no link is offered without it. */
    onOpenUrl: ((String) -> Unit)? = null,
    /** Where the picker opens when this place has no pin yet. */
    near: PickerStart? = null,
    /** The accent a pin is drawn in; the theme's own by default. */
    accent: Color? = null,
    pinIcon: ImageVector = ZillitIcons.Pin,
    /** What the empty box says on a host with no map of its own to open. */
    noPickerHint: String = str(S.desktop_recce_paste_maps_link),
    header: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(place?.lat, place?.long) { place?.let(onNeedPreview) }

    fun openPicker() {
        val service = picker ?: return
        if (picking) return
        picking = true
        scope.launch {
            try {
                val initial = place?.let { PickedLocation(it.name, it.address, it.lat, it.long) }
                service.pick(initial = initial, title = label, near = near)?.let { picked ->
                    onPlaceChange(PinnedPlace(picked.lat, picked.lng, picked.address, picked.name))
                }
            } finally {
                picking = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        header?.invoke(this) ?: PinLabel(label, required, errorText)

        if (place != null) {
            PinPreview(place, preview, previewKnown, accent ?: ZillitTheme.colors.accent, pinIcon)
        } else {
            EmptyMapBox(
                enabled = picker != null,
                icon = pinIcon,
                noPickerHint = noPickerHint,
                onClick = ::openPicker,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (picker != null) {
                ZillitButton(
                    text = if (place != null) {
                        str(S.desktop_recce_move_search_on_map)
                    } else {
                        str(S.desktop_recce_search_pick_on_map)
                    },
                    onClick = ::openPicker,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Search,
                    loading = picking,
                )
            }
            if (place != null && onOpenUrl != null) {
                ZillitButton(
                    text = str(S.recce_open_in_maps),
                    onClick = { onOpenUrl(place.mapsUrl) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = pinIcon,
                )
            }
        }

        MapsLinkField(place, onPlaceChange)
    }
}

/** The field's own heading, when the caller gave no [ZillitPinField.header]. */
@Composable
private fun PinLabel(label: String, required: Boolean, errorText: String?) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(
            text = if (required) "$label *" else label,
            style = ZillitTheme.typography.label,
            color = colors.textSecondary,
        )
        errorText?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.danger)
        }
    }
}

/** The still of the map behind the pin, with the reverse-geocoded address under it. */
@Composable
private fun PinPreview(
    place: PinnedPlace,
    preview: ImageBitmap?,
    known: Boolean,
    accent: Color,
    pinIcon: ImageVector,
) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .testTag(PIN_PREVIEW_TAG),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preview != null -> Image(
                    bitmap = preview,
                    contentDescription = str(S.desktop_recce_map_of_pinned_location),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                !known -> ZillitSpinner()
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitIcon(icon = pinIcon, tint = accent, size = 18.dp)
                    ZillitText(
                        text = str(S.desktop_recce_pinned_at, place.lat.round(), place.long.round()),
                        style = ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textSecondary,
                    )
                }
            }
        }
        if (place.address.isNotBlank()) {
            ZillitText(
                text = place.address,
                style = ZillitTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                color = colors.textSecondary,
            )
        }
    }
}

/** The click-to-pick empty state — a dashed box with the web's prompt. */
@Composable
private fun EmptyMapBox(enabled: Boolean, icon: ImageVector, noPickerHint: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .dashedBorder(colors.borderStrong, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag(PIN_EMPTY_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        ZillitIcon(icon = icon, tint = colors.textMuted, size = 16.dp)
        ZillitText(
            text = if (enabled) str(S.desktop_recce_search_or_drop_pin) else noPickerHint,
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = colors.textSecondary,
        )
    }
}

/**
 * The Google Maps link: auto-filled from the pin, or pasted to set one.
 *
 * The URL is never stored — the canonical link is always derived from the
 * coordinates; a link without any (a shortened `goo.gl`) cannot set the pin,
 * which is the web's rule too ("only Google Maps links with coordinates are
 * accepted").
 */
@Composable
private fun MapsLinkField(place: PinnedPlace?, onPlaceChange: (PinnedPlace) -> Unit) {
    // What the person typed. Kept as typed even once it parses: a link typed
    // (or pasted) character by character parses early — `@51.5,-0` is a pin —
    // and swapping the text for the canonical link mid-edit would splice the
    // rest of the keystrokes onto that, landing the pin somewhere else. The
    // pin follows every parse; the text only gives way to the canonical link
    // when the pin changes from elsewhere (the map picker) or is cleared.
    var typed by remember { mutableStateOf<String?>(null) }
    val link = place?.mapsUrl.orEmpty()
    LaunchedEffect(place?.lat, place?.long) {
        val current = typed
        if (current == null) return@LaunchedEffect
        val parsed = MapsLink.parseLatLng(current)
        if (parsed?.first != place?.lat || parsed?.second != place?.long) typed = null
    }
    val shown = typed ?: link
    val unparsed = !shown.isBlank() && typed != null && MapsLink.parseLatLng(shown) == null

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitTextField(
            value = shown,
            onValueChange = { next ->
                typed = next
                MapsLink.parseLatLng(next)?.let { (lat, long) ->
                    if (lat != place?.lat || long != place.long) {
                        // A link carries no venue name; the place keeps the one it has.
                        onPlaceChange(PinnedPlace(lat, long, place?.address.orEmpty(), place?.name.orEmpty()))
                    }
                }
            },
            label = str(S.desktop_recce_google_maps_link),
            placeholder = "https://maps.google.com/…",
            helperText = if (unparsed) {
                str(S.desktop_recce_no_coordinates_in_link)
            } else {
                str(S.desktop_recce_maps_link_hint)
            },
            modifier = Modifier.fillMaxWidth().testTag(PIN_LINK_TAG),
            trailingContent = { CopyAffordance(link) },
        )
    }
}

/** "Copy" / "Copied" at the link field's trailing edge — the web's `addonAfter`. */
@Composable
private fun CopyAffordance(link: String) {
    val colors = ZillitTheme.colors
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val enabled = link.isNotBlank()
    val tint = if (enabled) colors.textSecondary else colors.textDisabled
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) {
                copyTextToClipboard(link)
                copied = true
                scope.launch {
                    delay(COPIED_MS)
                    copied = false
                }
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ZillitIcon(icon = if (copied) ZillitIcons.Check else ZillitIcons.Copy, tint = tint, size = 14.dp)
        ZillitText(
            text = if (copied) str(S.dd_copied) else str(S.copy),
            style = ZillitTheme.typography.label,
            color = tint,
        )
    }
}

@Suppress("MagicNumber")
private fun Double.round(): String = ((this * 100_000).toLong() / 100_000.0).toString()

const val PIN_PREVIEW_TAG = "pin-field-preview"
const val PIN_EMPTY_TAG = "pin-field-empty"
const val PIN_LINK_TAG = "pin-field-link"

/** A 1dp dashed outline — the web's `border: 1px dashed` on the empty map box. */
@Composable
private fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape): Modifier {
    val density = LocalDensity.current
    val stroke = with(density) { 1.dp.toPx() }
    val dash = with(density) { DASH.toPx() }
    val radius = with(density) { shape.topStart.toPx(Size.Zero, this) }
    return drawBehind {
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
        )
    }
}

private val DASH = 5.dp
private val PREVIEW_HEIGHT = 168.dp
private const val COPIED_MS = 1_500L
