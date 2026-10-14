package com.zillit.desktop.feature.costumesetsync.domain

/**
 * The header search's hits (the web's `GlobalSearch`). The service's own
 * notification list is retired (BE, 2026-10-05) — unread is Zillit's own now,
 * see [CsyncBadges] — so what was its routing and its bell count went with it.
 */
object GlobalSearchModel {
    /** One search hit in the header. */
    data class Hit(val key: String, val label: String, val sub: String, val to: String, val kind: String)

    /** Scenes and characters are matched here (the web's `GlobalSearch`); costume hits come from the service. */
    fun sceneHits(scenes: List<Rec>, needle: String, sceneWord: String, kind: String): List<Hit> {
        val s = needle.lowercase()
        return scenes.filter {
            it.str("number")
                .lowercase()
                .startsWith(s) || it.str("name")
                .lowercase()
                .contains(s) || it.str("location")
                .lowercase()
                .contains(
                s,
            )
        }
            .take(MAX_KIND).map { sc ->
                Hit(
                    "s${sc.id}",
                    "$sceneWord ${sc.str("number")}" + sc.str("name").let { if (it.isEmpty()) "" else " · $it" },
                    listOf(sc.str("int_ext"), sc.str("location")).filter { it.isNotEmpty() }.joinToString(". "),
                    "scenes/${sc.id}",
                    kind,
                )
            }
    }

    fun characterHits(characters: List<Rec>, needle: String, kind: String): List<Hit> {
        val s = needle.lowercase()
        return characters.filter {
            it.str("name").lowercase().contains(s) || (it.has("cast_number") && it.str("cast_number") == needle)
        }
            .take(MAX_KIND).map { c ->
                Hit(
                    "c${c.id}",
                    (if (c.has("cast_number")) "${c.str("cast_number")}. " else "") + c.str("name"),
                    c.rec("actor")?.str("name").orEmpty(),
                    "characters/${c.id}",
                    kind,
                )
            }
    }

    fun costumeHits(costumes: List<Rec>, statusWord: (String) -> String, kind: String): List<Hit> =
        costumes.take(MAX_COSTUMES).map { c ->
            Hit(
                "k${c.id}", "${c.str("asset_number")} ${c.str("name")}".trim(),
                listOf(
                    c.rec("character")?.str("name").orEmpty(),
                    c.str("status").takeIf { it.isNotEmpty() }?.let(statusWord).orEmpty(),
                )
                    .filter { it.isNotEmpty() }
                    .joinToString(
                    " · ",
                ),
                "costumes/${c.id}", kind,
            )
        }

    private const val MAX_KIND = 5
    private const val MAX_COSTUMES = 6
}
