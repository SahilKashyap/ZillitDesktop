package com.zillit.desktop.feature.costumesetsync.domain

/** The shoot-date filters, in the order the chips show. */
val WHEN_KEYS = listOf("today", "upcoming", "scheduled", "all")

/** What the Scene Breakdown's filter bar holds. [characterId] empty = everyone. */
data class SceneFilters(
    val shoot: String = "all",
    val revision: String = "",
    val episode: String = "",
    val query: String = "",
    val characterId: String = "",
)

/**
 * The listed scenes — the web's `list` memo. A row being edited ([pinned]) stays
 * visible whatever the filters say, so a filter change can never hide — and
 * silently lose — an edit in progress.
 */
fun filterScenes(
    scenes: List<Rec>,
    filters: SceneFilters,
    charById: Map<String, Rec>,
    today: String,
    pinned: (Rec) -> Boolean,
): List<Rec> {
    var items = scenes
    if (filters.revision.isNotEmpty()) items = items.filter { pinned(it) || it.str("revision") == filters.revision }
    if (filters.episode.isNotEmpty()) items = items.filter { pinned(it) || it.str("episode").trim() == filters.episode }
    when (filters.shoot) {
        "today" -> items = items.filter { pinned(it) || dateKey(it.long("shoot_date")) == today }
        "upcoming" -> items = items.filter { pinned(it) || (it.long("shoot_date") != 0L && dateKey(it.long("shoot_date")) >= today) }
        // "Scheduled" means the scene has a shoot date at all, past or future.
        "scheduled" -> items = items.filter { pinned(it) || it.long("shoot_date") != 0L }
    }
    if (filters.characterId.isNotEmpty()) {
        items = items.filter { s -> pinned(s) || s.recs("characters").any { it.str("character_id") == filters.characterId } }
    }
    val needle = filters.query.trim().lowercase()
    if (needle.isNotEmpty()) items = items.filter { pinned(it) || sceneMatches(it, needle, charById) }
    return items
}

private fun sceneMatches(s: Rec, needle: String, charById: Map<String, Rec>): Boolean {
    val fields = mutableListOf(
        s.str("number"), s.str("episode"), s.str("name"), s.str("location"), s.str("synopsis"), s.str("script_day"), s.str("int_ext"),
    )
    s.recs("characters").forEach { c ->
        val full = charById[c.str("character_id")]
        val cast = c.rec("character")?.takeIf { it.has("cast_number") } ?: full
        fields += listOf(
            c.rec("character")?.str("name").orEmpty(),
            cast?.str("cast_number").orEmpty(),
            full?.rec("actor")?.str("name").orEmpty(),
            c.rec("change")?.str("name").orEmpty(),
        )
    }
    return fields.any { it.lowercase().contains(needle) }
}

/** A scene opened out to one of its characters, resolved for sorting and display. */
data class SceneLine(val sc: Rec, val name: String, val castNumber: Long?)

/**
 * Each listed scene opened out into its characters, in cast order. A scene with
 * nobody in it is one line with no character (`null`), so it still has a row.
 */
fun sceneLines(scene: Rec, charById: Map<String, Rec>, characterId: String): List<SceneLine?> {
    val cast = sortByCast(
        scene.recs("characters").map { sc ->
            val full = charById[sc.str("character_id")]
            val embedded = sc.rec("character")
            SceneLine(
                sc,
                embedded?.str("name")?.ifEmpty { null } ?: full?.str("name").orEmpty(),
                (embedded?.takeIf { it.has("cast_number") } ?: full?.takeIf { it.has("cast_number") })?.long("cast_number"),
            )
        },
        { it.castNumber },
        { it.name },
    ).filter { characterId.isEmpty() || it.sc.str("character_id") == characterId }
    return cast.ifEmpty { listOf(null) }
}

/** Every character that is in at least one scene, for the character filter: (id, name) by name. */
fun castOptions(scenes: List<Rec>): List<Pair<String, String>> {
    val byId = LinkedHashMap<String, String>()
    scenes.forEach { s -> s.recs("characters").forEach { c -> byId.getOrPut(c.str("character_id")) { c.rec("character")?.str("name").orEmpty() } } }
    return byId.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
}

fun revisionsOf(scenes: List<Rec>): List<String> = scenes.map { it.str("revision") }.filter { it.isNotEmpty() }.distinct().sorted()

fun latestRevisionOf(scenes: List<Rec>): String =
    scenes.filter { it.str("revision").isNotEmpty() }.maxByOrNull { it.long("revised_at") }?.str("revision").orEmpty()

fun locationsOf(scenes: List<Rec>): List<String> = scenes.map { it.str("location").trim() }.filter { it.isNotEmpty() }.distinct().sorted()

/** Episodes, sorted as numbers where they are ("2" before "10"). */
fun episodesOf(scenes: List<Rec>): List<String> =
    scenes.map { it.str("episode").trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(NATURAL)

private val NATURAL = Comparator<String> { a, b ->
    val x = a.toLongOrNull()
    val y = b.toLongOrNull()
    if (x != null && y != null) x.compareTo(y) else a.compareTo(b, ignoreCase = true)
}
