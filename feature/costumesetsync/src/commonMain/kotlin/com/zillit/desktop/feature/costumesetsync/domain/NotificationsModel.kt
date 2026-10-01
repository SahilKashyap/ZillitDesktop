package com.zillit.desktop.feature.costumesetsync.domain

/**
 * Where a notification leads — the web's `lib/notifications.js`, on the desktop's routes. A target is a
 * path under the tool root (`costumes/<id>`, `alterations?chat=<id>`), or null when the record has no page.
 */
object NotificationsModel {
    /** Where each kind of record lives, relative to the tool root — the reference's `open()` map. */
    private val PAGE = mapOf(
        "COSTUME" to "costumes", "CLEANING" to "cleaning", "ALTERATION" to "alterations", "DAMAGE" to "damages",
        "FITTING" to "fittings",
        "RENTAL" to "vendors",
        "MISSING" to "missing",
        "EXPENSE" to "budget",
        "BUDGET" to "budget",
    )

    /** Kinds with a page of their own; the rest live on a list page. */
    private val HAS_DETAIL = setOf("COSTUME", "CLEANING", "FITTING")

    /**
     * A chat message opens that record's chat with `?chat=<id>`; a costume, cleaning ticket or fitting opens its
     * page; anything else opens its list. (The web adds `#rec-<id>` to scroll to the record on a list; the
     * desktop has no anchors, so a list opens plain.)
     */
    fun target(n: Rec): String? {
        val type = n.str("entity_type")
        val id = n.str("entity_id")
        val page = PAGE[type] ?: return null
        return when {
            n.str("type") == "CHAT" &&
                id.isNotEmpty() -> if (type in HAS_DETAIL) "$page/$id?chat=$id" else "$page?chat=$id"
            id.isNotEmpty() && type in HAS_DETAIL -> "$page/$id"
            else -> page
        }
    }

    /** A notification's timestamp, whichever name the service used for it. */
    fun time(n: Rec): Long = n.long("created")
        .takeIf { it != 0L } ?: n.long("created_at")
        .takeIf { it != 0L } ?: n.long(
        "createdAt",
    )

    /** The header badge's text: the count, "99+" past 99, nothing for none. */
    fun badge(unread: Int): String = when {
        unread <= 0 -> ""
        unread > MAX_BADGE -> "$MAX_BADGE+"
        else -> unread.toString()
    }

    private const val MAX_BADGE = 99

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
