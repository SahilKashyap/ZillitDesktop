package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The characters group's rules — the web's `screens/characters/characterUtils.js`
 * and `sections.js`.
 *
 * These are the functions the screens would otherwise each re-derive: which
 * scenes a character is in, how story days and shooting days group, and what a
 * day label shortens to. They are here rather than in the screens because the
 * service counts them the same way on the character dashboard, so a mismatch
 * between a tile's count and the list it opens would be a visible bug.
 */
object Characters {

    /** The two kinds of character the service stores. */
    val TYPES: List<String> = listOf("principal", "background")

    val GENDERS: List<String> = listOf("female", "male", "nonbinary")

    /** A story day's kind, in the web's order. */
    val STORY_TYPES: List<String> = listOf("normal", "flashback", "flashforward", "dream", "fantasy")

    /**
     * The time spans a story day offers.
     *
     * ⚠ The service stores the TRANSLATED LABEL here, not the key: the web's
     * picker builds `{ value: label, label }`, so what is saved is the words
     * the script supervisor saw. Transcribed rather than renamed, because a
     * record written on the web has to read on the desktop.
     */
    val TIME_SPANS: List<String> = listOf(
        "continuous",
        "moments_later",
        "later",
        "morning",
        "midday",
        "afternoon",
        "evening",
        "night",
        "dawn",
        "dusk",
        "same_time",
        "next_day",
        "days_later",
        "weeks_later",
    )

    /** An actor's measurements, in the order the form lists them. */
    val MEASUREMENTS: List<String> = listOf(
        "height",
        "weight",
        "chest",
        "waist",
        "hips",
        "inseam",
        "neck",
        "sleeve",
        "shoe_size",
        "hat_size",
        "glove_size",
        "dress_size",
    )

    /** An actor's distinguishing marks: each a list of `{ location, description }`. */
    val MARKS: List<String> = listOf("tattoos", "piercings", "scars_marks")

    /** The day-key of a scene that no story day claims, in groups and in a URL. */
    const val UNASSIGNED: String = "unassigned"

    /**
     * A character's kind.
     *
     * Records the service wrote before `character_type` existed carry only
     * `importance`, so a background player from that era still reads as one.
     */
    fun typeOf(character: Rec?): String = character?.str("character_type")?.takeIf { it.isNotBlank() }
        ?: if (character?.str("importance") == "background") "background" else "principal"

    /**
     * The character's photo: the one marked primary, else the first.
     *
     * A stored value is this tool's own storage key, not an address — see
     * `ui/Photos.kt`, which signs it.
     */
    fun primaryPhoto(character: Rec?): String {
        val photos = character?.recs("photos").orEmpty()
        return photos.firstOrNull { it.bool("is_primary") }?.str("url")
            ?: photos.firstOrNull()?.str("url").orEmpty()
    }

    /** Every name the script may call this character: its own, then its aliases. */
    fun namesOf(character: Rec?): List<String> = character?.let {
        (listOf(it.str("name")) + it.strings("aliases")).filter { name -> name.isNotBlank() }
    }.orEmpty()

    /**
     * The character's scenes: the ones whose script NAMES them (by name or by
     * alias), plus the ones linked by hand (`scene_ids`), in scene-number
     * order.
     *
     * Both halves matter and the web is explicit about why: the script's own
     * `characters` array is what an import fills, while `scene_ids` is what a
     * script supervisor adds when the script spells a name differently than
     * the character record does. The service counts them the same way for the
     * character dashboard's `scenes` tile.
     */
    fun scenesFor(character: Rec?, scenes: List<Rec>): List<Rec> {
        val names = namesOf(character)
        val linked = character?.strings("scene_ids")?.toSet().orEmpty()
        return scenes
            .filter { scene -> scene.strings("characters").any { it in names } || scene.id in linked }
            .sortedWith(byNumber)
    }

