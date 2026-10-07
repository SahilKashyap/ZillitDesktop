package com.zillit.desktop.feature.selectstills.domain

/**
 * The loaded part of a photo list, kept in the server's order (newest first:
 * `sortAt` down, then id down — the same order the paging cursor walks), and
 * patched in place when a socket event names photos that changed. The web's
 * `lib/photoList.js`.
 *
 * A big upload sends over a thousand events, so the list is NEVER reloaded for
 * one: the screen asks for just those ids (with its current filters) and merges
 * the answer here.
 */

/** Whether [a] sorts before [b] (is newer). */
fun isBefore(a: PhotoTile, b: PhotoTile): Boolean =
    if (a.sortAt != b.sortAt) a.sortAt > b.sortAt else a.id > b.id

private val tileOrder = Comparator<PhotoTile> { a, b ->
    when {
        a.id == b.id -> 0
        isBefore(a, b) -> -1
        else -> 1
    }
}

/** Add the next page; a photo already listed is replaced where it stands. */
fun appendPage(list: List<PhotoTile>, page: List<PhotoTile>): List<PhotoTile> {
    if (page.isEmpty()) return list
    val fresh = page.associateByTo(LinkedHashMap()) { it.id }
    val kept = list.map { photo -> fresh.remove(photo.id) ?: photo }
    return kept + fresh.values
}

/**
 * Merge what the service answered for a set of ids.
 *
 * An asked id that did not come back leaves the list (deleted, discarded out of
 * a viewer's sight, or no longer matching the filter). One that came back is
 * replaced, or added if it belongs in the part that is loaded: a photo older
 * than everything loaded waits for its page.
 *
 * Returns the same list when nothing changed, so nothing recomposes.
 *
 * @param asked the ids that were asked about
 * @param found the tiles that came back: the ones this reader may see AND that match the filters
 * @param hasMore whether there are older photos not loaded yet
 */
fun patchList(
    list: List<PhotoTile>,
    asked: List<String>,
    found: List<PhotoTile>,
    hasMore: Boolean,
): List<PhotoTile> {
    val answers = found.associateByTo(LinkedHashMap()) { it.id }
    val askedIds = asked.toSet()
    var changed = false

    val next = mutableListOf<PhotoTile>()
    list.forEach { photo ->
        val answer = answers.remove(photo.id)
        when {
            answer != null -> {
                val same = answer.rev == photo.rev && answer.status == photo.status && answer.thumbUrl == photo.thumbUrl
                if (!same) changed = true
                next += if (same) photo else answer
            }
            photo.id in askedIds -> changed = true
            else -> next += photo
        }
    }

    val last = next.lastOrNull()
    answers.values.forEach { photo ->
        // Older than everything loaded: it waits for its page.
        if (hasMore && last != null && !isBefore(photo, last)) return@forEach
        next += photo
        changed = true
    }

    return if (!changed) list else next.sortedWith(tileOrder)
}

/** Drop photos by id (after a delete made here). */
fun removeIds(list: List<PhotoTile>, ids: Collection<String>): List<PhotoTile> {
    val gone = ids.toSet()
    val next = list.filterNot { it.id in gone }
    return if (next.size == list.size) list else next
}

/** Split ids into requests the service accepts (`ids` takes at most 200). */
fun idBatches(ids: Collection<String>, size: Int = ID_BATCH): List<List<String>> =
    ids.distinct().chunked(size)

const val ID_BATCH: Int = 200
