package com.zillit.desktop.feature.talentdeals.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.talentdeals.domain.TalentRefresh
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The talent-contract events (BE 2026-09-23), on the Account Hub's socket:
 * created / updated / deleted, to the posting / admin / accountant set and the
 * linked member (`accountHubListeners.js` `DEAL_MEMO_HANDLERS`).
 *
 * The frame is deliberately slim — reference, person, role, status, territory,
 * union, the document's NAME, updated_at, deleted_at — none of the money
 * fields and no file location, so screens REFETCH rather than patch. Socket
 * only, no bell entry: nobody is asked to act on a filed contract; an open
 * list just stops being stale.
 *
 * Kept their Deal Memo names when the tool moved out of Deal Memo; the web's
 * `useCustomEventListener` aliases (`ah:deal_memo:talent_contract:*`) are
 * browser-internal and never travel on the wire.
 */
internal val TALENT_CONTRACT_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("talent_contract:created"),
    SocketEventName("talent_contract:updated"),
    SocketEventName("talent_contract:deleted"),
)

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about, and which contract. The web drops cross-project frames
 * before any handler runs; the id is the first of `_id`, `id`.
 */
@Serializable
internal data class TalentSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("data") val data: JsonObject? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean = projectId == null || here == null || projectId == here

    val contractId: String?
        get() = listOf("_id", "id").firstNotNullOfOrNull { key ->
            (data?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        }

    fun toRefresh(): TalentRefresh = TalentRefresh(contractId)
}
