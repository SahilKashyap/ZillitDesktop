package com.zillit.desktop.feature.costumesetsync.domain

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
fun slugOf(f: Rec?): String = if (f == null) "" else listOf(f.str("int_ext"), f.str("location"))
    .filter { it.isNotEmpty() }
    .joinToString(". ")

/** A hand-corrected location or time renames the scene "Location - Time", as the parser names it. */
fun editedName(scene: Rec, edit: Rec?): String {
    if (edit == null) return scene.str("name")
    if (sameText(edit.str("location"), scene.str("location")) && sameText(
        edit.str("time_of_day"),
        scene.str("time_of_day"),
    )) {
        return scene.str("name")
    }
    return listOf(edit.str("location"), timeWord(edit.str("time_of_day"))).filter { it.isNotEmpty() }.joinToString(
        " - ",
    )
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
