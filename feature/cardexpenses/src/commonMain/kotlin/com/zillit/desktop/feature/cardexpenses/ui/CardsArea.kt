package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CapSource
import com.zillit.desktop.feature.cardexpenses.domain.CapVerdict
import com.zillit.desktop.feature.cardexpenses.domain.CardReference
import com.zillit.desktop.feature.cardexpenses.domain.DealRate
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapGuard
import com.zillit.desktop.core.common.Money

/**
 * The register's and the Card tab's own state: which card is open full-page,
 * and the action dialogs the web mounts beside its tiles.
 *
 * One field on [CardUiState] rather than a dozen, so the other pages' state
 * does not have to know any of it exists.
 */
data class CardsArea(
    /** The card taking over the content column (`CardDetailModal.jsx`); null shows the grid. */
    val openCardId: String? = null,
    /** The card's History side panel. */
    val historyOpen: Boolean = false,
    /** The inline control-code edit on the detail page; null when not editing. */
    val bsDraft: String? = null,
    val reject: ReasonDraft? = null,
    val override: ReasonDraft? = null,
    val assignPhysical: NumberDraft? = null,
    /** The card whose delete is being confirmed. */
    val deleteCardId: String? = null,
    /** A cardholder's own request (`UserCardsPage.jsx:398-467`). */
    val crewRequest: CrewCardDraft? = null,
    /** A cardholder re-submitting their request; errors stay in the dialog (`:220-265`). */
    val crewEdit: CrewCardDraft? = null,
    /** The card an action is in flight on — its buttons disable, the web's `actionLoading`. */
    val actionCardId: String? = null,
    /** Currencies, rates, companies and banks — the production's; the host supplies them. */
    val reference: CardReference = CardReference(),
    /** The requester's weekly rate for a salary-basis cap; null when unknown (the cap falls back). */
    val deal: DealRate? = null,
)

/** A reason typed into the reject or override dialog. */
data class ReasonDraft(val cardId: String, val reason: String = "")

/** Sixteen digits typed into the Assign Physical Card dialog. */
data class NumberDraft(val cardId: String, val digits: String = "")

/** The crew's request and re-submit form: limit, currency, justification. */
data class CrewCardDraft(
    val cardId: String? = null,
    val limit: String = "",
    val currency: String = "",
    val justification: String = "",
    /** The re-submit shows its refusal inline rather than as a toast. */
    val error: String? = null,
) {
    val limitValue: Double get() = limit.trim().replace(",", "").toDoubleOrNull() ?: 0.0
}

/**
 * The advisory line under Proposed Limit, and the refusal on submit
 * (`useRequestCapGuard.js`). Null where there is no cap to speak of.
 */
internal fun capHint(verdict: CapVerdict, reference: CardReference): String? = when (verdict) {
    CapVerdict.NoCap -> null
    is CapVerdict.Unenforceable -> str(S.desktop_pc_cap_no_rate, verdict.currency)
    is CapVerdict.Enforced -> {
        val shown = Money.format(verdict.capInDefault, reference.defaultCurrency)
        when (verdict.source) {
            CapSource.Weekly -> str(S.desktop_ce_cards_cap_hint_weekly, shown)
            CapSource.Fallback -> str(S.desktop_ce_cards_cap_hint_fallback, shown)
            CapSource.Max -> str(S.desktop_pc_cap_hint, shown)
        }
    }
}

internal fun capError(verdict: CapVerdict, reference: CardReference): String? {
    if (verdict !is CapVerdict.Enforced) return null
    val shown = Money.format(verdict.capInDefault, reference.defaultCurrency)
    return when (verdict.source) {
        CapSource.Weekly -> str(S.desktop_ce_cards_cap_error_weekly, shown)
        CapSource.Fallback -> str(S.desktop_ce_cards_cap_error_fallback, shown)
        CapSource.Max -> str(S.desktop_ce_cards_cap_error, shown)
    }
}

/** The cap that applies to this viewer — the crew's `/metadata` copy, an accountant's `/settings` one. */
internal val CardUiState.requestCap: RequestCap?
    get() = if (viewer.isAccountant) settings?.requestCap else viewer.metadata.requestCap

internal fun CardUiState.capVerdict(currency: String?): CapVerdict =
    RequestCapGuard.verdict(requestCap, cardsArea.deal, currency, cardsArea.reference)

/** The card open full-page, if it is still on the register. */
internal val CardUiState.openCard get() = cards.firstOrNull { it.id == cardsArea.openCardId }

/** What a blank currency falls back to: the project default (`CardRegisterPage.jsx:669`). */
internal val CardUiState.defaultCurrency: String
    get() = cardsArea.reference.defaultCurrency.ifBlank { currency.orEmpty() }
