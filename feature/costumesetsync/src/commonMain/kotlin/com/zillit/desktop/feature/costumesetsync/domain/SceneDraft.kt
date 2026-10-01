package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * The scene breakdown's edit model — the web's `lib/sceneDraft.js`, minus the
 * saving (see `data/SceneDraftPersist.kt`).
 *
 * A "draft" is one scene's editable fields flattened for a table row: the
 * service's `script_day` ("Day 1") is split into a prefix and a number so each
 * gets its own control, `shoot_date` (epoch ms) becomes a `YYYY-MM-DD` string
 * for the date field, and [SceneDraft.cast] collects the per-character edits
 * that belong to the CHARACTER record rather than to the scene.
 *
 * Nothing here touches Compose or the network, so all of it is unit-tested.
 */

/** The key the not-yet-created row is held under, in the same map as real scene ids. */
const val NEW_KEY = "new"
val DAY_PREFIXES = listOf("Day", "Night")
val INT_EXT_FALLBACK = listOf("INT", "EXT", "INT/EXT")

/** The largest cast number the service stores (a 32-bit integer). */
private const val CAST_NUMBER_MAX = 2147483647L

/** Year, month and day: the three parts of a date key. */
private const val DATE_PARTS = 3

/** The save-order loop runs at most this many passes per draft, plus slack, so it can never spin. */
private const val GUARD_FACTOR = 2
private const val GUARD_SLACK = 4

// -- dates -----------------------------------------------------------------

/**
 * Epoch ms → `YYYY-MM-DD` in the viewer's own timezone, which is what a date
 * field round-trips. The service writes 0, not null, for a date that has not
 * been set, so 0 is "no date" rather than 1 Jan 1970.
 */
fun dateKey(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (ms == null || ms == 0L) return ""
    val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    return "${d.year}-${d.monthNumber.toString().padStart(2, '0')}-${d.dayOfMonth.toString().padStart(2, '0')}"
}

/** `YYYY-MM-DD` → epoch ms at local midnight. An empty or unreadable key clears the date (0). */
fun dateMs(key: String?, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val parts = key.orEmpty().split('-').map { it.toIntOrNull() }
    if (parts.size < DATE_PARTS || parts.any { it == null || it == 0 }) return 0L
    return runCatching { LocalDate(
        parts[0] ?: 0,
        parts[1] ?: 0,
        parts[2] ?: 0,
    ).atStartOfDayIn(zone).toEpochMilliseconds() }
        .getOrDefault(0L)
}

