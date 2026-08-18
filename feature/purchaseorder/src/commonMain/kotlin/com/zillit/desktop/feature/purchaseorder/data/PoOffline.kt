package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.sync.RetryPolicy
import com.zillit.desktop.core.sync.SyncContext
import com.zillit.desktop.core.sync.SyncHandler
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncOutcome
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.purchaseorder.domain.LocalCopy
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** The outbox kind for an order raised while offline. */
const val PO_CREATE_KIND = "po.create"

/** What the outbox carries for a queued order: the form, and when it was filled in. */
@Serializable
data class QueuedPurchaseOrder(
    val order: NewPurchaseOrder,
    /** Who raised it, so the local row can say so before the server does. */
    val raisedBy: String?,
    val queuedAt: Long,
)

/**
 * Sends an order raised while offline.
 *
 * ## Looking before creating
 *
 * `POST purchase-orders` mints the id server-side and returns nothing the
 * client can key on, and an attempt may have landed before the network went
 * (a timeout on the way back). So the handler first reads the caller's own
 * orders and adopts one that is unmistakably this one — same vendor, same
 * description, same total, raised no earlier than the form was filled in —
 * and only creates when nothing matches. Once the backend accepts a client
 * `_id` on create (offline plan, phase 0) this becomes an exact match on id.
 *
 * On success it reads again to adopt the server's id into the operation's
 * result, so the local row can be swapped for the real one and anything that
 * depends on this order can address it.
 */
class PoSyncHandler(
    private val repository: PurchaseOrderRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val policy: RetryPolicy = RetryPolicy(),
) : SyncHandler {

    override val kind: String = PO_CREATE_KIND

    override suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome {
        val queued = runCatching { json.decodeFromString(QueuedPurchaseOrder.serializer(), operation.payload) }
            .getOrElse { return SyncOutcome.Failed(ZillitError.Serialization(it.message)) }

        val before = when (val mine = repository.myOrders()) {
            is ZillitResult.Success -> mine.data
            is ZillitResult.Failure -> return policy.outcomeFor(mine.error)
        }
        before.firstOrNull { it.isSameAs(queued) }?.let { return SyncOutcome.Done(result = it.id) }

        return when (val created = repository.create(queued.order)) {
            is ZillitResult.Failure -> policy.outcomeFor(created.error)
            is ZillitResult.Success -> {
                // Best effort: the create succeeded whether or not this read
                // does, so a failure here must not make the engine send twice.
                val adopted = repository.myOrders().getOrNull()?.firstOrNull { it.isSameAs(queued) }?.id
                SyncOutcome.Done(result = adopted)
            }
        }
    }
}

/**
 * Whether a server order is the queued one. Content plus a time bound —
 * the same vendor and description raised *before* the form was filled in is
 * a different order.
 */
internal fun PurchaseOrder.isSameAs(queued: QueuedPurchaseOrder): Boolean {
    val order = queued.order
    return vendorName.equals(order.vendorName, ignoreCase = true) &&
        description == order.description &&
        abs(total - order.total) < PENNY &&
        (order.currency == null || currency == null || currency == order.currency) &&
        (createdAt == null || createdAt >= queued.queuedAt - CLOCK_SKEW_MILLIS)
}

/**
 * The queued order as a row for the lists, so what was raised offline is
 * visible where it will appear once sent. No number, no server id.
 */
fun SyncOperation.toLocalOrder(json: Json = Json { ignoreUnknownKeys = true }): PurchaseOrder? {
    if (kind != PO_CREATE_KIND || state == SyncState.Done) return null
    val queued = runCatching { json.decodeFromString(QueuedPurchaseOrder.serializer(), payload) }.getOrNull()
        ?: return null
    val order = queued.order
    return PurchaseOrder(
        id = LOCAL_ID_PREFIX + id,
        number = "",
        vendorId = order.vendorId,
        vendorName = order.vendorName,
        description = order.description,
        departmentId = order.departmentId,
        companyId = order.companyId,
        status = PoStatus.Draft,
        currency = order.currency,
        total = order.total,
        vatTreatment = null,
        nominalCode = order.nominalCode,
        episode = order.episode,
        notes = order.notes,
        effectiveDate = order.effectiveDate,
        createdAt = queued.queuedAt,
        raisedBy = queued.raisedBy,
        assignedTo = null,
        reassignmentReason = null,
        deliveryAddress = null,
        lines = order.lines,
        local = LocalCopy(operationId = id, failed = state == SyncState.Failed, error = lastError),
    )
}

/** Marks an id as belonging to a row that exists only on this computer. */
const val LOCAL_ID_PREFIX = "local:"

private const val PENNY = 0.005
private const val CLOCK_SKEW_MILLIS = 5 * 60_000L
