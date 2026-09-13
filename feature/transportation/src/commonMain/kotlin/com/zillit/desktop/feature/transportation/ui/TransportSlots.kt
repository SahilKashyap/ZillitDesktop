package com.zillit.desktop.feature.transportation.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportUser

/**
 * What the app around the transport tool lends its screen: faces, the
 * object store's pictures, a phone line to a driver, and the browser.
 * Every slot may be absent — tests, and the render harness.
 */
class TransportSlots(
    /** A crew member's profile picture, decoded; null for none or while offline. */
    val loadAvatar: suspend (userId: String) -> ImageBitmap? = { null },
    /** A stored picture — a licence, a vehicle photograph — decoded; null when unreachable. */
    val loadMedia: suspend (StoredMedia) -> ImageBitmap? = { null },
    /** Rings the driver — the web's `UserPrivateAudioCall` on every driver row. */
    val call: ((user: TransportUser) -> Unit)? = null,
    /** Opens a stored document that is not a picture — a PDF — where it can be read. */
    val openDocument: (suspend (StoredMedia) -> Unit)? = null,
)

val LocalTransportSlots: ProvidableCompositionLocal<TransportSlots> = staticCompositionLocalOf { TransportSlots() }