/** Today as `YYYY-MM-DD`, for the Today / Upcoming filters. */
fun todayKey(now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String = dateKey(now, zone)

// -- script day --------------------------------------------------------------

/** A story day split for its two controls. */
data class DayParts(val prefix: String, val number: String)

private val DAY_WORD = Regex("^(day|night)\\s*[-.:]?\\s*(.*)$", RegexOption.IGNORE_CASE)
private val DAY_SHORT = Regex("^([dn])\\s*(\\d+[a-z]?)$", RegexOption.IGNORE_CASE)

/**
 * `"Day 3"` / `"D3"` / `"N12"` → prefix and number. Anything else keeps a
 * blank prefix ("—") with the text in the number box, so an imported
 * "Flashback" is never re-labelled "Day Flashback" on save.
 */
fun parseScriptDay(value: String?): DayParts {
    val raw = value.orEmpty().trim()
    DAY_WORD.find(raw)?.let { m ->
        return DayParts(
            if (m.groupValues[1].startsWith("d", ignoreCase = true)) "Day" else "Night",
            m.groupValues[2].trim(),
        )
    }
    DAY_SHORT.find(raw)?.let { m ->
        return DayParts(if (m.groupValues[1].startsWith("d", ignoreCase = true)) "Day" else "Night", m.groupValues[2])
    }
    return DayParts("", raw)
}

/** The two controls back into one `script_day` string. A prefix with no number is blank: "Day" alone says nothing. */
fun joinScriptDay(prefix: String?, number: String?): String {
    val n = number.orEmpty().trim()
    if (n.isEmpty()) return ""
    return listOf(prefix.orEmpty().trim(), n).filter { it.isNotEmpty() }.joinToString(" ")
}

// -- the draft ---------------------------------------------------------------

/** What the user typed for one character's own record: only what differs from the character now. */
data class CastEdit(val castNumber: String? = null, val actorId: String? = null) {
    val isEmpty: Boolean get() = castNumber == null && actorId == null
}

data class SceneDraft(
    val number: String = "",
    val episode: String = "",
    val dayPrefix: String = "Day",
    val dayN: String = "",
    val intExt: String = "INT",
    val location: String = "",
    val synopsis: String = "",
    val shootDate: String = "",
    val principals: List<String> = emptyList(),
    val cast: Map<String, CastEdit> = emptyMap(),
)

fun emptyDraft(): SceneDraft = SceneDraft()

fun toDraft(scene: Rec?): SceneDraft {
    val day = parseScriptDay(scene?.str("script_day"))
    return SceneDraft(
        number = scene?.str("number").orEmpty(),
        episode = scene?.str("episode").orEmpty(),
        dayPrefix = day.prefix,
        dayN = day.number,
        intExt = scene?.str("int_ext").orEmpty(),
        location = scene?.str("location").orEmpty(),
        synopsis = scene?.str("synopsis").orEmpty(),
        shootDate = dateKey(scene?.long("shoot_date")),
        principals = scene?.recs("characters").orEmpty().map { it.str("character_id") },
        cast = emptyMap(),
    )
}

/** `DAY` → `Day`, the way the parser writes the time into a scene's name. */
internal fun timeWord(v: String?): String = v?.takeIf { it.isNotEmpty() }
    ?.let { it.take(1) + it.drop(1).lowercase() }
    .orEmpty()

/**
 * The parser names a scene "Location - Time", so an edited location renames it
 * the same way rather than keeping the old one. No location keeps the name.
 */
fun sceneName(d: SceneDraft, original: Rec?): String {
    val loc = d.location.trim()
    if (loc.isEmpty()) return original?.str("name").orEmpty().trim()
    return listOf(loc, timeWord(original?.str("time_of_day"))).filter { it.isNotEmpty() }.joinToString(" - ")
}

/** The scene's own fields as the service names them: strings, and `shoot_date` as a Long. */
fun sceneBody(d: SceneDraft, original: Rec?): Map<String, Any> = linkedMapOf(
    "number" to d.number.trim(),
    "episode" to d.episode,
    "script_day" to joinScriptDay(d.dayPrefix, d.dayN),
    "int_ext" to d.intExt,
    "location" to d.location,
    "name" to sceneName(d, original),
    "synopsis" to d.synopsis,
    "shoot_date" to dateMs(d.shootDate),
)

/** Only the keys that actually differ, so an untouched field is never written. */
fun diffBody(next: Map<String, Any>, prev: Map<String, Any>): Map<String, Any> =
    next.filter { (k, v) -> v != prev[k] }

// -- validation --------------------------------------------------------------

enum class CastProblem { NotWhole, TooBig }

private val WHOLE = Regex("^\\d+$")

/**
 * A cast number is a whole number or blank. Checked here rather than with a
 * numeric field, which would hand back an empty string for anything it cannot
 * parse — that reads as "clear this cast number".
 */
fun castNumberProblem(raw: String?): CastProblem? {
    val v = raw.orEmpty().trim()
    if (v.isEmpty()) return null
    if (!WHOLE.matches(v)) return CastProblem.NotWhole
    val n = v.toLongOrNull() ?: return CastProblem.TooBig
    return if (n > CAST_NUMBER_MAX) CastProblem.TooBig else null
}

// -- save ordering -----------------------------------------------------------

/** One write in a "Save all" run; [tempNumber] parks the row on a temporary scene number. */
data class SaveStep(val key: String, val tempNumber: String? = null)

/**
 * Order "Save all" so renumbering never trips the service's unique scene number:
 * a row whose new number is still held by another edited row is saved after that
 * row. A cycle (swapping 5 and 6) is broken by first parking one row on a
 * temporary number, which is emitted as its own step.
 *
 * [sceneNumbers] maps a scene id to the number it holds now.
 */
fun planSaveOrder(
    keys: List<String>,
    drafts: Map<String, SceneDraft>,
    sceneNumbers: Map<String, String>,
): List<SaveStep> {
    val pending = LinkedHashSet(keys)
    val holder = HashMap<String, String>() // number currently in the database -> the draft holding it
    keys.forEach { k -> sceneNumbers[k]?.let { holder[it] = k } }
    fun blockedBy(k: String): String? {
        val h = holder[drafts[k]?.number.orEmpty().trim()]
        return h?.takeIf { it != k && it in pending }
    }
    val steps = ArrayList<SaveStep>()
    var guard = keys.size * GUARD_FACTOR + GUARD_SLACK
    while (pending.isNotEmpty() && guard-- > 0) {
        val ready = pending.filter { blockedBy(it) == null }
        if (ready.isNotEmpty()) {
            ready.forEach { k ->
                pending.remove(k)
                holder.remove(sceneNumbers[k].orEmpty())
                steps.add(SaveStep(k))
            }
            continue
        }
        // Every pending row waits on another pending row: free one number, then carry on.
        val k = pending.first()
        val current = sceneNumbers[k].orEmpty()
        holder.remove(current)
        steps.add(SaveStep(k, tempNumber = "$current~tmp${k.take(TEMP_KEY_CHARS)}"))
    }
    return steps
}

private const val TEMP_KEY_CHARS = 6
