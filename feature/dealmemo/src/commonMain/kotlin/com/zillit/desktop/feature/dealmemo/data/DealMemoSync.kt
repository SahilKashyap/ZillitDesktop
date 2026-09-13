package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.dealmemo.domain.DealRefresh
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey.Approval
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey.Detail
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey.Mine
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey.Registry
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey.Templates
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deal-memo events the web subscribes on the shared socket, with the
 * refetch keys each one bridges to (`accountHubListeners.js:1584-1731`).
 *
 * `deal:tier-approved` is kebab-case and templates emit `deal:template:*` —
 * a wrong name fails silently, so these are the wire names verbatim.
 * `deal:deactivate_scheduled` deliberately leaves My Deal alone: crew never
 * see a scheduled deactivation.
 */
internal val DEAL_EVENT_KEYS: Map<SocketEventName, Set<DealRefreshKey>> = mapOf(
    "deal:created" to setOf(Registry, Approval),
    "deal:submitted" to setOf(Registry, Approval, Detail),
    "deal:tier-approved" to setOf(Registry, Approval, Detail),
    "deal:approved" to setOf(Registry, Approval, Mine, Detail),
    "deal:rejected" to setOf(Registry, Approval, Mine, Detail),
    "deal:activated" to setOf(Registry, Mine, Detail),
    "deal:completed" to setOf(Registry, Mine, Detail),
    "deal:cancelled" to setOf(Registry, Approval, Mine, Detail),
    "deal:deactivate_scheduled" to setOf(Registry, Detail),
    "deal:updated" to setOf(Registry, Detail),
    "deal:approval_reset" to setOf(Registry, Approval, Mine, Detail),
    "deal:deleted" to setOf(Registry, Approval, Mine),
    "deal:template:created" to setOf(Templates),
    "deal:template:updated" to setOf(Templates),
    "deal:template:deleted" to setOf(Templates),
).mapKeys { (name, _) -> SocketEventName(name) }

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about, and which deal. The web drops cross-project frames before
 * any handler runs; the deal id is the first of `deal_id`, `_id`, `id`.
 */
@Serializable
internal data class DealSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("data") val data: JsonObject? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here

    val dealId: String?
        get() = listOf("deal_id", "_id", "id").firstNotNullOfOrNull { key ->
            (data?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        }

    fun toRefresh(event: SocketEventName): DealRefresh? =
        DEAL_EVENT_KEYS[event]?.let { keys -> DealRefresh(keys, dealId) }
}
