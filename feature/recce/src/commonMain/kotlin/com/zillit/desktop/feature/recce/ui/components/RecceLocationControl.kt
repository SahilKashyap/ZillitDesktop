package com.zillit.desktop.feature.recce.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.locationpicker.PickerStart
import com.zillit.desktop.core.locationpicker.PinnedPlace
import com.zillit.desktop.core.locationpicker.ZillitPinField
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.ui.StopEditor

/**
 * The unified location control, one per stop and one for the rendezvous —
 * the web's `RecceLocationField`.
 *
 * The map PIN is the single source of truth for where a stop is; the route
 * picture, the PDF and the "Open in Google Maps" link all derive from it.
 * Picking happens in one place, the map picker (search / click / drag),
 * which reverse-geocodes the address — there is no address field to type
 * into, as there is none on the web.
 *
 * The pin, its picture and the Google Maps link box are
 * [ZillitPinField]'s — the shared control, which the Location tool reuses
 * exactly as the web reuses this component (PR #6521). What stays here is
 * what is Recce's: the stop's NAME above the map, and the brand amber the
 * pin is drawn in.
 *
 * [nearPin] is where the picker opens when this place has no pin yet: a pin
 * already on the same recce. The picker falls back from there to the last
 * place this device used and then to a coarse fix, so it never opens on a
 * city nobody chose (web, Oct 2026).
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
    label: String = str(S.location),
    required: Boolean = false,
    namePlaceholder: String = "e.g. Millennium Bridge",
    errorText: String? = null,
    nearPin: LatLng? = null,
) {
    val image = remember(preview) { preview?.let(::decodeImageBitmap) }
    ZillitPinField(
        place = stop.pin?.let { PinnedPlace(it.lat, it.lng, stop.address, stop.place) },
        onPlaceChange = { picked ->
            // The picked venue names a place that had none; one already
            // named keeps its name — the web's `place: name || place`.
            onChange(
                stop.copy(
                    place = stop.place.ifBlank { picked.name },
                    address = picked.address,
                    lat = picked.lat,
                    long = picked.long,
                ),
            )
        },
        modifier = modifier,
        label = label,
        preview = image,
        previewKnown = previewKnown,
        onNeedPreview = { place -> onNeedPreview(LatLng(place.lat, place.long)) },
        onOpenUrl = onOpenUrl,
        near = nearPin?.let { PickerStart(it.lat, it.lng) },
        accent = RecceColors.Brand,
        pinIcon = RecceIcons.MapPin,
    ) {
        Labelled(label = label, required = required, errorText = errorText) {
            ZillitTextField(
                value = stop.place,
                onValueChange = { onChange(stop.copy(place = it)) },
                placeholder = namePlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The picker button's label — what the render test looks for. */
internal val PICK_ON_MAP: String get() = str(S.desktop_recce_search_pick_on_map)
