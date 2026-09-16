package com.zillit.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a person's profile picture comes from, by their user id.
 *
 * One seam for the whole app: every [ZillitAvatar] given a `userId` asks the
 * loader in scope, so a face shows wherever a person is drawn — not only on
 * the screens that happened to thread a loader through their signatures. Null
 * means they never uploaded one, and the avatar falls back to initials.
 */
fun interface AvatarLoader {
    suspend fun load(userId: String): ImageBitmap?
}

/**
 * The loader in scope. Null by default, so previews and render tests draw
 * initials without a host.
 */
val LocalAvatarLoader: ProvidableCompositionLocal<AvatarLoader?> = staticCompositionLocalOf { null }

/** Puts [loader] in reach of every avatar drawn below it. */
@Composable
fun ProvideAvatarLoader(loader: AvatarLoader?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAvatarLoader provides loader, content = content)
}

/**
 * The picture for [userId] — null while it loads, and for anyone without one.
 *
 * Keyed on the id and the loader, so a row reused for somebody else re-reads
 * rather than keeping the previous face, and a production switch (which
 * installs a new loader) drops the old crew's pictures.
 */
@Composable
fun rememberAvatar(userId: String?): ImageBitmap? {
    val loader = LocalAvatarLoader.current
    val id = userId?.trim()?.takeIf { it.isNotBlank() }
    return produceState<ImageBitmap?>(initialValue = null, loader, id) {
        value = if (loader != null && id != null) runCatching { loader.load(id) }.getOrNull() else null
    }.value
}

/**
 * Remembers every picture it finds, and shares one in-flight fetch between
 * rows asking for the same person at once — a list of forty posts by one
 * author costs one fetch and one decode.
 *
 * A null answer is not kept: the fetch cannot tell "never uploaded one"
 * (settled from the crew list before any network) from a fetch that failed,
 * and remembering the latter would leave a face missing for the session.
 * Asking again for somebody without a picture is a map lookup.
 *
 * Fetches run on the loader's own scope, not the caller's, so a row that
 * scrolls away before its face arrives does not cancel the fetch for the row
 * that scrolls in next. [close] when the crew it describes is gone; [forget]
 * when one person's picture changed.
 */
class CachingAvatarLoader(
    private val fetch: suspend (String) -> ImageBitmap?,
) : AvatarLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val faces = HashMap<String, Deferred<ImageBitmap?>>()

    override suspend fun load(userId: String): ImageBitmap? {
        val pending = mutex.withLock {
            faces.getOrPut(userId) {
                scope.async { runCatching { fetch(userId) }.getOrNull() }
            }
        }
        val face = pending.await()
        if (face == null) mutex.withLock { if (faces[userId] === pending) faces.remove(userId) }
        return face
    }

    /** Drops [userId]'s cached answer, so the next draw fetches afresh. */
    suspend fun forget(userId: String) {
        mutex.withLock { faces.remove(userId) }?.cancel()
    }

    fun close() = scope.cancel()
}
