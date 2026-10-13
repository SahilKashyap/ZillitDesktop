package com.zillit.desktop.core.badges

/**
 * Android's composition rules over the ledger (`CommonBadgesHandler.computeBadgesCount`
 * and `recomputeToolsTotal`), one pass:
 *
 * - **Home**: one per row whose tool is not the calendar, keyed by unit; a
 *   calendar row counts only when its event has an end time (pending or
 *   expired both count — 182-186, 215-225, `BottomNavigationActivity:401-405`).
 * - **Tools**: one per row, keyed by tool identifier; `ad_dashboard_label`
 *   never counts (187-199). Also keyed by unit — a board tool's tab strip
 *   (Accounts, Catering, Info, Location…) badges each unit the way the web's
 *   `getMultipleUnitChatBadgesFromDB` groups a tool's rows by `unit`.
 * - **C&C**: missed calls plus every chat row that can be placed in a
 *   conversation (a room id or a sender); the rest are dropped (10133-10137,
 *   8126-8130).
 * - **Settings**: only the approval/onboarding units (2432-2445), also keyed
 *   by unit so each approval queue's row can wear its own count.
 * - Everything else (`sos_label`, `global_label`, `email_label`…): one per row.
 */
fun tallyBadges(rows: Collection<NotificationRecord>): BadgeCounts {
    val tally = Tally()
    rows.filter { it.counts }.forEach(tally::add)
    return tally.counts()
}

/** The three maps, filled one row at a time by the section's own rule. */
private class Tally {
    private val sections = mutableMapOf<String, Int>()
    private val tools = mutableMapOf<String, Int>()
    private val units = mutableMapOf<String, Int>()

    fun add(row: NotificationRecord) {
        when (row.section) {
            BadgeSections.HOME -> home(row)
            BadgeSections.TOOLS -> tool(row)
            BadgeSections.CNC -> if (row.tool == NotificationRecord.CALL_TOOL || row.conversationKey != null) count(row)
            BadgeSections.SETTINGS -> if (row.unit in BadgeSections.settingsUnits) {
                count(row)
                // By unit too, so each approval row can wear its own count.
                units.add(row.unit)
            }
            else -> if (row.section.isNotBlank()) count(row)
        }
    }

    private fun home(row: NotificationRecord) {
        val calendar = row.tool == NotificationRecord.CALENDAR_TOOL
        if (calendar && row.calendarEnd == null) return
        count(row)
        if (row.unit.isNotBlank()) units.add(row.unit)
    }

    private fun tool(row: NotificationRecord) {
        if (row.tool == NotificationRecord.AD_DASHBOARD_TOOL) return
        count(row)
        if (row.tool.isNotBlank()) tools.add(wireToolToIdentifier(row.tool))
        if (row.unit.isNotBlank()) units.add(row.unit)
    }

    private fun count(row: NotificationRecord) = sections.add(row.section)

    fun counts() = BadgeCounts(bySection = sections, byTool = tools, byUnit = units)
}

/**
 * One grouped drill into the ledger — a screen asking for its own split
 * (`?section=cnc_label&group=tool` in the old endpoint's terms). Scope
 * fields left null are not filtered; the answer maps [BadgeDrilldownQuery.groupBy]'s
 * values (wire names, unmapped) to their unread.
 */
fun splitBadges(rows: Collection<NotificationRecord>, query: BadgeDrilldownQuery): Map<String, Int> {
    val out = mutableMapOf<String, Int>()
    rows.asSequence()
        .filter { it.counts }
        .filter { query.section == null || it.section == query.section }
        .filter { query.tool == null || it.tool == query.tool }
        .filter { query.unit == null || it.unit == query.unit }
        .filter { query.level1 == null || it.level1 == query.level1 }
        .filter { query.level2 == null || it.level2 == query.level2 }
        .forEach { row ->
            val key = row.field(query.groupBy)
            if (key.isNotBlank()) out.add(key)
        }
    return out
}

private fun NotificationRecord.field(name: String): String = when (name) {
    "section" -> section
    "tool" -> tool
    "unit" -> unit
    "level_1" -> level1
    "level_2" -> level2
    "level_3" -> level3
    "action" -> action
    "reference_id" -> referenceId
    else -> ""
}

private fun MutableMap<String, Int>.add(key: String) {
    this[key] = (this[key] ?: 0) + 1
}
