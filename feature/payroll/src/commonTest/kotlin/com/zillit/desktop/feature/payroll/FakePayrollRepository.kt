package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.payroll.domain.BatchOutcome
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.DealCoding
import com.zillit.desktop.feature.payroll.domain.JournalCoding
import com.zillit.desktop.feature.payroll.domain.JournalPosted
import com.zillit.desktop.feature.payroll.domain.JournalSubmission
import com.zillit.desktop.feature.payroll.domain.ManualClaim
import com.zillit.desktop.feature.payroll.domain.OverrideFlags
import com.zillit.desktop.feature.payroll.domain.PayrollAdjustmentRepository
import com.zillit.desktop.feature.payroll.domain.PayrollCompany
import com.zillit.desktop.feature.payroll.domain.PayrollJournalRepository
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollSettingsRepository
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PendingClaim
import com.zillit.desktop.feature.payroll.domain.ProcessingQueue
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** An in-memory payroll service that records every write it is asked for. */
internal class FakePayrollRepository(
    var paid: List<PayrollTimecard> = emptyList(),
    var run: List<PayrollTimecard> = emptyList(),
    var processing: List<PayrollTimecard> = emptyList(),
    var metadata: PayrollMetadata = PayrollMetadata(),
    var locked: String? = null,
    var flags: OverrideFlags = OverrideFlags(isAccountant = true, isApprover = false),
    override val refreshes: Flow<Unit> = emptyFlow(),
) : PayrollRepository {

    val calls = mutableListOf<String>()
    val submissions = mutableListOf<JournalSubmission>()
    var paidWeeks = mutableListOf<Long>()

    override val journal: PayrollJournalRepository = object : PayrollJournalRepository {
        override suspend fun coding(timecardIds: List<String>, weekStarting: Long) =
            ZillitResult.Success(JournalCoding())

        override suspend fun submit(submission: JournalSubmission): ZillitResult<JournalPosted> {
            submissions += submission
            return ZillitResult.Success(JournalPosted("PR-1", null))
        }
    }

    override val adjustments: PayrollAdjustmentRepository = object : PayrollAdjustmentRepository {
        override suspend fun pendingClaims(userId: String) = ZillitResult.Success(emptyList<PendingClaim>())
        override suspend fun attachBatch(timecardId: String, batchId: String) = record("attachBatch:$timecardId")
        override suspend fun attachManual(timecardId: String, claim: ManualClaim) = record("attachManual:$timecardId")
        override suspend fun detachClaim(timecardId: String, claim: ClaimLine) = record("detach:$timecardId")
        override suspend fun addDeduction(timecardId: String, deduction: ManualClaim) =
            record("addDeduction:$timecardId")
        override suspend fun removeDeduction(timecardId: String, deductionId: String) =
            record("removeDeduction:$deductionId")

        private fun record(call: String): ZillitResult<PayrollTimecard?> {
            calls += call
            return ZillitResult.Success(null)
        }
    }

    override val settings: PayrollSettingsRepository = object : PayrollSettingsRepository {
        override suspend fun metadata() = ZillitResult.Success(metadata)
        override suspend fun overrideFlags() = ZillitResult.Success(flags)
        override suspend fun lockedDate() = ZillitResult.Success(locked)
        override suspend fun companies() = ZillitResult.Success(emptyList<PayrollCompany>())
        override suspend fun activeDealCoding(userId: String) = ZillitResult.Success<DealCoding?>(null)
    }

    override suspend fun paidCrew(weekStarting: Long): ZillitResult<List<PayrollTimecard>> {
        paidWeeks += weekStarting
        return ZillitResult.Success(paid)
    }

    override suspend fun runQueue(weekStarting: Long) = ZillitResult.Success(run)
    override suspend fun processingQueue() = ZillitResult.Success(ProcessingQueue(null, processing))
    override suspend fun outstanding() = ZillitResult.Success(emptyList<PayrollTimecard>())
    override suspend fun outstandingFor(userId: String) = ZillitResult.Success(emptyList<PayrollTimecard>())

    override suspend fun timecard(timecardId: String): ZillitResult<PayrollTimecard> =
        (paid + run + processing).firstOrNull { it.id == timecardId }?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Success(PayrollTimecard(id = timecardId, userId = "u", status = TimecardStatus.Unknown))

    override suspend fun finalApprove(timecardIds: List<String>) = batch("finalApprove", timecardIds)
    override suspend fun lock(timecardIds: List<String>) = batch("lock", timecardIds)
    override suspend fun markPaidBatch(timecardIds: List<String>) = batch("markPaidBatch", timecardIds)

    override suspend fun unlock(timecardId: String) = single("unlock:$timecardId")
    override suspend fun override(timecardId: String, reason: String) = single("override:$timecardId:$reason")
    override suspend fun markPaid(timecardId: String) = single("markPaid:$timecardId")
    override suspend fun markUnpaid(timecardId: String) = single("markUnpaid:$timecardId")

    private fun batch(name: String, ids: List<String>): ZillitResult<BatchOutcome> {
        calls += "$name:${ids.joinToString(",")}"
        return ZillitResult.Success(BatchOutcome(marked = ids, skipped = 0, message = null))
    }

    private fun single(call: String): ZillitResult<String?> {
        calls += call
        return ZillitResult.Success(null)
    }
}
