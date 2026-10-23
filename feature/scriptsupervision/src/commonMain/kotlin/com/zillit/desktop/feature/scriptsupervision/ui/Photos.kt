package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.media.decodeImageBitmap
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This tool's stored photos, shown — the web's `looks/photos.js`
 * (`useSignedUrls`) and `usePhotoUrls`.
 *
 * ## What a stored photo value is
 *
 * Three things, and which one decides whether it has to be signed at all:
 * an older record holds a `data:` URL or a link somebody pasted, and a new one
 * holds this tool's own storage KEY. A key is signed through
 * `POST /upload/download-url`, which answers a one-hour `presigned_url`;
 * anything with a scheme on it is already an address.
 *
 * ## Why the signed address is cached across screens
 *
 * A look board draws forty thumbnails of twelve characters, and each would
 * otherwise sign its own key on every recomposition. The web caches a signed
 * address for fifty of its sixty minutes and re-tries a key that would not
 * sign after one; this does the same, and caches the DECODED picture too,
 * because the look board scrolls through the same faces.
 */

/** Already an address: nothing to sign. The web's `DIRECT` test. */
private val DIRECT = Regex("^(?:data:|https?:|blob:)", RegexOption.IGNORE_CASE)

internal fun isStorageKey(value: String): Boolean = value.isNotBlank() && !DIRECT.containsMatchIn(value)

/** A signed address and when it stops being worth reusing. */
private class Signed(val url: String, val until: Long)

/**
 * Signed addresses and decoded pictures, for the life of the window.
 *
 * Plain maps guarded by nothing: everything here runs on the main dispatcher
 * from a composition, and the one `withContext` is the decode, which touches
 * neither map.
 */
private val signed = mutableMapOf<String, Signed>()
private val decoded = LinkedHashMap<String, ImageBitmap>()

private fun cache(key: String, bitmap: ImageBitmap) {
    if (decoded.size >= IMAGE_LIMIT) decoded.keys.firstOrNull()?.let(decoded::remove)
    decoded[key] = bitmap
}

private fun now(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * The address [value] resolves to: itself when it already is one, else this
 * tool's signed link for the key. Blank when it will not sign.
 */
private suspend fun SsCtx.addressOf(value: String, now: Long): String {
    if (!isStorageKey(value)) return value
    signed[value]?.takeIf { it.until > now }?.let { return it.url }
    val answer = api.post("/upload/download-url", buildJsonObject { put("key", value) })
    val url = (answer as? ZillitResult.Success)?.data?.rec?.str("presigned_url").orEmpty()
    // A key that would not sign is re-tried in a minute, not on every frame.
    signed[value] = Signed(url, now + if (url.isNotBlank()) SIGNED_TTL_MS else RETRY_MS)
    return url
}

/**
 * A stored photo, decoded; null while it loads and when it will not load.
 *
 * Keyed by the stored value, so a re-list of the same records redraws from the
 * cache rather than signing and fetching again.
 */
@Composable
fun rememberPhoto(stored: String): ImageBitmap? {
    val ctx = LocalSs.current
    val bitmap by produceState(decoded[stored], stored) {
        if (stored.isBlank() || stored in decoded) return@produceState
        val url = ctx.addressOf(stored, now()).ifBlank { return@produceState }
        val bytes = ctx.host.fetch(url) ?: return@produceState
        val picture = withContext(Dispatchers.Default) { decodeImageBitmap(bytes) } ?: return@produceState
        cache(stored, picture)
        value = picture
    }
    return bitmap
}

/**
 * A character's or an actor's square photo, falling back to the initial on its
 * own colour — the web's `CharacterAvatar`, whose fallback is the same letter.
 */
@Composable
fun PhotoAvatar(name: String, photo: String, size: Dp = AVATAR, modifier: Modifier = Modifier) {
    val bitmap = rememberPhoto(photo)
    if (bitmap == null) {
        ZillitAvatar(name = name, size = size, modifier = modifier)
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(PHOTO_RADIUS)),
    )
}

/** One thumbnail, [onOpen] to see it full size. */
@Composable
fun PhotoThumb(
    value: String,
    width: Dp = THUMB_WIDTH,
    height: Dp = THUMB_HEIGHT,
    onOpen: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(PHOTO_RADIUS)
    val bitmap = rememberPhoto(value)
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(colors.surfaceHover)
            .border(1.dp, colors.border, shape)
            .let { if (onOpen != null) it.clickable(onClick = onOpen) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MutedText("…")
        }
    }
}

/** A row of thumbnails that wraps — the web's `PhotoStrip`. */
@Composable
fun PhotoStrip(values: List<String>, onOpen: ((String) -> Unit)? = null) {
    if (values.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        values.filter { it.isNotBlank() }.forEach { value ->
            PhotoThumb(value, onOpen = onOpen?.let { open -> { open(value) } })
        }
    }
}

/** The signed address of [value], for the viewer's browser (a photo full size). */
suspend fun SsCtx.photoAddress(value: String): String = addressOf(value, now())

private const val MINUTE_MS = 60_000L

/** Signed links last an hour; fifty minutes is the web's own reuse window. */
private const val SIGNED_TTL_MS = 50 * MINUTE_MS
private const val RETRY_MS = MINUTE_MS
private const val IMAGE_LIMIT = 200

private val AVATAR = 32.dp
private val PHOTO_RADIUS = 6.dp
private val THUMB_WIDTH = 56.dp
private val THUMB_HEIGHT = 44.dp
