package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cardexpenses.domain.CardAction
import com.zillit.desktop.feature.cardexpenses.domain.CardDetailsEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardServerNote
import com.zillit.desktop.feature.cardexpenses.domain.NewCardRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Every register and Card-tab write, answered with the server's own message.
 *
 * The bodies are the web's, key for key. Three differ from the older
 * single-purpose calls on the repository: the details edit and the
 * control-code correction both carry `updated_by` (`CardRegisterPage.jsx:591`,
 * `lib/constants.js:142`), and the crew's own request and re-submit send only
 * what a cardholder may set (`UserCardsPage.jsx:202-208, 249-254`) — the
 * provider, the bank and the control code are the accounts team's to assign.
 */
internal class CardActionCalls(private val apiClient: ApiClient, config: AppConfig) {

    private val cards = "${config.baseUrl(ZillitService.CardExpenses)}/api/v2/card-expenses/cards"

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per card write; the table is the point.
    suspend fun send(action: CardAction): ZillitResult<CardServerNote> = when (action) {
        is CardAction.Request -> noted(HttpVerb.Post, cards, action.request.body())
        is CardAction.CrewRequest -> noted(
            HttpVerb.Post,
            cards,
            buildJsonObject {
                put("user_id", JsonPrimitive(action.userId))
                putIfPresent("department_id", action.departmentId)
                put("proposed_limit", JsonPrimitive(action.proposedLimit))
                put("justification", JsonPrimitive(action.justification.trim()))
                put("currency", JsonPrimitive(action.currency))
            },
        )

        is CardAction.EditDetails -> noted(
            HttpVerb.Patch,
            "$cards/${action.cardId}",
            buildJsonObject {
                action.edit.body().forEach { (key, value) -> put(key, value) }
                put("updated_by", JsonPrimitive(action.updatedBy))
            },
        )

        // The crew's re-submit: back to `requested`, for the accounts team to
        // review again (`UserCardsPage.jsx:249-254`) — not the accountant's
        // `pending`, which would skip that review.
        is CardAction.CrewEdit -> noted(
            HttpVerb.Patch,
            "$cards/${action.cardId}",
            buildJsonObject {
                put("card_limit", JsonPrimitive(action.limit))
                put("status", JsonPrimitive(REQUESTED))
                put("justification", JsonPrimitive(action.justification.trim()))
                put("currency", JsonPrimitive(action.currency))
            },
        )

        // Deliberately minimal: no status and no balance, which the server
        // reads as a resubmit and answers by wiping the collected approvals.
        is CardAction.BsCode -> noted(
            HttpVerb.Patch,
            "$cards/${action.cardId}",
            buildJsonObject {
                put("bs_control_code", JsonPrimitive(action.code.trim()))
                put("updated_by", JsonPrimitive(action.updatedBy))
            },
        )

        is CardAction.Delete -> noted(HttpVerb.Delete, "$cards/${action.cardId}", null)
        is CardAction.Approve -> noted(
            HttpVerb.Post,
            "$cards/${action.cardId}/approve",
            buildJsonObject {
                action.step.nextTier?.let { put("tier_number", JsonPrimitive(it)) }
                put("total_tiers", JsonPrimitive(action.step.totalTiers))
                put("user_id", JsonPrimitive(action.userId))
            },
        )

        is CardAction.Reject ->
            noted(HttpVerb.Post, "$cards/${action.cardId}/reject", actorBody(action.userId, action.reason))

        is CardAction.Override ->
            noted(HttpVerb.Post, "$cards/${action.cardId}/override", actorBody(action.userId, action.reason))

        is CardAction.Activate -> noted(HttpVerb.Post, "$cards/${action.cardId}/activate", action.activation.body())
        is CardAction.Suspend -> noted(HttpVerb.Post, "$cards/${action.cardId}/suspend", null)
        is CardAction.Reactivate -> noted(HttpVerb.Post, "$cards/${action.cardId}/reactivate", null)

        // `card_type` lowercase and `physical_card_number`, not activation's
        // `full_card_number` — both fail silently if wrong; see the repository.
        is CardAction.AssignPhysical -> {
            val digits = action.number.filter(Char::isDigit)
            noted(
                HttpVerb.Patch,
                "$cards/${action.cardId}",
                buildJsonObject {
                    put("last4", JsonPrimitive(digits.takeLast(LAST_FOUR)))
                    put("card_type", JsonPrimitive(PHYSICAL))
                    put("physical_card_number", JsonPrimitive(digits))
                },
            )
        }
    }

    /**
     * A write whose envelope is read, not discarded: `status: 0` inside a 200
     * is the server refusing, with its reason in `message`.
     */
    private suspend fun noted(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<CardServerNote> =
        when (val sent = apiClient.envelope(verb, url, RequestModule.ProjectUser, body)) {
            is ZillitResult.Failure -> sent
            is ZillitResult.Success -> {
                val envelope = sent.data
                val elements = envelope.messageElements.orEmpty()
                if (envelope.status == 0) {
                    ZillitResult.Failure(
                        ZillitError.Http(
                            status = HTTP_OK,
                            serverMessage = envelope.message,
                            messageElements = elements,
                        ),
                    )
                } else {
                    ZillitResult.Success(CardServerNote(envelope.message?.takeIf { it.isNotBlank() }, elements))
                }
            }
        }

    /** The create body — `proposed_limit`, never `card_limit`; see `CardRepositoryImpl.requestCard`. */
    private fun NewCardRequest.body(): JsonObject = buildJsonObject {
        put("user_id", JsonPrimitive(holderId))
        put("proposed_limit", JsonPrimitive(proposedLimit))
        putIfPresent("currency", currency)
        putIfPresent("department_id", departmentId)
        putIfPresent("justification", justification)
        putIfPresent("company_id", companyId)
        putIfPresent("card_provider_id", providerId)
        putIfPresent("card_issuer", issuer)
        putIfPresent("bs_control_code", bsControlCode)
    }

    /** The accountant's resubmit; see `CardRepositoryImpl.updateCardDetails`. */
    private fun CardDetailsEdit.body(): JsonObject = buildJsonObject {
        put("card_limit", JsonPrimitive(limit))
        put("balance", JsonPrimitive(balance))
        put("card_provider_id", JsonPrimitive(providerId.orEmpty()))
        put("card_issuer", JsonPrimitive(issuer.orEmpty()))
        put("company_id", JsonPrimitive(companyId.orEmpty()))
        putIfPresent("currency", currency)
        put("bs_control_code", JsonPrimitive(bsControlCode.trim()))
        put("justification", JsonPrimitive(justification.trim()))
        put("status", JsonPrimitive(PENDING))
    }

    private fun actorBody(userId: String, reason: String): JsonObject = buildJsonObject {
        put("user_id", JsonPrimitive(userId))
        put("reason", JsonPrimitive(reason.trim()))
    }

    private companion object {
        const val PENDING = "pending"
        const val REQUESTED = "requested"
        const val PHYSICAL = "physical"
        const val LAST_FOUR = 4
        const val HTTP_OK = 200
    }
}
