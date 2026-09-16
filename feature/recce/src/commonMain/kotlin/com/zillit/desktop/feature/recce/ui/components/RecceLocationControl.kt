package com.zillit.desktop.feature.recce.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.layout.ContentScale
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
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.ui.StopEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The unified location control, one per stop and one for the rendezvous —
 * the web's `RecceLocationField`.
 *
 * The map PIN is the single source of truth for where a stop is; the route
 * picture, the PDF and the "Open in Google Maps" link all derive from it.
 * Picking happens in one place, the map picker (search / click / drag),
 * which reverse-geocodes the address — there is no address field to type
 * into, as there is none on the web. Once a pin is set the stop shows a
 * still of the map; a pasted Google Maps link sets the pin too, and the
 * link field always shows the canonical link with a copy affordance.
 *
 * What3Words is deliberately not here — it is a display-only satnav code
 * kept separate in the form.
 */
@Composable
internal fun RecceLocationControl(
    stop: StopEditor,
    onChange: (StopEditor) -> Unit,
    preview: ByteArray?,
    previewKnown: Boolean,
    onNeedPreview: (LatLng) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Location",
    required: Boolean = false,
    namePlaceholder: String = "e.g. Millennium Bridge",
    errorText: String? = null,
) {
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val pin = stop.pin

    LaunchedEffect(pin) { pin?.let(onNeedPreview) }

    fun openPicker() {
        val service = picker ?: return
        if (picking) return
        picking = true
        scope.launch {
            try {
                val initial = pin?.let { PickedLocation(stop.place, stop.address, it.lat, it.lng) }
                service.pick(initial = initial, title = "Pick a location")?.let { picked ->
                    // The picked venue names a place that had none; one already
                    // named keeps its name — the web's `place: name || place`.
                    onChange(
                        stop.copy(
                            place = stop.place.ifBlank { picked.name },
                            address = picked.address,
                            lat = picked.lat,
                            long = picked.lng,
                        ),
                    )
                }
            } finally {
                picking = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Labelled(label = label, required = required, errorText = errorText) {
            ZillitTextField(
                value = stop.place,
                onValueChange = { onChange(stop.copy(place = it)) },
                placeholder = namePlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (pin != null) {
            PinPreview(pin, stop.address, preview, previewKnown)
        } else {
            EmptyMapBox(enabled = picker != null, onClick = ::openPicker)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (picker != null) {
                ZillitButton(
                    text = if (pin != null) "Move / search on map" else PICK_ON_MAP,
                    onClick = ::openPicker,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Search,
                    loading = picking,
                )
            }
            if (pin != null) {
                RecceLink(
                    text = "Open in Google Maps",
                    onClick = { onOpenUrl(stop.mapsUrl) },
                    iconSize = 14.dp,
                    fontSize = 13.sp,
                )
            }
        }

        MapsLinkField(stop, onChange)
    }
}

/** The still of the map behind the pin, with the reverse-geocoded address under it. */
@Composable
private fun PinPreview(pin: LatLng, address: String, preview: ByteArray?, known: Boolean) {
    val colors = ZillitTheme.colors
    val image = remember(preview) { preview?.let(::decodeImageBitmap) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = "Map of the pinned location",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                !known -> ZillitSpinner()
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitIcon(icon = RecceIcons.MapPin, tint = RecceColors.Brand, size = 18.dp)
                    ZillitText(
                        text = "Pinned at ${pin.lat.round()}, ${pin.lng.round()}",
                        style = ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textSecondary,
                    )
                }
            }
        }
        if (address.isNotBlank()) MutedText(address, size = 12.5.sp)
    }
}

/** The click-to-pick empty state — a dashed box with the web's prompt. */
@Composable
private fun EmptyMapBox(enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .dashedBorder(colors.borderStrong, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        ZillitIcon(icon = RecceIcons.MapPin, tint = colors.textMuted, size = 16.dp)
        MutedText(
            if (enabled) {
                "Search a place or drop a pin to show it on the map"
            } else {
                "Paste a Google Maps link below to place this stop"
            },
        )
    }
}

/**
 * The Google Maps link: auto-filled from the pin, or pasted to set one. The
 * URL is never stored — the canonical link is always derived from the
 * coordinates; a link without any (a shortened `goo.gl`) cannot set the pin.
 */
@Composable
private fun MapsLinkField(stop: StopEditor, onChange: (StopEditor) -> Unit) {
    // What the person typed. Kept as typed even once it parses: a link typed
    // (or pasted) character by character parses early — `@51.5,-0` is a pin —
    // and swapping the text for the canonical link mid-edit would splice the
    // rest of the keystrokes onto that, landing the pin somewhere else. The
    // pin follows every parse; the text only gives way to the canonical link
    // when the pin changes from elsewhere (the map picker) or is cleared.
    var typed by remember { mutableStateOf<String?>(null) }
    val link = stop.mapsUrl
    LaunchedEffect(stop.pin) {
        val current = typed
        if (current != null && MapsLink.parseLatLng(current)?.let { LatLng(it.first, it.second) } != stop.pin) {
            typed = null
        }
    }
    val shown = typed ?: link
    val unparsed = typed != null && typed!!.isNotBlank() && MapsLink.parseLatLng(typed!!) == null

    Labelled(label = "Google Maps link", hint = "(optional — auto-filled from the pin, or paste one)") {
        ZillitTextField(
            value = shown,
            onValueChange = { next ->
                typed = next
                MapsLink.parseLatLng(next)?.let { (lat, lng) ->
                    if (lat != stop.lat || lng != stop.long) onChange(stop.copy(lat = lat, long = lng))
                }
            },
            placeholder = "https://maps.google.com/…",
            helperText = if (unparsed) "No coordinates in that link" else null,
            modifier = Modifier.fillMaxWidth(),
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
        ZillitText(text = if (copied) "Copied" else "Copy", style = ZillitTheme.typography.label, color = tint)
    }
}

@Suppress("MagicNumber")
private fun Double.round(): String = ((this * 100_000).toLong() / 100_000.0).toString()

/** The picker button's label — what the render test looks for. */
internal const val PICK_ON_MAP = "Search / pick on map"
private val PREVIEW_HEIGHT = 168.dp
private const val COPIED_MS = 1_500L
