package com.zillit.desktop.feature.location.domain

/**
 * The folder tree, computed from the flat `location-info` list exactly as
 * the web's `getUniqueLocations` / `getUniqueSceneNumbers` /
 * `getUniqueEpisodeforLocation` do — the server never groups.
 *
 * "Not Assigned" is a blank key: the web treats `''`, `null` and the literal
 * `not assigned` alike, and prints the label for all three.
 */
object Folders {

    fun group(rows: List<LocationInfo>, by: GroupBy): List<LocationFolder> = when (by) {
        GroupBy.LocationName -> byLocation(rows)
        GroupBy.SceneNo -> byScene(rows)
        GroupBy.EpisodeNo -> byEpisode(rows)
    }

    /** Sorted by lower-cased name, as the web sorts. */
    private fun byLocation(rows: List<LocationInfo>): List<LocationFolder> =
        rows.groupBy { it.location.trim() }.map { (name, group) ->
            LocationFolder(
                key = name,
                locations = listOf(name),
                sceneNumbers = group.flatMap { it.sceneNumbers }.map(::normaliseScene).distinct(),
                episodes = group.flatMap { it.episodes }.filter { it.isNotBlank() }.distinct(),
                cities = group.flatMap { it.cities }.filter { it.isNotBlank() }.distinct(),
                lastUpdateMs = group.maxOf { it.lastUpdateMs },
            )
        }.sortedBy { it.key.lowercase() }

    /**
     * Sorted NUMERICALLY, as the web does — non-numeric scenes fall to the
     * end together rather than collapsing into one `NaN` bucket.
     */
    private fun byScene(rows: List<LocationInfo>): List<LocationFolder> =
        rows.flatMap { row -> row.sceneNumbers.ifEmpty { listOf("") }.map { normaliseScene(it) to row } }
            .groupBy({ it.first }, { it.second })
            .map { (scene, group) ->
                LocationFolder(
                    key = scene,
                    locations = group.map { it.location }.filter { it.isNotBlank() }.distinct(),
                    sceneNumbers = listOf(scene),
                    episodes = group.flatMap { it.episodes }.filter { it.isNotBlank() }.distinct(),
                    cities = group.flatMap { it.cities }.filter { it.isNotBlank() }.distinct(),
                    lastUpdateMs = group.maxOf { it.lastUpdateMs },
                )
            }
            .sortedWith(compareBy<LocationFolder> { it.key.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.key })

    private fun byEpisode(rows: List<LocationInfo>): List<LocationFolder> =
        rows.flatMap { row -> row.episodes.ifEmpty { listOf("") }.map { it.trim() to row } }
            .groupBy({ it.first }, { it.second })
            .map { (episode, group) ->
                LocationFolder(
                    key = episode,
                    locations = group.map { it.location }.filter { it.isNotBlank() }.distinct(),
                    sceneNumbers = group.flatMap { it.sceneNumbers }.map(::normaliseScene).distinct(),
                    episodes = listOf(episode),
                    cities = group.flatMap { it.cities }.filter { it.isNotBlank() }.distinct(),
                    lastUpdateMs = group.maxOf { it.lastUpdateMs },
                )
            }
            .sortedWith(compareBy<LocationFolder> { it.key.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.key })

    /**
     * The tiles inside a folder — the web's ListModal / CommonEpisode level.
     * By location: one per scene; by scene: one per location; by episode:
     * one per (location, scene) pair the episode's rows hold, flattened.
     */
    fun picks(folder: LocationFolder, by: GroupBy, rows: List<LocationInfo>): List<LocationPick> = when (by) {
        GroupBy.LocationName -> folder.sceneNumbers.ifEmpty { listOf("") }.map { LocationPick(folder.key, it) }
        GroupBy.SceneNo -> folder.locations.ifEmpty { listOf("") }.map { LocationPick(it, folder.key) }
        GroupBy.EpisodeNo -> rows
            .filter { row -> folder.key in row.episodes.map { it.trim() }.ifEmpty { listOf("") } }
            .flatMap { row ->
                row.sceneNumbers.map(::normaliseScene).ifEmpty { listOf("") }
                    .map { LocationPick(row.location.trim(), it, folder.key) }
            }
            .distinct()
            .sortedWith(
                compareBy<LocationPick> { it.location.lowercase() }
                    .thenBy { it.scene.toDoubleOrNull() ?: Double.MAX_VALUE }
                    .thenBy { it.scene },
            )
    }

    /** The web's `normalizeSceneNo`: blank, null and "not assigned" are all the empty scene. */
    fun normaliseScene(scene: String?): String {
        val s = scene?.trim().orEmpty()
        return if (s.equals("not assigned", ignoreCase = true)) "" else s
    }

    /** The web's `searchByLocationList`: any field contains the needle (cities too — a desktop nicety). */
    fun search(folders: List<LocationFolder>, query: String): List<LocationFolder> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return folders
        return folders.filter { f ->
            f.key.lowercase().contains(needle) ||
                f.locations.any { it.lowercase().contains(needle) } ||
                f.sceneNumbers.any { it.lowercase().contains(needle) } ||
                f.episodes.any { it.lowercase().contains(needle) } ||
                f.cities.any { it.lowercase().contains(needle) }
        }
    }
}
