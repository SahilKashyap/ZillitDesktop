package com.zillit.desktop.core.badges

/**
 * iOS's composition over the ledger (`getNotificationRootObject` grouped into
 * `RootNotificationModel`, whose sums the tab bar wears —
 * `DashBoardTabBarViewController.addObserver`): **every unread row of a
 * section counts once on that section**. Home, Tools, C&C, Settings, SOS,
 * Global and Email are each a plain count of their rows.
 *
 * The finer maps are what the screens inside draw:
 *
 * - **Home**: also keyed by unit — a board's own count. Calendar invites
 *   count whether their event is pending or over (iOS files them under
 *   `pending`/`expired` and sums both into Home); see [calendarExpiryOf].
 * - **Tools**: keyed by tool identifier (a tile) and by unit (a board tool's
 *   tab strip — Accounts, Catering, Info, Location… — the way the web's
 *   `getMultipleUnitChatBadgesFromDB` groups a tool's rows by `unit`).
 * - **Settings**: keyed by unit, so each approval queue's row wears its own.
 * - **Tiles that roll up** ([TILE_ROLLUPS]): a Film Tools tile that opens
 *   several lists wears all of their counts, as iOS's tiles do
 *   (`FilmToolsViewController`): Script Distribution = full script + pages;
 *   Schedule Distribution = full + pages + one-line; Email = the whole email
 *   section. Each list keeps its own key too, for the tabs inside.
 * - **Tiles with a rule of their own** — what iOS's tile draws, not every
 *   row of the tool; the Tools total still counts every row:
 *   E-signature ([EsignTile]) and Drive (folder and file units only,
 *   `FilmToolsViewController.refreshDriveToolBadge`).
 *
 * This replaced Android's stricter rules, which dropped whole classes of row
 * from the tabs while the production card went on counting them: an
 * `ad_dashboard_label` row (iOS tallies it, `adDashboardCount`), a calendar
 * invite with no end time (iOS: pending), a C&C row naming no conversation,
 * a settings row outside the two approval queues.
 */
fun tallyBadges(rows: Collection<NotificationRecord>): BadgeCounts {
    val tally = Tally()
    rows.filter { it.counts }.forEach(tally::add)
    return tally.counts()
}

/**
 * The same composition, production by production — the production picker's
 * cards.
 *
 * One rule for a card and for the rail inside it. The card used to be a raw
 * unread-row count, so it wore rows no screen inside could ever badge: an
 * `ad_dashboard_label` row, a calendar invite with no end time, a chat row
 * that names no conversation, an approval row filed under a unit nobody
 * sums. The number then had nothing behind it and nothing that could clear
 * it — "badges on the card, nothing badged inside", which is exactly how it
 * was reported.
 */
fun tallyByProject(rows: Collection<NotificationRecord>): Map<String, BadgeCounts> =
    rows.groupBy { it.projectId }.mapValues { (_, own) -> tallyBadges(own) }

/** The three maps, filled one row at a time by the section's own rule. */
private class Tally {
    private val sections = mutableMapOf<String, Int>()
    private val tools = mutableMapOf<String, Int>()
    private val units = mutableMapOf<String, Int>()
    private val esign = EsignTile()

    fun add(row: NotificationRecord) {
        if (row.section.isBlank()) return
        count(row)
        when (row.section) {
            // By unit, so each board and each approval row can wear its own count.
            BadgeSections.HOME, BadgeSections.SETTINGS -> if (row.unit.isNotBlank()) units.add(row.unit)
            BadgeSections.TOOLS -> {
                if (row.tool.isNotBlank()) tile(row)
                if (row.unit.isNotBlank()) units.add(row.unit)
            }
            // iOS's Email tile is `email` — the section (FilmToolsViewController:949-950).
            BadgeSections.EMAIL -> tools.add(EMAIL_TOOL)
        }
    }

    private fun tile(row: NotificationRecord) {
        val identifier = wireToolToIdentifier(row.tool)
        when {
            identifier == EsignTile.IDENTIFIER -> esign.add(row)
            identifier == DRIVE_TOOL && row.unit !in DRIVE_TILE_UNITS -> Unit
            else -> {
                tools.add(identifier)
                TILE_ROLLUPS[identifier]?.let(tools::add)
            }
        }
    }

    private fun count(row: NotificationRecord) = sections.add(row.section)

    fun counts(): BadgeCounts {
        esign.total().takeIf { it > 0 }?.let { tools[EsignTile.IDENTIFIER] = it }
        return BadgeCounts(bySection = sections, byTool = tools, byUnit = units)
    }
}

/**
 * iOS's E-signature tile (`DocuSignBadgeReader.fetchTotalBadge`, ZL-21085 /
 * ZL-21329): every signer row, the sender's rows only once an envelope is
 * completed or rejected (draft and sent were never on the tile), no row
 * without an envelope (nothing could ever clear it), and one envelope
 * counted once across the two units — an admin who signs their own envelope
 * is not badged twice.
 */
private class EsignTile {
    private val perUnit = mutableMapOf<String, MutableMap<String, Int>>()

    fun add(row: NotificationRecord) {
        if (row.level3.isBlank()) return
        val counted = row.unit == SIGN_UNIT || (row.unit == MANAGE_UNIT && row.level1 in SETTLED)
        if (!counted) return
        perUnit.getOrPut(row.unit) { mutableMapOf() }.add(row.level3)
    }

    fun total(): Int {
        val perEnvelope = mutableMapOf<String, Int>()
        perUnit.values.forEach { envelopes ->
            envelopes.forEach { (envelope, count) -> perEnvelope[envelope] = maxOf(perEnvelope[envelope] ?: 0, count) }
        }
        return perEnvelope.values.sum()
    }

    companion object {
        const val IDENTIFIER = "e_signature_tool"
        const val SIGN_UNIT = "sign_documents_label"
        const val MANAGE_UNIT = "manage_envelopes_label"
        val SETTLED = setOf("completed", "rejected")
    }
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

/**
 * Lists whose count also lights a parent tile — iOS sums them into one tile
 * (`FilmToolsViewController.swift:867-873, 957-960`). A list with no tile of
 * its own was keyed under an identifier the grid never shows, so its rows
 * badged the Tools total and nothing anyone could open.
 */
private val TILE_ROLLUPS = mapOf(
    "script_distribution_pages_tool" to "script_distribution_tool",
    "schedule_distribution_pages_tool" to "schedule_distribution_tool",
    "schedule_oneline_tool" to "schedule_distribution_tool",
)

private const val EMAIL_TOOL = "email_tool"

private const val DRIVE_TOOL = "drive_tool"

/** The two Drive units iOS's tile sums; any other `drive_label` row is not an item anyone can open. */
private val DRIVE_TILE_UNITS = setOf("drive_folder_label", "drive_file_label")

private fun MutableMap<String, Int>.add(key: String) {
    this[key] = (this[key] ?: 0) + 1
}
