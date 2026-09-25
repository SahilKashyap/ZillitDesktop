package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.TabBadgeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The card tool's unread, per page and per row.
 *
 * An accountant's rows file under the account hub, a cardholder's under the
 * card tool, both in the card unit (`constants.js:189-193`). Per page is
 * `level_1`; per row is the `level_3` inside it — the web's
 * `getCardTotalUnread(badges, level_1, id)`, summed over every `level_2`
 * action. A row read is `emitCardLevelRead`: the entity at `level_3`, and the
 * action label, when given, at `level_2` (`card-expenses-badge-helpers.js:63`).
 */
internal fun AppGraph.Ready.cardBadges(isAccountant: () -> Boolean): TabBadgeSource = object : TabBadgeSource {
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun tool() = if (isAccountant()) "account_hub_label" else CARD_UNIT

    private fun query(groupBy: String, level1: String? = null) = BadgeDrilldownQuery(
        groupBy = groupBy,
        section = BadgeSections.TOOLS,
        tool = tool(),
        unit = CARD_UNIT,
        level1 = level1,
    )

    override val counts: Flow<Map<String, Int>> = badgeStore.counts
        .map { badgeStore.split(query("level_1")) }
        .distinctUntilChanged()

    override val entityCounts: Flow<Map<String, Map<String, Int>>> = badgeStore.counts
        .map {
            badgeStore.split(query("level_1")).keys.associateWith { level1 ->
                badgeStore.split(query("level_3", level1)).filterKeys { it.isNotBlank() }
            }.filterValues { it.isNotEmpty() }
        }
        .distinctUntilChanged()

    override fun read(key: String) {
        val tool = tool()
        reads.launch { emitLevelRead(tool = tool, unit = CARD_UNIT, level1 = key) }
    }

    override fun readEntity(key: String, entityId: String, kind: String?) {
        val tool = tool()
        reads.launch { emitLevelRead(tool = tool, unit = CARD_UNIT, level1 = key, level2 = kind, level3 = entityId) }
    }
}

private const val CARD_UNIT = "card_expenses_label"
