package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.LocationAccess
import com.zillit.desktop.feature.chat.domain.locationAccess
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * "View user's location", as the web's contact drawer offers it
 * (`cnc_latest/components/personal/InfoSider.jsx`): a tile beside Voice and
 * Video that opens Google Maps at where this person last was.
 *
 * Three answers, decided before anything happens — see
 * [com.zillit.desktop.feature.chat.domain.locationAccess]:
 *
 *  - they share and it is within their window → their position **on the app's
 *    own map**, read-only (`LocalLocationPicker.view`), falling back to the
 *    host's guarded browser launcher only where this build has no map;
 *  - they share but not at this hour → the thread's refusal toast;
 *  - they do not share → [onAsk], which raises [LocationRequestDialog].
 *
 * The web opens a Google Maps tab because a web page has nowhere else to put
 * a map. This client already embeds one — the same Chromium surface the
 * composer's "Share location" picks on — so looking up where somebody is
 * stays inside the app, and the browser is left as the one-click way out of
 * the dialog for Street View and directions.
 *
 * Null hides the tile altogether: neither a map nor a launcher wired, or the
 * viewer's own join of the production is still pending, which is the web's
 * and Android's gate both.
 */
@Composable
internal fun rememberLocationAction(
    onEvent: (ChatEvent) -> Unit,
    onAsk: (CrewContact) -> Unit,
): ((CrewContact) -> Unit)? {
    val seams = LocalChatSeams.current
    val open = seams.onOpenUrl
    val map = LocalLocationPicker.current
    if ((map == null && open == null) || seams.viewerIsPending()) return null
    val zone = remember { TimeZone.currentSystemDefault() }
    val refusal = str(S.location_showing_time_restrict_txt)
    val scope = rememberCoroutineScope()
    return { person ->
        val now = Clock.System.now().toEpochMilliseconds()
        when (val access = person.locationAccess(now, zone)) {
            is LocationAccess.Open ->
                if (map != null) {
                    // Suspends until the reader closes it, so it runs in the
                    // thread's own scope rather than blocking the click.
                    scope.launch {
                        map.view(
                            place = PickedLocation(
                                name = person.fullName,
                                // No address: nothing reverse-geocodes a
                                // tracked position, and inventing one would
                                // put a street name under a moving pin.
                                address = "",
                                lat = access.lat,
                                lng = access.long,
                            ),
                        )
                    }
                } else {
                    open?.invoke(access.mapsUrl)
                }

            LocationAccess.OutsideWindow -> onEvent(ChatEvent.Refuse(refusal))
            LocationAccess.NotShared -> onAsk(person)
        }
    }
}

/**
 * The question a peer who does not share gets: "Individual tracking is
 * disabled by the user. Do you want to send the user a message to enable
 * it?" — the web's `GeneratePdfModal` with No / Yes, and Android's
 * `showDialogWithButton`.
 *
 * Yes posts the request into this very thread, which is what both clients do:
 * the asker's name, then Android's `enable_location_message`, which already
 * carries the Settings path and the two notes in 22 languages. The web builds
 * the same sentence from its own dictionary and renames the page "Web
 * Preferences"; this app's card is App Preferences, so the phones' wording is
 * the accurate one here.
 */
@Composable
internal fun LocationRequestDialog(
    target: CrewContact?,
    askerName: String,
    onEvent: (ChatEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val body = "$askerName ${str(S.enable_location_message)}".trim()
    ZillitDialogShell(
        title = str(S.alert),
        subtitle = str(S.tracking_disabled_text),
        icon = ZillitIcons.Pin,
        visible = target != null,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(text = str(S.no), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            ZillitButton(
                text = str(S.yes),
                onClick = {
                    onDismiss()
                    onEvent(ChatEvent.AskToShareLocation(body))
                },
            )
        },
    ) {}
}
