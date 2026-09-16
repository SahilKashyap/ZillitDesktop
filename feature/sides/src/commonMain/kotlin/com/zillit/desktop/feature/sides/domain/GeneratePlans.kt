package com.zillit.desktop.feature.sides.domain

/** How scenes the run did not select are printed. */
object SceneDisplayMode {
    /** Keep full pages; strike through unselected scenes — the default. */
    const val CROSSOUT = "crossout"

    /** Only the selected scenes appear. */
    const val HIDE = "hide"
}

/** The scenes picked from one script version. */
data class VersionScenes(val versionId: String, val sceneNumbers: List<String>)

/** The scenes picked from one page folder; an empty list means the whole PDF. */
data class PageSelection(val pageId: String, val sceneNumbers: List<String> = emptyList())

/**
 * A manual generation request — the web's `GenerateSidesForm.handleSubmit`.
 * `publish` is always false: every run lands in review first, and publishing
 * is its own explicit act.
 */
data class ManualPlan(
    val scriptId: String,
    val title: String = "",
    val versionScenes: List<VersionScenes> = emptyList(),
    val pageSelections: List<PageSelection> = emptyList(),
    val displayMode: String = SceneDisplayMode.CROSSOUT,
    /** Non-empty turns on `orderedScenes` and regroups the version picks to follow it. */
    val sceneOrder: List<String> = emptyList(),
) {
    val sceneCount: Int get() = versionScenes.sumOf { it.sceneNumbers.size }

    val readyToSubmit: Boolean get() = sceneCount > 0 || pageSelections.isNotEmpty()

    /**
     * The version groups the wire carries: in the typed order when one is
     * set (each scene attributed to the first version that picked it, as the
     * web does), else as picked.
     */
    val wireVersionScenes: List<VersionScenes>
        get() {
            if (sceneOrder.isEmpty()) return versionScenes
            val sceneToVersion = linkedMapOf<String, String>()
            versionScenes.forEach { group ->
                group.sceneNumbers.forEach { scene -> sceneToVersion.putIfAbsent(scene, group.versionId) }
            }
            val grouped = linkedMapOf<String, MutableList<String>>()
            sceneOrder.forEach { scene ->
                val versionId = sceneToVersion[scene] ?: return@forEach
                grouped.getOrPut(versionId) { mutableListOf() }.add(scene)
            }
            return if (grouped.isEmpty()) versionScenes else grouped.map { (id, scenes) -> VersionScenes(id, scenes) }
        }
}

/**
 * A call-sheet-driven generation — the web's `AutogenerateSidesModal
 * .handleGenerate`. The call sheet is always included, its own scene pages
 * never are, and the scene order is the call sheet's (or the typed one).
 */
data class AutoPlan(
    val scriptId: String,
    val callSheetId: String,
    val scheduleId: String = "",
    val sceneNumbers: List<String>,
    val displayMode: String = SceneDisplayMode.CROSSOUT,
    val title: String = "",
)

/** The web's scene-list parse: split on commas, semicolons or whitespace. */
fun parseSceneList(typed: String): List<String> =
    typed.split(Regex("""[,;\s]+""")).map { it.trim() }.filter { it.isNotEmpty() }
