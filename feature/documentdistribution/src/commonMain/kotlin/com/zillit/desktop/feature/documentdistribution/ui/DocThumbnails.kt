package com.zillit.desktop.feature.documentdistribution.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Where a card gets its cover picture from (the web's `DocThumb`).
 *
 * The picture is a small JPEG in the document's own bucket, fetched through the
 * app's signed GET and drawn only when its card is composed — a lazy grid
 * composes just what is on screen, which is what the web's IntersectionObserver
 * does by hand. Null means "show the file-type icon", which is also what a
 * document with no cover, a failed fetch and a corrupt picture all get.
 */
fun interface DocThumbnails {
    suspend fun image(document: LibraryDocument): ImageBitmap?

    companion object {
        val None = DocThumbnails { null }
    }
}

/** The screen's source, provided once at the top so a card needs no plumbing. */
val LocalDocThumbnails = staticCompositionLocalOf { DocThumbnails.None }

/**
 * A repository-backed source with a memory of what it already fetched.
 *
 * Remembered per storage key so a card that scrolls away and back, or a list
 * re-rendered by a badge refresh, does not fetch the same picture again — and a
 * failure is remembered too, so a row pointing at a bucket the project cannot
 * reach is asked once, not on every scroll. Fetches are capped: a folder can
 * hold dozens of documents, and they all compose together.
 */
class CachedDocThumbnails(private val repository: DocDistRepository) : DocThumbnails {

    private val remembered = object : LinkedHashMap<String, ImageBitmap?>(CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?): Boolean =
            size > CAPACITY
    }
    private val gate = Semaphore(PARALLEL)

    override suspend fun image(document: LibraryDocument): ImageBitmap? {
        val key = document.thumbnail?.key ?: return null
        synchronized(remembered) { if (key in remembered) return remembered[key] }
        val decoded = gate.withPermit {
            // Another card may have fetched it while this one waited its turn.
            synchronized(remembered) { if (key in remembered) return remembered[key] }
            when (val fetched = repository.thumbnailBytes(document)) {
                is ZillitResult.Success ->
                    withContext(Dispatchers.Default) { decodeImageBitmap(fetched.data, Thumbnails.WIDTH) }
                is ZillitResult.Failure -> null
            }
        }
        synchronized(remembered) { remembered[key] = decoded }
        return decoded
    }

    private companion object {
        const val CAPACITY = 200
        const val LOAD_FACTOR = 0.75f
        const val PARALLEL = 4
    }
}
