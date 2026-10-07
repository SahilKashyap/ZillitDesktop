package com.zillit.desktop.feature.selectstills.domain

/**
 * Keeps a photo's signed link the same for as long as it works — the web's
 * `lib/urlCache.js`.
 *
 * Every answer from the service carries freshly signed links. The service
 * signs as of the start of the hour, so a link normally comes back identical —
 * but across an hour boundary a patched tile would get a new link and reload
 * its image for nothing. The first link seen for a file is kept until shortly
 * before it stops working.
 */
class StillsUrlCache(private val now: () -> Long) {

    private class Entry(val url: String, val until: Long)

    private val cache = LinkedHashMap<String, Entry>()

    fun stable(key: String, url: String, expiresAt: Long): String {
        if (url.isBlank()) return url
        val at = now()
        val hit = cache[key]
        if (hit != null && hit.until - at > SAFETY_MS) return hit.url
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[key] = Entry(url, if (expiresAt > 0) expiresAt else at + HOUR_MS)
        return url
    }

    /** A list tile with its thumbnail link held steady. */
    fun stableTile(tile: PhotoTile, expiresAt: Long): PhotoTile =
        if (tile.thumbUrl.isBlank()) {
            tile
        } else {
            tile.copy(thumbUrl = stable("${tile.id}:thumb", tile.thumbUrl, expiresAt))
        }

    fun stableTiles(tiles: List<PhotoTile>, expiresAt: Long): List<PhotoTile> =
        tiles.map { stableTile(it, expiresAt) }

    fun clear() = cache.clear()

    private companion object {
        const val SAFETY_MS = 60L * 1000
        const val HOUR_MS = 60L * 60 * 1000
        const val MAX_ENTRIES = 20_000
    }
}
