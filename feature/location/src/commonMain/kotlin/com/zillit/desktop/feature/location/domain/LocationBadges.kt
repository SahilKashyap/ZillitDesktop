package com.zillit.desktop.feature.location.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread leaf of the tool's notification ledger.
 *
 * The service files a location row under the status list as its unit
 * (`location_select_label`…), the place as `level_1`, the scene as `level_2`
 * and the episode as `level_3` (Android `LocationVM.getUnit`,
 * `LibraryPage.markReadFolder`; web `LocationPage.jsx:442-481`). A comment
 * on one record carries the record's id as its chat unit — that is what the
 * web groups "image chat" badges by (`getLCWInnerBadgesFromDB group='all'`).
 */
data class LocationBadgeLeaf(
    val status: LocationStatus,
    val location: String,
    val scene: String,
    val episode: String,
    /** The record a comment row belongs to; blank for a row about the folder itself. */
    val recordId: String,
    val unread: Int,
)

/**
 * The tool's unread rows, and the reads its screen makes — a gallery opened
 * (Android reads the folder's levels, `markReadFolder`), a record's thread
 * opened (the web's image-chat read, `LCWChatDiscussionV2.jsx:209`).
 */
interface LocationBadges {
    val leaves: Flow<List<LocationBadgeLeaf>> get() = emptyFlow()

    fun readGallery(status: LocationStatus, pick: LocationPick) {}

    fun readRecord(recordId: String) {}

    companion object {
        val None: LocationBadges = object : LocationBadges {}

        /** The unit the service files each status list under. */
        fun unitOf(status: LocationStatus): String = when (status) {
            LocationStatus.Selected -> "location_select_label"
            LocationStatus.Shortlisted -> "location_shortlist_label"
            LocationStatus.Published -> "location_publish_label"
        }

        fun statusOf(unit: String): LocationStatus? = LocationStatus.entries.firstOrNull { unitOf(it) == unit }

        const val TOOL = "location_tool_label"
    }
}

/**
 * The leaves cut the way the screen asks: a status tab, a folder under the
 * current grouping, one gallery, one record. Every count is a sum of leaves
 * — the same rows counted at coarser and finer grain, never added twice.
 */
data class LocationUnread(val leaves: List<LocationBadgeLeaf> = emptyList()) {

    fun status(status: LocationStatus): Int = leaves.filter { it.status == status }.sumOf { it.unread }

    /** A folder tile: every leaf of the status whose grouping key is the folder's. */
    fun folder(status: LocationStatus, by: GroupBy, folder: LocationFolder): Int =
        leaves.filter { it.status == status && it.key(by) == folder.key }.sumOf { it.unread }

    fun pick(status: LocationStatus, pick: LocationPick): Int =
        leaves.filter { it.status == status && it.matches(pick) }.sumOf { it.unread }

    fun record(recordId: String): Int =
        if (recordId.isBlank()) 0 else leaves.filter { it.recordId == recordId }.sumOf { it.unread }

    /**
     * The scenes a place's rows are filed under that no gallery of the place
     * lists any more — rows written before the record had its scene, or
     * under a scene since edited away. Live data has them (three of a
     * production's location rows sat under a blank scene while every record
     * of the place read `99ee`), and no client's gallery would ever read
     * them; the first gallery of the place opened reads them here.
     */
    /**
     * The leaves of a list filed under a place no folder shows any more — a
     * location since deleted or renamed. Nothing on screen can open them, so
     * the loaded folder grid reads them.
     */
    fun orphans(status: LocationStatus, listedLocations: Collection<String>): List<LocationBadgeLeaf> {
        val listed = listedLocations.map { it.trim() }.toSet()
        return leaves.filter { it.status == status && it.location.trim() !in listed }
    }

    fun strayScenes(status: LocationStatus, location: String, listedScenes: Collection<String>): Set<String> {
        val listed = listedScenes.map { it.trim() }.toSet()
        return leaves
            .filter { it.status == status && it.location.trim() == location.trim() && it.scene.trim() !in listed }
            .map { it.scene.trim() }
            .toSet()
    }

    private fun LocationBadgeLeaf.key(by: GroupBy): String = when (by) {
        GroupBy.LocationName -> location
        GroupBy.SceneNo -> scene
        GroupBy.EpisodeNo -> episode
    }.trim()

    /** A pick without an episode is every episode's — the gallery lists them all. */
    private fun LocationBadgeLeaf.matches(pick: LocationPick): Boolean =
        location.trim() == pick.location.trim() && scene.trim() == pick.scene.trim() &&
            (pick.episode.isBlank() || episode.trim() == pick.episode.trim())

    companion object {
        val None = LocationUnread()
    }
}