    /**
     * Scene order by number, the way a reader expects: 2 before 10, and "12A"
     * right after "12".
     *
     * The web gets this from `localeCompare(…, { numeric: true })`; here the
     * number and the suffix are compared apart, which is the same order for
     * every scene number this service stores.
     */
    val byNumber: Comparator<Rec> = compareBy(
        { it.str("scene_number").takeWhile { c -> c.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE },
        { it.str("scene_number") },
    )

    /**
     * The story day a scene happens on: the scene's own label, else the story
     * day that lists its number, else [UNASSIGNED].
     */
    fun scriptDayOf(scene: Rec, storyDays: List<Rec>): String =
        scene.str("story_day").takeIf { it.isNotBlank() }
            ?: storyDays.firstOrNull { day -> scene.str("scene_number") in day.strings("scene_numbers") }
                ?.str("label")?.takeIf { it.isNotBlank() }
            ?: UNASSIGNED

    /**
     * "Story Day 3" → "D3", as the script supervisor writes it on a slate.
     *
     * The leading "story" is optional because the service holds both spellings.
     */
    fun shortDay(label: String?): String =
        STORY_DAY_PREFIX.replace(label.orEmpty(), "D")

    /** A scene's shooting day, or 0 for one nothing has scheduled yet. */
    fun shootingDayOf(scene: Rec): Int = scene.int("shooting_day").takeIf { it > 0 } ?: 0

    /**
     * Scenes by story day, in NARRATIVE order — not the order the scenes are
     * numbered in.
     *
     * Three kinds of label meet here and the sort is what keeps them apart: a
     * label a story-day record owns sorts by that record's `order`; a label
     * only scenes carry (nobody made the record) sorts after all of those, by
     * the number in its own text; and [UNASSIGNED] sorts last of all. The web
     * does this with 999 / 500+n / 9999 sentinels, kept here so the two orders
     * match scene for scene.
     */
    fun groupScriptDays(scenes: List<Rec>, storyDays: List<Rec>): List<ScriptDayGroup> {
        val byLabel = LinkedHashMap<String, MutableList<Rec>>()
        scenes.forEach { scene ->
            byLabel.getOrPut(scriptDayOf(scene, storyDays)) { mutableListOf() }.add(scene)
        }
        return byLabel.entries
            .map { (label, list) ->
                ScriptDayGroup(label, list, storyDays.firstOrNull { it.str("label") == label })
            }
            .sortedBy { group -> dayOrder(group.label, group.record) }
    }

    private fun dayOrder(label: String, record: Rec?): Int {
        // A record's own place in the story, which the writer set.
        if (record != null) return if (record.has("order")) record.int("order") else NO_ORDER
        // A label only scenes carry: after every record, by its own number.
        val digits = label.filter { it.isDigit() }.toIntOrNull() ?: return UNNUMBERED
        return LOOSE_LABEL + digits
    }

    /** Scenes by shooting day; the unscheduled ones (day 0) last. */
    fun groupShootingDays(scenes: List<Rec>): List<ShootingDayGroup> {
        val byDay = LinkedHashMap<Int, MutableList<Rec>>()
        scenes.forEach { scene -> byDay.getOrPut(shootingDayOf(scene)) { mutableListOf() }.add(scene) }
        return byDay.entries
            .map { (day, list) -> ShootingDayGroup(day, list) }
            .sortedBy { if (it.day == 0) UNNUMBERED else it.day }
    }

    private val STORY_DAY_PREFIX = Regex("^(story\\s*)?day\\s*", RegexOption.IGNORE_CASE)

    /** A story-day record with no `order` of its own still sorts before loose labels. */
    private const val NO_ORDER = 999

    /** Where labels that no record owns begin, so they follow every record. */
    private const val LOOSE_LABEL = 500

    /** [UNASSIGNED], and any label with no number in it: last. */
    private const val UNNUMBERED = 9999
}

/** One story day and the scenes that happen on it; [record] is null for a label no record owns. */
data class ScriptDayGroup(val label: String, val scenes: List<Rec>, val record: Rec?)

/** One shooting day and its scenes; day 0 is "not scheduled". */
data class ShootingDayGroup(val day: Int, val scenes: List<Rec>)

/**
 * A character's sub-screens, in the order the character page lists them — the
 * web's `sections.js`.
 *
 * [route] is the web's own URL segment, [count] the key the character
 * dashboard answers that section's tally under, and [dept] the look department
 * for the three that show looks.
 */
enum class CharacterSection(
    val route: String,
    val count: String,
    val group: SectionGroup,
    val dept: String? = null,
) {
    Scenes("scenes", "scenes", SectionGroup.Appearances),
    ScriptDays("script-days", "script_days", SectionGroup.Appearances),
    ShootingDays("shooting-days", "shooting_days", SectionGroup.Appearances),
    Costume("costume", "costume_looks", SectionGroup.Looks, dept = "costume"),
    Hair("hair", "hair_looks", SectionGroup.Looks, dept = "hair"),
    Makeup("makeup", "makeup_looks", SectionGroup.Looks, dept = "makeup"),
    Wardrobe("wardrobe", "wardrobe_items", SectionGroup.Warehouse),
    Props("props", "props_items", SectionGroup.Warehouse),
    ;

    /** The module text key for this section's name — the web's `sectionKey`. */
    val labelKey: String get() = "section_${route.replace('-', '_')}"

    /** The tile's name and help on the character page.  */
    val tileKey: String get() = "tile_${route.replace('-', '_')}"

    companion object {
        fun of(route: String?): CharacterSection? = entries.firstOrNull { it.route == route }
    }
}

/** How the character page bands its sections. */
enum class SectionGroup { Appearances, Looks, Warehouse }
