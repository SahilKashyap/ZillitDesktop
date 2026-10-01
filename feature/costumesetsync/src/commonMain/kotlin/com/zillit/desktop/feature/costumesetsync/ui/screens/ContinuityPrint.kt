package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.domain.ContinuityModel
import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml.esc
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

/*
 * The continuity book on paper — the web's `PrepExport` / `BookExport`. Desktop has
 * no print portal, so the book is generated as an HTML document and saved through
 * the host's save dialog (open it in a browser to print or save as PDF). Take photos
 * are left out: they are stored as keys that need the media seam to resolve.
 */

/** "INT · Kitchen · Day · D2 · 1 4/8 pgs"; empty when the scene has none of them. */
internal fun sceneLine(s: Rec): String = listOf(
    s.str("int_ext"),
    s.str("location"),
    tEnum(s.str("time_of_day")),
    s.str("script_day"),
    s.str("pages").takeIf { it.isNotBlank() }?.let { "$it ${t("csync_pgs")}" }.orEmpty(),
).filter { it.isNotBlank() }.joinToString(" · ")

private fun sceneTitle(s: Rec): String = "${t("csync_scene")} ${s.str("number")}" + s.str("name").let { if (it.isBlank()) "" else " · $it" }

private fun changeLine(change: Rec?): String =
    if (change != null) "${t("csync_change")} #${change.str("change_number")} ${change.str("name")}" else t("csync_no_change_assigned")

private fun characterHead(name: String, actor: String): String = "<b>${esc(name)}${if (actor.isNotBlank()) " — ${esc(actor)}" else ""}</b>"

private fun cover(sub: String, project: Rec?, day: String, counts: String): String {
    val shooting = project?.long("shooting_day")?.takeIf { it > 0 }?.let { t("csync_production_day_n", "n" to it) + " · " }.orEmpty()
    return "<div><div class=\"sub\">${esc(sub.uppercase())}</div><h1>${esc(project?.str("project_name")?.ifBlank { null } ?: t("csync_production"))}</h1>" +
        "<div>${esc(shooting + DayKeys.long(day))}</div><div>${esc(counts)}</div></div>"
}

/** The prep sheet for [day]. */
internal fun prepHtml(day: String, scenes: List<Rec>, project: Rec?): String {
    val dayScenes = ContinuityModel.onDay(scenes, day)
    val characters = dayScenes.sumOf { it.recs("characters").size }
    val counts = t(if (dayScenes.size == 1) "csync_n_scene_one" else "csync_n_scenes", "n" to dayScenes.size) + " · " +
        t(if (characters == 1) "csync_n_character_one" else "csync_n_characters", "n" to characters)
    return buildString {
        append(cover(t("csync_continuity_of_prep"), project, day, counts))
        if (dayScenes.isEmpty()) append("<p>${esc(t("csync_nothing_is_scheduled_day"))}</p>")
        dayScenes.forEach { s ->
            append("<h2>${esc(sceneTitle(s))}</h2><div class=\"muted\">${esc(sceneLine(s).ifBlank { "—" })}</div>")
            if (s.recs("characters").isEmpty()) append("<div>${esc(t("csync_nobody_tagged_scene_print"))}</div>")
            s.recs("characters").forEach { c ->
                val r = ContinuityModel.readiness(c)
                val change = c.rec("change")
                append("<div class=\"char\">${characterHead(c.rec("character")?.str("name").orEmpty(), c.rec("character")?.rec("actor")?.str("name").orEmpty())}")
                append("<div>${esc(changeLine(change))}</div>")
                val items = change?.recs("items").orEmpty()
                if (items.isNotEmpty()) {
                    append("<div>${esc(items.joinToString(" · ") { it.rec("costume")?.str("name").orEmpty() + it.str("wear_notes").let { n -> if (n.isBlank()) "" else " ($n)" } })}</div>")
                }
                if (change != null && r.blockers.isNotEmpty()) append("<div><b>${esc(t("csync_not_ready"))}:</b> ${esc(blockerLine(r))}</div>")
                append("</div>")
            }
        }
    }
}

/** The continuity book for the shoot day [day]: every scene, character and take. */
internal fun bookHtml(day: String, records: List<Rec>, scenes: List<Rec>, project: Rec?): String {
    val dayScenes = ContinuityModel.onDay(scenes, day)
    val takesOnDay = records.count { r -> dayScenes.any { it.id == r.str("scene_id") } }
    val counts = t(if (dayScenes.size == 1) "csync_n_scene_one" else "csync_n_scenes", "n" to dayScenes.size) + " · " +
        t(if (takesOnDay == 1) "csync_n_take_one" else "csync_n_takes", "n" to takesOnDay)
    return buildString {
        append(cover(t("csync_continuity_book"), project, day, counts))
        if (dayScenes.isEmpty()) append("<p>${esc(t("csync_no_scenes_that_day"))}</p>")
        dayScenes.forEach { s ->
            val rows = ContinuityModel.takesOf(records, s.id)
            val ids = rows.map { it.str("character_id") }.distinct()
            append("<h2>${esc(sceneTitle(s))}</h2><div class=\"muted\">${esc(sceneLine(s).ifBlank { "—" })}</div>")
            if (ids.isEmpty()) append("<div>${esc(t("csync_no_takes_for_scene"))}</div>")
            ids.forEach { cid ->
                val takes = rows.filter { it.str("character_id") == cid }
                val first = takes.first()
                val name = first.rec("character")?.str("name")?.ifBlank { null }
                    ?: s.recs("characters").firstOrNull { it.str("character_id") == cid }?.rec("character")?.str("name")
                    ?: t("csync_character")
                append("<div class=\"char\">${characterHead(name, first.rec("character")?.rec("actor")?.str("name").orEmpty())}<div>${esc(changeLine(first.rec("change")))}</div>")
                takes.forEach { append(takeHtml(it)) }
                append("</div>")
            }
        }
    }
}

private fun takeHtml(r: Rec): String = buildString {
    append("<div class=\"take\"><b>${esc(t("csync_take"))} ${r.long("take_number")}</b> ${esc(fmtDateTime(r.long("created")))}")
    r.str("recorded_by_name").takeIf { it.isNotBlank() }?.let { append(" · ${esc(it)}") }
    r.rec("details")?.let { d -> append("<table>" + d.keys.joinToString("") { "<tr><td class=\"muted\">${esc(it)}</td><td>${esc(d.str(it).ifBlank { "—" })}</td></tr>" } + "</table>") }
    val acc = r.recs("accessories")
    if (acc.isNotEmpty()) append("<div>${esc(acc.joinToString(" · ") { (if (it.bool("present")) "✓ " else "✗ ") + it.str("name") })}</div>")
    r.str("notes").takeIf { it.isNotBlank() }?.let { append("<div><i>${esc(it)}</i></div>") }
    append("</div>")
}
