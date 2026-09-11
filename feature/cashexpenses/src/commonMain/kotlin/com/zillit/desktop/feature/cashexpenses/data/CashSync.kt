package com.zillit.desktop.feature.cashexpenses.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.coroutines.flow.mapNotNull
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Everything another client can do to a petty-cash float, a claim batch or a
 * reconciliation — the 47 names both phones carry across `cash:batch:`,
 * `cash:float:`, `cash:recon:`, `cash:topup:` and `cash:settings:`.
 *
 * Reloaded coarsely, for the same reason as the card tool beside it: one
 * batch moving through escalation, verification, audit and posting touches
 * half a dozen of this tool's twenty-odd pages, and the `:crew` variants
 * duplicate several verbs for the crew's own view of the same float. Any of
 * these reloads the page on screen and nothing else. See [CARD_SYNC_EVENTS]
 * for the full argument.
 *
 * The module subscribed to nothing at all before 2026-09-09.
 */
val CASH_SYNC_EVENTS: List<SocketEventName> = listOf(
    "cash:batch:approval", "cash:batch:approved",
    "cash:batch:assigned", "cash:batch:claim-coded",
    "cash:batch:claim-unverified", "cash:batch:claim-verified",
    "cash:batch:created", "cash:batch:deescalated",
    "cash:batch:escalated", "cash:batch:overridden",
    "cash:batch:posted", "cash:batch:queried",
    "cash:batch:rejected", "cash:batch:submitted-for-audit",
    "cash:batch:submitted-for-coord", "cash:batch:submitted-for-senior-review",
    "cash:batch:verified", "cash:float:approval",
    "cash:float:approved", "cash:float:approved:crew",
    "cash:float:closed", "cash:float:closed:crew",
    "cash:float:collected", "cash:float:collected:crew",
    "cash:float:created", "cash:float:issued",
    "cash:float:issued:crew", "cash:float:overridden",
    "cash:float:overridden:crew", "cash:float:queried",
    "cash:float:queried:crew", "cash:float:ready-to-collect",
    "cash:float:ready-to-collect:crew", "cash:float:rejected",
    "cash:float:rejected:crew", "cash:float:return-recorded",
    "cash:float:return-recorded:crew", "cash:recon:created",
    "cash:recon:signed-off", "cash:recon:submitted-for-review",
    "cash:recon:updated", "cash:settings:updated",
    "cash:topup:completed", "cash:topup:needed",
    "cash:topup:paid", "cash:topup:partial",
    "cash:topup:skipped",
).map(::SocketEventName)

/** One pulse per burst of cash traffic, or empty without a bus. */
internal fun cashRefreshes(bus: SocketEventBus?): Flow<Unit> =
    bus?.onAny(CASH_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()


/**
 * The accountant changed what the float request form is.
 *
 * Its own flow rather than another entry in [CASH_SYNC_EVENTS], because that
 * one is a coarse "reload the page" pulse and this reloads a document instead.
 * Filtered by module: a change to the purchase order form is not this one's.
 */
val CASH_FORM_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("form_template:changed"),
    SocketEventName("form_template:reset"),
)

/** This form's module id, as the template service names it. */
const val CASH_FORM_MODULE = "cash_expenses"

/** Whether a form-template frame naming [module] is about this form. */
fun isCashFormFrame(module: String?): Boolean = module == null || module == CASH_FORM_MODULE

internal fun cashFormRefreshes(bus: SocketEventBus?): Flow<Unit> =
    bus?.onAny(CASH_FORM_SYNC_EVENTS, FormFrame.serializer())
        ?.mapNotNull { (_, frame) -> Unit.takeIf { isCashFormFrame(frame.formModule) } }
        ?.conflate()
        ?: emptyFlow()

/** The module a form-template frame names, wherever the envelope puts it. */
@Serializable
internal data class FormFrame(
    @SerialName("module") val module: String? = null,
    @SerialName("data") val data: FormFrameData? = null,
) {
    val formModule: String? get() = module ?: data?.module
}

@Serializable
internal data class FormFrameData(@SerialName("module") val module: String? = null)
