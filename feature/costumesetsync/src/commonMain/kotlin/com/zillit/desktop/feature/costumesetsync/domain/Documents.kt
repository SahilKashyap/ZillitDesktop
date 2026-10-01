package com.zillit.desktop.feature.costumesetsync.domain

/**
 * The project's documents (scripts, schedules, call sheets) the service keeps —
 * the web's `lib/documents.js`: Zillit's latest of each kind plus every file
 * imported in this tool, newest first. Listing is read-only: scenes change only
 * when a user reads a document into the importer and confirms.
 */

/** The service's answer when a listed document was deleted or replaced in Zillit meanwhile. */
const val DOC_GONE = "costume_set_sync_document_not_exists"

/**
 * Where a document came from, as a web translation key. A ZILLIT document names
 * the Zillit tool it was posted in, so the user knows which one to update;
 * anything else was uploaded here.
 */
fun docSourceKey(doc: Rec): String = when {
    doc.str("source") != "ZILLIT" -> "csync_doc_source_upload"
    doc.str("kind") == "SCRIPT" -> "csync_doc_source_script_distribution"
    doc.str("kind") == "SCHEDULE" -> "csync_doc_source_schedule_distribution"
    else -> "csync_doc_source_home_callsheet"
}

/** The newest document: the one the service flags `latest`, else the first in its newest-first list. */
fun latestOf(docs: List<Rec>): Rec? = docs.firstOrNull { it.bool("latest") } ?: docs.firstOrNull()

/** A listed document's display name. */
fun docName(doc: Rec?): String = doc?.str("file_name")?.ifEmpty { null } ?: doc?.rec("attachment")?.str("name").orEmpty()

private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}")

/** The day a kept call sheet is for, as `YYYY-MM-DD` (blank when unknown). */
fun docDayKey(doc: Rec?): String {
    val raw = doc?.str("sheet_date").orEmpty()
    if (raw.isEmpty()) return ""
    if (ISO_DAY.containsMatchIn(raw)) return raw.take(ISO_DAY_CHARS)
    return dateKey(doc?.long("sheet_date"))
}

private const val ISO_DAY_CHARS = 10

private val TEXT_DOC = Regex("\\.(fountain|txt|text|csv|tsv|fdx)$", RegexOption.IGNORE_CASE)

/** Read as text beside the scenes: Final Draft, Fountain and plain-text files. */
fun isTextDoc(name: String): Boolean = TEXT_DOC.containsMatchIn(name)

private fun sceneNote(s: Rec): String =
    listOf(s.str("int_ext"), s.str("location")).filter { it.isNotEmpty() }.joinToString(". ").ifEmpty { s.str("name") }

/** One scene against a kept file; [note] is the location line, or for a schedule the `YYYY-MM-DD` day. */
data class DocSceneRow(val scene: Rec, val note: String)

data class DocScenes(val rows: List<DocSceneRow>, val undated: Int)

/**
 * The scenes the app holds for a kept file. Script: every scene in the
 * breakdown. Schedule: every scene with a shoot date, by day. Call sheet: the
 * scenes on its day. Omitted scenes are left out. [DocScenes.undated] counts the
 * scenes a schedule is missing.
 */
fun docSceneRows(kind: String, scenes: List<Rec>, day: String = ""): DocScenes {
    val live = scenes.filter { it.str("status") != "OMITTED" }
    return when (kind) {
        "SCRIPT" -> DocScenes(live.map { DocSceneRow(it, sceneNote(it)) }, 0)
        "SCHEDULE" -> {
            val dated = live.mapIndexed { i, s -> Triple(s, i, dateKey(s.long("shoot_date"))) }
                .filter { it.third.isNotEmpty() }
                .sortedWith(compareBy({ it.third }, { it.second }))
            DocScenes(dated.map { DocSceneRow(it.first, it.third) }, live.size - dated.size)
        }
        else -> DocScenes(
            live.filter { day.isNotEmpty() && dateKey(it.long("shoot_date")) == day }.map { DocSceneRow(it, sceneNote(it)) },
            0,
        )
    }
}
