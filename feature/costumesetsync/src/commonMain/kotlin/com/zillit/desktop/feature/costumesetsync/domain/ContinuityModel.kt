package com.zillit.desktop.feature.costumesetsync.domain

/** One reason a character is not ready: `no_change`, `no_pieces`, or a `piece` in a bad status. */
data class Blocker(val key: String, val name: String = "", val status: String = "")

/** How ready one character is for one scene (the web's `characterReadiness`). */
data class Readiness(val level: String, val ready: Int, val total: Int, val blockers: List<Blocker>)

/**
 * The continuity screens' pure logic — the web's `lib/readiness.js` plus the
 * day-grouping the on-set board and the continuity book do inline. Mirrors the
 * service's own readiness rules, so a chip reads the same here as the scene does.
 */
object ContinuityModel {
    private val STATUS_TO_LEVEL = mapOf(
        "AVAILABLE" to "READY", "ISSUED" to "READY", "ON_SET" to "READY", "CLEANING" to "CLEANING",
        "ALTERATION" to "ALTERATION", "DAMAGED" to "DAMAGED", "MISSING" to "MISSING",
        "RETURNED_TO_VENDOR" to "MISSING", "RETIRED" to "MISSING",
    )
    private val SEVERITY = listOf("MISSING", "DAMAGED", "ALTERATION", "CLEANING", "NOT_ASSIGNED", "ISSUED", "READY")

    fun itemLevel(status: String?): String = STATUS_TO_LEVEL[status] ?: "READY"

    fun worst(levels: List<String>): String =
        levels.fold("READY") { acc, level -> if (SEVERITY.indexOf(level) < SEVERITY.indexOf(acc)) level else acc }

    /** [sc] is a scene's `characters[]` entry: `{ change: { items: [{ costume }] } }`. */
    fun readiness(sc: Rec): Readiness {
        val change = sc.rec("change") ?: return Readiness("NOT_ASSIGNED", 0, 0, listOf(Blocker("no_change")))
        val items = change.recs("items")
        if (items.isEmpty()) return Readiness("NOT_ASSIGNED", 0, 0, listOf(Blocker("no_pieces")))
        val levels = items.map { itemLevel(it.rec("costume")?.str("status")) }
        val blockers = items.filter { itemLevel(it.rec("costume")?.str("status")) != "READY" }.map { item ->
            val costume = item.rec("costume")
            Blocker(
                "piece",
                costume?.str("name")?.ifBlank { null } ?: costume?.str("asset_number").orEmpty(),
                costume?.str("status").orEmpty(),
            )
        }
        return Readiness(worst(levels), levels.count { it == "READY" }, items.size, blockers)
    }

    /** The scenes on [day] (a `YYYY-MM-DD` key), omitted ones left off. */
    fun onDay(scenes: List<Rec>, day: String): List<Rec> =
        scenes.filter { DayKeys.of(it.long("shoot_date")) == day && it.str("status") != "OMITTED" }

    /** Every character row on a day's board, with its readiness. */
    fun rowsOf(dayScenes: List<Rec>): List<Pair<Rec, Readiness>> =
        dayScenes.flatMap { s -> s.recs("characters").map { it to readiness(it) } }

    fun locations(dayScenes: List<Rec>): List<String> =
        dayScenes.map { it.str("location").trim() }.filter { it.isNotEmpty() }.distinct()

    /** One shoot day in the book's History tab. */
    data class ShootDay(val day: String, val scenes: List<Rec>, val takes: Int)

    /**
     * Every shoot day the schedule has put behind us (or that has a take on
     * it), newest first — a day still ahead with nothing shot on it is not history.
     */
    fun shootDays(scenes: List<Rec>, records: List<Rec>, today: String): List<ShootDay> {
        val dayOfScene = scenes.associate { it.id to DayKeys.of(it.long("shoot_date")) }
        val takes = HashMap<String, Int>()
        records.forEach { r ->
            dayOfScene[r.str("scene_id")]?.takeIf { it.isNotEmpty() }?.let { takes[it] = (takes[it] ?: 0) + 1 }
        }
        val byDay = LinkedHashMap<String, MutableList<Rec>>()
        scenes.forEach { s ->
            val d = dayOfScene[s.id].orEmpty()
            if (d.isEmpty() || s.str("status") == "OMITTED") return@forEach
            if (d >= today && d !in takes) return@forEach
            byDay.getOrPut(d) { mutableListOf() }.add(s)
        }
        return byDay.map { (d, list) -> ShootDay(d, list, takes[d] ?: 0) }.sortedByDescending { it.day }
    }

    /** Prep opens on today, or — when nothing is scheduled today — the next day that is. */
    fun nextPrepDay(scenes: List<Rec>, today: String): String {
        val dates = scenes
            .filter { it.str("status") != "OMITTED" }
            .map { DayKeys.of(it.long("shoot_date")) }
            .filter { it.isNotEmpty() }
            .sorted()
        return if (today in dates) today else dates.firstOrNull { it > today } ?: today
    }

    /** A day's takes for one scene and character, in take order. */
    fun takesOf(records: List<Rec>, sceneId: String): List<Rec> =
        records.filter { it.str("scene_id") == sceneId }.sortedBy { it.long("take_number") }

    /** The wear details and accessories a new take starts from: the last take's, else the defaults. */
    data class TakeFill(
        val takeNumber: String,
        val details: List<Pair<String, String>>,
        val accessories: List<Pair<String, Boolean>>,
    )

    val DEFAULT_DETAILS = listOf("Shirt", "Sleeves", "Collar", "Trousers", "Hair", "Accessories")

    /** [sc] is the open scene's character entry (its change's accessory / jewellery pieces seed a first take). */
    fun fill(last: Rec?, sc: Rec?): TakeFill {
        val details = last?.rec("details")?.let { d -> d.keys.map { it to d.str(it) } } ?: DEFAULT_DETAILS.map {
            it to ""
        }
        val accessories = if (last != null) {
            last.recs("accessories").map {
                it.str("name") to (it.json["present"]?.let { _ -> it.bool("present") } ?: true)
            }
        } else {
            sc?.rec("change")?.recs("items").orEmpty()
                .mapNotNull { it.rec("costume") }
                .filter { it.str("category") in setOf("ACCESSORY", "JEWELLERY") }
                .map { it.str("name") to true }
        }
        return TakeFill(((last?.long("take_number") ?: 0L) + 1).toString(), details, accessories)
    }
}
