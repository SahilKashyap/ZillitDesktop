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
    if (parts.size < 3 || parts.any { it == null || it == 0 }) return 0L
    return runCatching { LocalDate(parts[0] ?: 0, parts[1] ?: 0, parts[2] ?: 0).atStartOfDayIn(zone).toEpochMilliseconds() }
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
        return DayParts(if (m.groupValues[1].startsWith("d", ignoreCase = true)) "Day" else "Night", m.groupValues[2].trim())
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
private fun timeWord(v: String?): String = v?.takeIf { it.isNotEmpty() }?.let { it.take(1) + it.drop(1).lowercase() }.orEmpty()

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
fun planSaveOrder(keys: List<String>, drafts: Map<String, SceneDraft>, sceneNumbers: Map<String, String>): List<SaveStep> {
    val pending = LinkedHashSet(keys)
    val holder = HashMap<String, String>() // number currently in the database -> the draft holding it
    keys.forEach { k -> sceneNumbers[k]?.let { holder[it] = k } }
    fun blockedBy(k: String): String? {
        val h = holder[drafts[k]?.number.orEmpty().trim()]
        return h?.takeIf { it != k && it in pending }
    }
    val steps = ArrayList<SaveStep>()
    var guard = keys.size * 2 + 4
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

// -- display helpers ---------------------------------------------------------

/** `"3. Priya"` when the character has a cast number, otherwise just the name. */
fun castLabel(c: Rec?): String {
    if (c == null) return ""
    val number = c.str("cast_number")
    return if (c.has("cast_number") && number.isNotEmpty()) "$number. ${c.str("name")}" else c.str("name")
}

private val CAST_ORDER = Comparator<Pair<Long?, String>> { a, b ->
    val byNumber = (a.first ?: Long.MAX_VALUE).compareTo(b.first ?: Long.MAX_VALUE)
    if (byNumber != 0) byNumber else a.second.compareTo(b.second, ignoreCase = true)
}

/** Cast-number order; characters without a number come last, alphabetically. */
fun <T> sortByCast(list: List<T>, number: (T) -> Long?, name: (T) -> String): List<T> =
    list.sortedWith { a, b -> CAST_ORDER.compare(number(a) to name(a), number(b) to name(b)) }

fun sortByCast(list: List<Rec>): List<Rec> =
    sortByCast(list, { c -> c.long("cast_number").takeIf { c.has("cast_number") } }, { it.str("name") })

fun initials(name: String?): String =
    name.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }

/** `INT. LIVING ROOM` — the script-location column. */
fun scriptLoc(s: Rec): String =
    listOf(s.str("int_ext").takeIf { it.isNotEmpty() }?.let { "$it." }.orEmpty(), s.str("location").uppercase())
        .filter { it.isNotEmpty() }.joinToString(" ")

fun truncate(s: String?, n: Int = TRUNCATE_AT): String = when {
    s.isNullOrEmpty() -> ""
    s.length > n -> s.take(n - 1).trimEnd() + "…"
    else -> s
}

private const val TRUNCATE_AT = 60

/** A text/tooltip pair for a table cell listing several characters. */
data class CellText(val text: String, val title: String)

/** A scene character, resolved against the full character list. */
data class CastRow(
    val characterId: String,
    val name: String,
    val castNumber: Long?,
    val actor: String?,
    val change: Rec?,
)

/** A scene's characters resolved in cast order, whatever order the service sent them. */
fun resolveCast(chars: List<Rec>, byId: Map<String, Rec>): List<CastRow> = sortByCast(
    chars.map { c ->
        val full = byId[c.str("character_id")]
        val embedded = c.rec("character")
        CastRow(
            characterId = c.str("character_id"),
            name = embedded?.str("name")?.ifEmpty { null } ?: full?.str("name").orEmpty(),
            castNumber = (embedded?.takeIf { it.has("cast_number") } ?: full?.takeIf { it.has("cast_number") })?.long("cast_number"),
            actor = full?.rec("actor")?.str("name")?.ifEmpty { null },
            change = c.rec("change"),
        )
    },
    { it.castNumber },
    { it.name },
)

/** Resolved straight from character records (a draft's principals, which are ids). */
fun castRowsOfIds(ids: List<String>, byId: Map<String, Rec>): List<CastRow> = sortByCast(
    ids.mapNotNull { byId[it] }.map { c ->
        CastRow(c.id, c.str("name"), c.long("cast_number").takeIf { c.has("cast_number") }, c.rec("actor")?.str("name")?.ifEmpty { null }, null)
    },
    { it.castNumber },
    { it.name },
)

private fun CastRow.label(): String = if (castNumber != null) "$castNumber. $name" else name

fun namesOf(rows: List<CastRow>) = CellText(rows.joinToString(", ") { it.name }, rows.joinToString("\n") { it.label() })

/** Their cast numbers, in the same order. A character without one is left out. */
fun castNumbersOf(rows: List<CastRow>): CellText {
    val numbered = rows.filter { it.castNumber != null }
    return CellText(numbered.joinToString(", ") { it.castNumber.toString() }, numbered.joinToString("\n") { it.label() })
}

