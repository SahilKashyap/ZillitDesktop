package com.zillit.desktop.core.locationpicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.coroutines.launch

/**
 * A text field with a "Pick on map" affordance.
 *
 * Replaces the two ways the desktop used to ask for a place: transportation's
 * pair of raw latitude/longitude boxes ("paste a Google Maps link"), and the
 * calendar's free-text venue with no coordinates at all. The web asks the same
 * way in both of its pickers — an address input you may simply *type into*,
 * with the map as an optional second route
 * (transportationHub/common/LocationPicker.jsx:157-170,
 * boxScheduleV2/components/PlacePicker.jsx:202-245) — so typing still works
 * here: an address someone knows by heart must not need a map. Picking fills
 * the text **and** reports coordinates.
 *
 * The map button is hidden when no [LocalLocationPicker] is provided, so tests
 * and hosts without an embedded browser see a plain text field rather than a
 * control that does nothing.
 */
@Composable
fun ZillitLocationField(
    text: String,
    onTextChange: (String) -> Unit,
    onPicked: (PickedLocation) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    /** Seeds the map when reopening a saved place, so it does not open across the ocean. */
    initial: PickedLocation? = null,
) {
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    // The dialog suspends for as long as the user takes; without this the
    // button stays live and a second click opens a second picker.
    var picking by remember { mutableStateOf(false) }

    ZillitTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helperText = helperText,
        errorText = errorText,
        leadingIcon = ZillitIcons.Pin,
        enabled = enabled,
        trailingContent = picker?.let {
            {
                ZillitButton(
                    text = PICK_ON_MAP,
                    onClick = {
                        picking = true
                        scope.launch {
                            try {
                                val place = picker.pick(initial = initial, title = label)
                                if (place != null) {
                                    // Text first, then the full place: a call
                                    // site that keeps only a string still gets
                                    // filled in, and one that stores
                                    // coordinates has the last word.
                                    onTextChange(place.address)
                                    onPicked(place)
                                }
                            } finally {
                                picking = false
                            }
                        }
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Pin,
                    enabled = enabled,
                    loading = picking,
                )
            }
        },
    )
}

/** Also the button's accessible label, and what the field's render test looks for. */
private const val PICK_ON_MAP = "Pick on map"
