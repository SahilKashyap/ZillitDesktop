package com.zillit.desktop.feature.costumesetsync.domain

/**
 * Pure helpers for the Reports screen (daily, inventory, wrap) — the web's `lib/reports.js`,
 * matching the reference app's `pages/Reports.tsx` and the service's `routes/reports.ts`.
 */
object ReportsModel {
    /** ` ×6` after a description when a line holds more than one piece. */
    fun qtySuffix(quantity: Long): String = if (quantity > 1) " ×$quantity" else ""

    /** A daily-report scene's cast as the reference prints it: `Meera: #2 Wedding · Arjun: —`. */
    fun sceneCast(scene: Rec): String = scene.recs("characters").joinToString(" · ") {
        "${it.str("name")}: ${it.str("change")}"
    }

    /** The wrap report's groups in the order the service sent them: `source → pieces`. */
    fun wrapGroups(wrap: Rec?): List<Pair<String, List<Rec>>> {
        val groups = wrap?.rec("groups") ?: return emptyList()
        return groups.keys.map { it to groups.recs(it) }.filter { (key, _) ->
            groups.json[key] is kotlinx.serialization.json.JsonArray
        }
    }

    /** One CSV cell, escaped exactly as the service's own `csv()` does. */
    fun cell(value: String?): String {
        val s = value.orEmpty()
        return if (s.any { it == '"' || it == ',' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
    }

    /** Rows → CSV text with [headers] in order; empty for no rows, as the service. */
    fun toCsv(rows: List<Map<String, String>>, headers: List<String>): String {
        if (rows.isEmpty()) return ""
        return (
            listOf(headers.joinToString(",")) + rows.map { r -> headers.joinToString(",") { cell(r[it]) } }
        ).joinToString("\n")
    }

    /** The inventory CSV's columns, in the service's order, headed as the reference's CSV. */
    val INVENTORY_CSV_COLUMNS = listOf(
        "asset", "name", "category", "type", "color", "brand", "size", "quantity",
        "character", "source", "vendor", "purchaseCost", "status", "location",
    )

    /** Inventory CSV built from the rows on screen; the cost stays blank unless [withCost] (the service redacts it). */
    fun inventoryCsv(rows: List<Rec>, withCost: Boolean): String = toCsv(
        rows.map { r ->
            INVENTORY_CSV_COLUMNS.associateWith { column ->
                when (column) {
                    "purchaseCost" -> if (withCost && r.has("purchase_cost")) r.str("purchase_cost") else ""
                    else -> r.str(column)
                }
            }
        },
        INVENTORY_CSV_COLUMNS,
    )

    val DAILY_CSV_COLUMNS = listOf("section", "reference", "description", "status", "detail")

    /**
     * The daily report as CSV, built from the figures on screen: one row per scene, cleaning
     * ticket, alteration and missing piece. (The web downloads the service's own
     * `/reports/daily.csv`, a raw file the desktop api client does not fetch.)
     */
    fun dailyCsv(data: Rec): String {
        val rows = mutableListOf<Map<String, String>>()
        fun row(section: String, reference: String, description: String, status: String, detail: String) =
            rows.add(mapOf(
                "section" to section,
                "reference" to reference,
                "description" to description,
                "status" to status,
                "detail" to detail
            ))
        data.recs("scenes").forEach { row("Scene", it.str("number"), it.str("name"), it.str("status"), sceneCast(it)) }
        data.recs("cleaning").forEach {
            row(
                "Cleaning",
                it.rec("costume")?.str("asset_number").orEmpty(),
                it.rec("costume")?.str("name").orEmpty(),
                it.str("status"),
                "${it.str("problem")} / ${it.str("cleaning_type")}"
            )
        }
        data.recs("alterations").forEach {
            row(
                "Alteration",
                it.rec("costume")?.str("asset_number").orEmpty(),
                it.rec("costume")?.str("name").orEmpty(),
                it.str("status"),
                "${it.str("issue")} -> ${it.str("required_work")}"
            )
        }
        data.recs("missing").forEach {
            row(
                "Missing",
                it.rec("costume")?.str("asset_number").orEmpty(),
                it.rec("costume")?.str("name").orEmpty(),
                "MISSING",
                it.str("last_seen_location")
            )
        }
        return toCsv(rows, DAILY_CSV_COLUMNS)
    }
}