/** The actors playing them, in the same order. A part with nobody cast is left out. */
fun castMembersOf(rows: List<CastRow>): CellText {
    val cast = rows.filter { !it.actor.isNullOrEmpty() }
    return CellText(cast.joinToString(", ") { it.actor.orEmpty() }, cast.joinToString("\n") { "${it.actor} · ${it.name}" })
}

/** The change number each one wears ("#13, #8"). A character with no change yet is left out. */
fun changesOf(rows: List<CastRow>): CellText {
    val worn = rows.filter { it.change != null }
    return CellText(
        worn.joinToString(", ") { "#${it.change?.str("change_number")}" },
        worn.joinToString("\n") { "${it.name} · #${it.change?.str("change_number")} ${it.change?.str("name")}" },
    )
}

/** `#13 Rain coat`, the label a breakdown row shows in the Change column. */
fun changeLabel(change: Rec?): String = if (change == null) "" else "#${change.str("change_number")} ${change.str("name")}".trim()

private val ROW_PROBLEMS = listOf("MISSING", "DAMAGED", "ALTERATION", "CLEANING")

/**
 * A breakdown row's dot, by the reference's row rule: no look is unassigned,
 * otherwise the worst of the four problem statuses among its pieces, else
 * ready. Unlike the scene-level readiness, a look with no pieces reads ready.
 */
fun readinessOf(sceneCharacter: Rec): String {
    val change = sceneCharacter.rec("change") ?: return "NOT_ASSIGNED"
    val statuses = change.recs("items").map { it.rec("costume")?.str("status").orEmpty() }
    return ROW_PROBLEMS.firstOrNull { it in statuses } ?: "READY"
}

// -- script review -------------------------------------------------------------

/** The fields a script upload compares with what each scene says now. */
val REVIEW_FIELDS = listOf("int_ext", "location", "script_day", "time_of_day", "pages", "synopsis")

private fun norm(v: String?): String = v.orEmpty().replace(Regex("\\s+"), " ").trim().lowercase()

fun sameText(a: String?, b: String?): Boolean = norm(a) == norm(b)

/** The compared fields the script (or the hand edit) would change; none for a new scene. */
fun changedFields(scene: Rec, edited: Rec? = null): List<String> {
    val previous = scene.rec("previous") ?: return emptyList()
    val next = edited ?: scene
    return REVIEW_FIELDS.filter { !sameText(previous.str(it), next.str(it)) }
}

/** `INT. Kitchen`, the slugline a review row shows. */
fun slugOf(f: Rec?): String = if (f == null) "" else listOf(f.str("int_ext"), f.str("location")).filter { it.isNotEmpty() }.joinToString(". ")

/** A hand-corrected location or time renames the scene "Location - Time", as the parser names it. */
fun editedName(scene: Rec, edit: Rec?): String {
    if (edit == null) return scene.str("name")
    if (sameText(edit.str("location"), scene.str("location")) && sameText(edit.str("time_of_day"), scene.str("time_of_day"))) {
        return scene.str("name")
    }
    return listOf(edit.str("location"), timeWord(edit.str("time_of_day"))).filter { it.isNotEmpty() }.joinToString(" - ")
        .ifEmpty { scene.str("name") }
}

/**
 * The service leaves a scene whose script text has not moved alone, so a row
 * the review shows as changing (corrected by hand, or with fields that differ
 * from the scene now) must be forced, or Replace would quietly do nothing.
 */
fun forceImport(scene: Rec, edited: Boolean): Boolean = edited || changedFields(scene).isNotEmpty()

// -- per-character cast edits ----------------------------------------------------------

/** The cast number a character has now, as the text a field shows. */
fun castNumberText(c: Rec): String = if (c.has("cast_number")) c.str("cast_number") else ""

/** The id of the actor playing a character now (`actor_id`, else the embedded actor). */
fun actorIdOf(c: Rec): String = c.str("actor_id").ifEmpty { c.rec("actor")?.id.orEmpty() }

/**
 * Cast edits are per character, so each is merged into the draft's map rather
 * than replacing it — and a value typed back to what the character already has
 * stops being an edit, so the row does not stay dirty and no pointless write is sent.
 */
fun SceneDraft.withCastNumber(c: Rec, typed: String): SceneDraft {
    val now = cast[c.id] ?: CastEdit()
    val next = now.copy(castNumber = typed.takeIf { it.trim() != castNumberText(c) })
    return withCastEdit(c.id, next)
}

fun SceneDraft.withCastActor(c: Rec, actorId: String): SceneDraft {
    val now = cast[c.id] ?: CastEdit()
    val next = now.copy(actorId = actorId.takeIf { it != actorIdOf(c) })
    return withCastEdit(c.id, next)
}

private fun SceneDraft.withCastEdit(characterId: String, edit: CastEdit): SceneDraft =
    copy(cast = if (edit.isEmpty) cast - characterId else cast + (characterId to edit))
