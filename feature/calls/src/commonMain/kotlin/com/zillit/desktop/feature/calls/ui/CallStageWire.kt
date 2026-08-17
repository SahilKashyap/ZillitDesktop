package com.zillit.desktop.feature.calls.ui

import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The model the embedded page draws its own tile chrome from.
 *
 * The page has to be told this because it owns the pixels inside the video
 * rectangle — a heavyweight browser surface draws above anything Compose puts
 * on top of it, so a name chip or a mute badge for a *video* tile cannot be
 * drawn on this side at all.
 *
 * It is told identity and shape only. Who is speaking and who has video it
 * knows first-hand, a frame earlier than Kotlin could tell it.
 */
fun stageJson(tiles: List<CallTile>, columns: Int): String = buildJsonObject {
    put("cols", columns)
    putJsonArray("tiles") {
        tiles.forEach { tile ->
            addJsonObject {
                put("uid", tile.uid)
                put("name", tile.name)
                put("self", tile.isSelf)
                put("hue", hex(avatarHue(tile.name)))
                put("muted", tile.media?.audioMuted ?: false)
                put("known", tile.media != null)
                put("ringing", tile.presence == CallStatus.Ringing)
            }
        }
    }
}.toString()

/**
 * The app's palette, for the page's CSS custom properties.
 *
 * Without it the video stage is a foreign slab in the middle of the window —
 * the one place the user is most likely to be looking.
 */
fun themeJson(colors: ZillitColors): String = buildJsonObject {
    put("bg", hex(colors.canvas))
    put("tile", hex(colors.surface))
    put("tileIdle", hex(colors.surfaceSunken))
    put("border", hex(colors.border))
    put("text", hex(colors.textPrimary))
    put("muted", hex(colors.textMuted))
    put("speaking", hex(colors.success))
    put("danger", hex(colors.danger))
}.toString()

/** `#rrggbb`, alpha dropped — CSS gets its transparency from its own rules. */
private fun hex(color: Color): String {
    val argb = color.value shr ARGB_SHIFT
    val rgb = (argb and RGB_MASK).toString(HEX_RADIX).padStart(HEX_DIGITS, '0')
    return "#$rgb"
}

private const val ARGB_SHIFT = 32
private const val RGB_MASK = 0xFFFFFFuL
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 6
