package com.zillit.desktop.feature.calls.ui

import androidx.compose.ui.graphics.Color
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.feature.calls.domain.CallStatus
import kotlinx.serialization.json.JsonPrimitive
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
fun stageJson(tiles: List<CallTile>, columns: Int, pins: List<String> = emptyList()): String = buildJsonObject {
    put("cols", columns)
    // Pinned tile keys, in pin order: the page lays them out big and asks
    // back through a `pin` event, never deciding for itself.
    putJsonArray("pins") { pins.forEach { add(JsonPrimitive(it)) } }
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
                put("hand", tile.hand)
                // The Line 1 identity this tile answers to: the page binds a
                // consumed stream to a tile by the peer id's user half, and a
                // model without it strands every remote video in "no tile".
                put("peerId", tile.userId)
                // What a pin names — the one id every tile has, self and
                // unclaimed streams included.
                put("key", tile.key)
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
/**
 * One reaction, for the page to float over its own picture.
 *
 * Same reason as [stageJson]: on a video call the surface is a heavyweight
 * native component, and an emoji drawn on the Compose side would rise behind
 * it and never be seen. [id] travels so the page can pick a stable path for it
 * rather than a random one that jumps on every frame.
 */
fun reactionJson(reaction: CallReaction): String = buildJsonObject {
    put("emoji", reaction.emoji)
    put("name", reaction.name)
    put("id", reaction.key)
}.toString()

/**
 * The page's colours — the call palette, not the workspace theme.
 *
 * The workspace's light theme used to be pushed in here, which painted a
 * white stage with white tiles around the picture (seen 2026-09-08); the
 * web's stage is one dark surface whatever the app's theme (`styles.css:3-19`),
 * and so is every phone's. [colors] is kept for the signature's sake and
 * decides nothing.
 */
@Suppress("UNUSED_PARAMETER")
fun themeJson(colors: ZillitColors): String = buildJsonObject {
    put("bg", hex(CallPalette.surface))
    put("tile", hex(CallPalette.control))
    put("tileIdle", hex(CallPalette.tileIdle))
    put("border", hex(CallPalette.surface))
    put("text", hex(CallPalette.text))
    put("muted", hex(CallPalette.muted))
    put("speaking", hex(CallPalette.green))
    put("danger", hex(CallPalette.danger))
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
