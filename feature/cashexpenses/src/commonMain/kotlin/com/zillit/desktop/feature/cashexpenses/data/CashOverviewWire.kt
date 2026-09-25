package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCategorySpend
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentStats
import com.zillit.desktop.feature.cashexpenses.domain.RecentClaim
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A receipt row of `GET /claims/overview/my` — see [RecentClaim]. */
@Serializable
internal data class RecentClaimDto(
    @SerialName("id") val id: String? = null,
    @SerialName("batch_reference") val batchReference: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("settlement_type") val settlementType: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("receipt_date") val receiptDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("gross_amount") val grossAmount: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("batch_status") val batchStatus: String? = null,
    @SerialName("status") val status: String? = null,
) {
    fun toDomain(): RecentClaim? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return RecentClaim(
            id = identifier,
            batchReference = batchReference.orEmpty(),
            description = description.orEmpty(),
            settlementType = settlementType,
            category = category,
            date = receiptDate.toEpochMillisOrNull() ?: createdAt.toEpochMillisOrNull(),
            grossAmount = grossAmount.toAmount(),
            currency = currency,
            status = BatchStatus.from(batchStatus?.takeIf { it.isNotBlank() } ?: status),
        )
    }
}

@Serializable
internal data class DepartmentStatsDto(
    @SerialName("active_floats") val activeFloats: String? = null,
    @SerialName("active_float_names") val activeFloatNames: List<String>? = null,
    @SerialName("cash_issued") val cashIssued: String? = null,
    @SerialName("receipts_approved") val receiptsApproved: String? = null,
    @SerialName("oop_count") val oopCount: String? = null,
    @SerialName("oop_pending") val oopPending: String? = null,
    @SerialName("oop_approved") val oopApproved: String? = null,
) {
    fun toDomain() = DepartmentStats(
        activeFloats = activeFloats.toAmount().toInt(),
        activeFloatHolders = activeFloatNames.orEmpty().filter { it.isNotBlank() },
        cashIssued = cashIssued.toAmount(),
        receiptsApproved = receiptsApproved.toAmount(),
        outOfPocketCount = oopCount.toAmount().toInt(),
        outOfPocketPending = oopPending.toAmount().toInt(),
        outOfPocketApproved = oopApproved.toAmount().toInt(),
    )
}

@Serializable
internal data class DepartmentCategoryDto(
    @SerialName("category") val category: String? = null,
    @SerialName("pct") val percent: String? = null,
    @SerialName("amount") val amount: String? = null,
) {
    fun toDomain(): DepartmentCategorySpend? {
        val name = category?.takeIf { it.isNotBlank() } ?: return null
        return DepartmentCategorySpend(name, percent.toAmount(), amount.toAmount())
    }
}
