package com.zillit.desktop.feature.cashexpenses.domain

/**
 * One receipt on the crew's My Overview, with its batch's reference and status.
 *
 * The web's `ClaimRow` (`PCCrewOverviewPage.jsx:89-111`): `#batch_reference ·
 * description`, then `settlement · category · receipt_date || created_at`,
 * `gross_amount`, and the pill from `batch_status || status`.
 */
data class RecentClaim(
    val id: String,
    val batchReference: String,
    val description: String,
    val settlementType: String?,
    val category: String?,
    /** `receipt_date`, else `created_at`. */
    val date: Long?,
    val grossAmount: Double,
    val currency: String?,
    /** The batch's status first — the receipt's own only when the batch's is missing. */
    val status: BatchStatus,
)

/** The Department Overview's tiles (`PCDeptViewPage.jsx:63-82`). */
data class DepartmentStats(
    val activeFloats: Int = 0,
    /** User ids of the active floats' holders, named on the tile. */
    val activeFloatHolders: List<String> = emptyList(),
    val cashIssued: Double = 0.0,
    val receiptsApproved: Double = 0.0,
    val outOfPocketCount: Int = 0,
    val outOfPocketPending: Int = 0,
    val outOfPocketApproved: Int = 0,
)

/** One bar of the department's Spend by Category; [percent] is 0–100 as the server sends it. */
data class DepartmentCategorySpend(
    val category: String,
    val percent: Double,
    val amount: Double,
)
