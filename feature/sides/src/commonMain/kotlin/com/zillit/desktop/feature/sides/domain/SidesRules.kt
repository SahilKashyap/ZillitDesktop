package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The small pure rules the web's Sides screens encode inline, gathered so
 * the screens and the view model read one definition each.
 */
object SidesRules {

    /**
     * Strip a leading scene number from a heading so a chip's tooltip does
     * not repeat the number the chip already shows — the web's `sceneName`.
     */
    fun sceneName(heading: String, sceneNumber: String): String {
        val trimmed = heading.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith(sceneNumber, ignoreCase = true)) return trimmed
        val rest = trimmed.drop(sceneNumber.length).trimStart(' ', '.', '-', ':', ')').trim()
        return rest.ifEmpty { trimmed }
    }

    /** The chip tooltip: `12  INT. KITCHEN - DAY`, or `Scene 12` when nameless. */
    fun sceneTip(scene: SceneInfo): String {
        val name = sceneName(scene.heading, scene.sceneNumber)
        return if (name.isEmpty()) str(S.desktop_scene_numbered, scene.sceneNumber) else "${scene.sceneNumber}  $name"
    }

    /**
     * Keep a typed order in step with the selection: preserve the custom
     * order, append newly picked scenes, drop deselected ones — the web's
     * order-sync effect.
     */
    fun syncOrder(current: List<String>, selected: List<String>): List<String> {
        val selectedSet = selected.toSet()
        val kept = current.filter { it in selectedSet }
        val missing = selected.filter { it !in kept }
        return kept + missing
    }

    /** The web's history filter: title or creator, case-insensitive. */
    fun filterHistory(rows: List<SidesRecord>, query: String): List<SidesRecord> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return rows
        return rows.filter {
            it.title.lowercase().contains(needle) || it.generatedByName.lowercase().contains(needle)
        }
    }

    /** The web's pages filter: scene number, file name or note. */
    fun filterPages(pages: List<ScenePage>, query: String): List<ScenePage> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return pages
        return pages.filter {
            it.sceneNumber.lowercase().contains(needle) ||
                it.fileName.lowercase().contains(needle) ||
                it.description.lowercase().contains(needle)
        }
    }

    /** The web's `formatBytes`: null for nothing, B / KB / MB otherwise. */
    fun formatBytes(bytes: Long): String? = when {
        bytes <= 0 -> null
        bytes < KB -> "$bytes B"
        bytes < MB -> "${(bytes + KB / 2) / KB} KB"
        else -> {
            val tenths = (bytes * TENTHS + MB / 2) / MB
            "${tenths / TENTHS}.${tenths % TENTHS} MB"
        }
    }

    /** `Day 5 sides.pdf` → `Day_5_sides.pdf`: the web's download file name. */
    fun downloadName(title: String, fallback: String = "sides"): String =
        title.ifBlank { fallback }.replace(Regex("""[^\w.-]+"""), "_") + ".pdf"

    /** A script or page file must be a PDF or Final Draft document. */
    fun isPdfOrFdx(fileName: String): Boolean = fileName.lowercase().let { it.endsWith(".pdf") || it.endsWith(".fdx") }

    fun isPdf(fileName: String): Boolean = fileName.lowercase().endsWith(".pdf")

    /** The extension the attachment descriptor carries: `pdf` or `fdx`. */
    fun contentSubtype(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase().ifBlank { "pdf" }

    /** The file's name without its `.pdf`/`.fdx` — the web's default title. */
    fun titleFromFileName(fileName: String): String =
        fileName.replace(Regex("""\.(pdf|fdx)$""", RegexOption.IGNORE_CASE), "").trim()

    /** Whether fetched bytes are a PDF at all — a Final Draft file is XML. */
    fun isPdfBytes(bytes: ByteArray): Boolean =
        bytes.size >= PDF_MAGIC.size && PDF_MAGIC.indices.all { bytes[it] == PDF_MAGIC[it] }

    /** A hex colour the page chips can tint from. */
    fun isHexColor(value: String): Boolean = HEX_COLOR.matches(value)

    /**
     * Why Autogenerate's Generate is disabled — the web's `disabledReason`,
     * surfaced as a hint under the form and as the button's tooltip.
     */
    fun autoDisabledReason(
        callSheetSelected: Boolean,
        loadingScenes: Boolean,
        orderedScenes: Int,
        rearranging: Boolean,
    ): String = when {
        !callSheetSelected -> str(S.desktop_sides_select_call_sheet_above)
        loadingScenes -> str(S.desktop_sides_loading_call_sheet_scenes)
        orderedScenes == 0 && rearranging -> str(S.desktop_sides_add_scene_number)
        orderedScenes == 0 -> str(S.desktop_sides_call_sheet_no_scenes)
        else -> ""
    }

    /** The web's `MMM D, YYYY · h:mm A` in the viewer's zone; `—` when unparseable. */
    fun formatDateTime(iso: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val time = parseInstant(iso)?.toLocalDateTime(zone) ?: return "—"
        val hour12 = (time.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
        val meridiem = if (time.hour < HALF_DAY) "AM" else "PM"
        val minute = time.minute.toString().padStart(2, '0')
        return "${formatDate(iso, zone)} · $hour12:$minute $meridiem"
    }

    /** The web's `MMM D, YYYY`. */
    fun formatDate(iso: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val time = parseInstant(iso)?.toLocalDateTime(zone) ?: return "—"
        return "${MONTHS[time.month.ordinal]} ${time.day}, ${time.year}"
    }

    private fun parseInstant(iso: String): Instant? =
        iso.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** The page-folder colours the editor offers. */
    val PAGE_COLORS: List<String> = listOf(
        "#e53935", "#fb8c00", "#fdd835", "#43a047", "#1e88e5", "#8e24aa", "#6d4c41", "#9e9e9e",
    )

    const val SCENE_CHIP_CAP = 13
    const val PAGE_CHIP_CAP = 10
    const val RESULT_SCENE_CAP = 30
    const val RESULT_PAGE_SCENE_CAP = 18
    const val NOTE_LIMIT = 120

    private const val HALF_DAY = 12
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val KB = 1024L
    private const val TENTHS = 10
    private const val MB = KB * KB
    private val PDF_MAGIC = "%PDF".encodeToByteArray()
    private val HEX_COLOR = Regex("""^#[0-9a-fA-F]{6}$""")
}
