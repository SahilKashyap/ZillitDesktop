package com.zillit.desktop.feature.talentdeals.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** What a create or update answers: the stored row's id, and the server's own words. */
data class TalentSaved(val id: String?, val message: String?)

/** Every talent-contract route the tool calls. */
interface TalentDealsRepository {

    /** The register changed under us — a `talent_contract:*` frame for this production. */
    val refreshes: Flow<TalentRefresh>

    /** Live contracts for the project, newest first as the server orders them. */
    suspend fun contracts(): ZillitResult<List<TalentContract>>

    suspend fun contract(id: String): ZillitResult<TalentContract>

    /** `POST /talent-contracts`; [body] is [TalentContractBody.build]'s. */
    suspend fun create(body: JsonObject): ZillitResult<TalentSaved>

    /** `PATCH /talent-contracts/:id` with the whole body, as the web sends it. */
    suspend fun update(id: String, body: JsonObject): ZillitResult<TalentSaved>

    /** Soft delete — the row keeps its history under `deleted_at`. The answer is the server's message. */
    suspend fun remove(id: String): ZillitResult<String?>

    /**
     * Which guilds represent a crew type in a territory. [crewType] is one of
     * `cast`, `director`, `producer` or `all` (see [TalentRoles.crewTypeFor]).
     */
    suspend fun unions(crewType: String, territory: String): ZillitResult<List<TalentUnion>>

    /** The AD dashboard's Exhibit G signing rows for a talent, by typed name, across every shoot day. */
    suspend fun exhibitG(name: String): ZillitResult<List<SigningDay>>
}
