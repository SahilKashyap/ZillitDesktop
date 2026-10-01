package com.zillit.desktop.feature.costumesetsync.domain

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
            castNumber = (embedded?.takeIf { it.has("cast_number") } ?: full?.takeIf { it.has("cast_number") })?.long(
                "cast_number",
            ),
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
        CastRow(
            c.id,
            c.str("name"),
            c.long("cast_number").takeIf { c.has("cast_number") },
            c.rec("actor")?.str("name")?.ifEmpty { null },
            null,
        )
    },
    { it.castNumber },
    { it.name },
)

private fun CastRow.label(): String = if (castNumber != null) "$castNumber. $name" else name

fun namesOf(rows: List<CastRow>) = CellText(rows.joinToString(", ") { it.name }, rows.joinToString("\n") { it.label() })

/** Their cast numbers, in the same order. A character without one is left out. */
fun castNumbersOf(rows: List<CastRow>): CellText {
    val numbered = rows.filter { it.castNumber != null }
    return CellText(
        numbered.joinToString(", ") { it.castNumber.toString() },
        numbered.joinToString("\n") { it.label() },
    )
}

/** The actors playing them, in the same order. A part with nobody cast is left out. */
fun castMembersOf(rows: List<CastRow>): CellText {
    val cast = rows.filter { !it.actor.isNullOrEmpty() }
    return CellText(
        cast.joinToString(", ") { it.actor.orEmpty() },
        cast.joinToString("\n") { "${it.actor} · ${it.name}" },
    )
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
fun changeLabel(change: Rec?): String =
    if (change == null) "" else "#${change.str("change_number")} ${change.str("name")}".trim()
