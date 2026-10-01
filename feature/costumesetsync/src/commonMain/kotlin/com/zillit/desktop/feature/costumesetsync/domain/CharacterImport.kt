package com.zillit.desktop.feature.costumesetsync.domain

/**
 * The character half of a script import — the web's `lib/characterImport.js`.
 *
 * A row is one character the parser found (or one typed in by hand). [manual]
 * marks a row typed in rather than read out of the script, so its name stays
 * editable; [deleted] FLAGS rather than removes, which is what lets Delete All
 * be undone row by row with Restore.
 */
data class ImportRow(val name: String, val castNumber: String = "", val deleted: Boolean = false, val manual: Boolean = false)

/** The two maps the import endpoint takes. A null name in [characterMap] says "not a character at all". */
data class CharacterImport(val characterMap: Map<String, String?>, val castNumbers: Map<String, Long>)

/** A character the import will create by hand, outside the scenes. */
data class ManualCharacter(val name: String, val castNumber: Long?)

private val WHOLE_NUMBER = Regex("^\\d+$")

/** Only a whole non-negative number is a cast number — the service rejects anything else. */
fun castNumberOf(row: ImportRow): Long? = row.castNumber.trim().takeIf { WHOLE_NUMBER.matches(it) }?.toLongOrNull()

private fun byName(existing: List<Rec>): Map<String, Rec> = existing.associateBy { it.str("name").lowercase() }

/** One row per character the parser found, pre-filled with any number it already has. */
fun initialRows(detected: List<Rec>, existing: List<Rec>): List<ImportRow> {
    val known = byName(existing)
    return detected.map { d ->
        val have = known[d.str("name").lowercase()]
        val number = have?.takeIf { it.has("cast_number") }?.str("cast_number")
            ?: d.takeIf { it.has("cast_number") }?.str("cast_number").orEmpty()
        ImportRow(name = d.str("name"), castNumber = number)
    }
}

/**
 * - a deleted row is not a character at all (`character_map[name] = null`);
 * - a row whose name matches one the production already has (case-insensitively)
 *   resolves to that character, so a re-upload merges instead of duplicating;
 * - anything else becomes a new character carrying the number typed beside it.
 */
fun buildCharacterImport(rows: List<ImportRow>, existing: List<Rec> = emptyList()): CharacterImport {
    val map = LinkedHashMap<String, String?>()
    val numbers = LinkedHashMap<String, Long>()
    val known = byName(existing)
    for (r in rows) {
        val name = r.name.trim()
        if (name.isEmpty()) continue
        if (r.deleted) {
            map[name] = null
            continue
        }
        val target = known[name.lowercase()]?.str("name")?.ifEmpty { null } ?: name
        map[name] = target
        castNumberOf(r)?.let { numbers[target] = it }
    }
    return CharacterImport(map, numbers)
}

/**
 * Hand-typed characters that no scene mentions — extras, doubles, anyone who
 * never speaks. The breakdown import only creates the names it finds IN scenes,
 * so these have to be created on their own.
 */
fun manualCharacters(rows: List<ImportRow>, existing: List<Rec> = emptyList(), detected: List<Rec> = emptyList()): List<ManualCharacter> {
    val known = (existing + detected).map { it.str("name").lowercase() }.toMutableSet()
    val out = ArrayList<ManualCharacter>()
    for (r in rows) {
        val name = r.name.trim()
        if (!r.manual || r.deleted || name.isEmpty()) continue
        if (!known.add(name.lowercase())) continue
        out.add(ManualCharacter(name, castNumberOf(r)))
    }
    return out
}

/** How many characters the import will end up creating — the count above the table. */
fun aliveRows(rows: List<ImportRow>): List<ImportRow> = rows.filter { !it.deleted }
