package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Card and Petty Cash settings documents — the web's `getSettings` /
 * `updateSettings` over each module's own `/settings` route, which Production
 * Setup's two spend modals read and write.
 *
 * On the card and cash services, not the account hub. The answer is the
 * document bare (`data: {…}`) or wrapped (`data: {value: {…}}`), and a stated
 * `status: 0` over a 200 is a refusal.
 *
 * ## What a save sends
 *
 * Only the keys whose value changed. A PATCH merges, so a body of the changed
 * keys leaves everything else the document holds — `request_cap`, a key this
 * client has never heard of, a coordinator list somebody else changed meanwhile
 * — exactly as it was. The keys it does send keep the unknown fields of every
 * row they carry ([SpendSettings]'s `extra`).
 *
 * ## A key the server does not keep
 *
 * These services answer `status: 1` to a PATCH whose key is not a column and
 * store nothing, so the page says "saved" over a lost edit. After a write the
 * document is read again; a key that was sent with content and is missing from
 * what the server now holds is a failure, named. A read that fails after a good
 * write is not an error — the write stands — and the edit is kept as the
 * baseline.
 */
internal class HubSpendSource(private val apiClient: ApiClient, private val config: AppConfig) {

    private fun url(kind: SpendKind): String = when (kind) {
        SpendKind.Cards -> "${config.baseUrl(ZillitService.CardExpenses)}/api/v2/card-expenses/settings"
        SpendKind.Cash -> "${config.baseUrl(ZillitService.CashExpenses)}/api/v2/cash-expenses/settings"
    }

    suspend fun load(kind: SpendKind): ZillitResult<SpendSettings> = fetch(kind).flatMap { document ->
        ZillitResult.Success(parseSpend(kind, document))
    }

    suspend fun save(saved: SpendSettings, edited: SpendSettings): ZillitResult<SpendSettings> {
        val kind = edited.kind
        val before = saved.wire()
        val body = JsonObject(edited.wire().filter { (key, value) -> before[key] != value })
        if (body.isEmpty()) return ZillitResult.Success(edited)
        val written = apiClient.envelope(
            verb = HttpVerb.Patch,
            url = url(kind),
            module = RequestModule.ProjectUser,
            body = body,
        ).flatMap { envelope -> if (envelope.status == REFUSED) refusal(envelope) else ZillitResult.Success(Unit) }
        return when (written) {
            is ZillitResult.Failure -> written
            is ZillitResult.Success -> verified(kind, edited, body)
        }
    }

    private suspend fun verified(
        kind: SpendKind,
        edited: SpendSettings,
        sent: JsonObject,
    ): ZillitResult<SpendSettings> {
        val document = (fetch(kind) as? ZillitResult.Success)?.data ?: return ZillitResult.Success(edited)
        val lost = sent.filter { (key, value) -> value.hasContent() && !document.holds(key) }.keys
        return if (lost.isEmpty()) {
            ZillitResult.Success(parseSpend(kind, document))
        } else {
            ZillitResult.Failure(ZillitError.Validation(str(S.desktop_hub_sp_kept_not, lost.joinToString(", "))))
        }
    }

    private suspend fun fetch(kind: SpendKind): ZillitResult<JsonObject?> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url(kind),
        module = RequestModule.ProjectUser,
    ).flatMap { envelope ->
        if (envelope.status == REFUSED) refusal(envelope) else ZillitResult.Success(envelope.data.settingsDocument())
    }

    private fun <T> refusal(envelope: ApiEnvelope): ZillitResult<T> =
        ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))

    private companion object {
        private const val HTTP_OK = 200
        private const val REFUSED = 0
    }
}

/** Whether a value carries anything — an empty list, object or text clears, and is not checked for. */
private fun JsonElement.hasContent(): Boolean = when (this) {
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
    is JsonNull -> false
    is JsonPrimitive -> content.isNotBlank()
}

private fun JsonObject.holds(key: String): Boolean = this[key]?.takeUnless { it is JsonNull } != null
