package com.zillit.desktop.feature.assetreport.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The Asset Register: every POSTED/CLOSED purchase-order line as an asset
 * row, each carrying at most one register record (category, a note,
 * attachments). Two ids meet here and must never be conflated —
 * [AssetLine.lineItemId] joins the PO line; [AssetLine.assetId] names the
 * register record and is null until the first write.
 */
data class AssetLine(
    /** The LINE item id — the join key, not the register record's id. */
    val lineItemId: String,
    val description: String = "",
    val quantity: Double = 0.0,
    val unitPrice: Double = 0.0,
    /** Server-computed line total — authoritative, never recomputed. */
    val total: Double = 0.0,
    /** The account code — the "Code" column. */
    val account: String = "",
    /** A vendor ID — resolve to a name before display. */
    val vendorId: String = "",
    /** A department ID — likewise. */
    val departmentId: String = "",
    val currency: String = "",
    val expenditureType: ExpenditureType = ExpenditureType.Unknown,
    /** A standalone tax line — rendered, excluded from every total. */
    val isTax: Boolean = false,
    val rentalStartMillis: Long = 0,
    val rentalEndMillis: Long = 0,
    val poNumber: String = "",
    val poId: String = "",
    /** The register record's id; null until the first save. */
    val assetId: String? = null,
    val category: AssetCategory = AssetCategory.None,
)

/** One register record — the mutable half of a row. */
data class AssetRecord(
    val id: String = "",
    val lineItemId: String = "",
    val category: AssetCategory = AssetCategory.None,
    /** ONE free-text note, not a thread. */
    val comments: String = "",
    /** A user id — resolve to a name where the crew list knows it. */
    val commentBy: String = "",
    val commentAtMillis: Long = 0,
    val attachmentNames: List<String> = emptyList(),
)

/** `Keep` / `Sell`, capitalised on the wire; empty clears. */
enum class AssetCategory(val wire: String) {
    None(""),
    Keep("Keep"),
    Sell("Sell"),
    ;

    companion object {
        fun fromWire(value: String?): AssetCategory =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) && it != None } ?: None
    }
}

/**
 * `Purchase` / `Rent` / `Consumption` on the wire; "Rent" displays as
 * "Rental". Android's tolerance (`rental`, `consume`, any case) is kept —
 * strictness against a live wire only manufactures Unknowns.
 */
enum class ExpenditureType(val wire: String, val label: String) {
    Purchase("Purchase", "Purchase"),
    Rent("Rent", "Rental"),
    Consumption("Consumption", "Consumption"),
    Unknown("", ""),
    ;

    companion object {
        fun fromWire(value: String?): ExpenditureType = when (value?.trim()?.lowercase()) {
            "purchase" -> Purchase
            "rent", "rental" -> Rent
            "consumption", "consume" -> Consumption
            else -> Unknown
        }
    }
}

/**
 * Rights from `asset_report_tool`. Editing follows Android's stricter rule —
 * `posting_access` gates the category, the note and the save; the web lets
 * any viewer edit, which reads like an omission rather than a decision.
 * Export follows `download_access`, again Android's rule.
 */
data class AssetViewer(
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayEdit: Boolean get() = isAdmin || canPost
    val mayExport: Boolean get() = isAdmin || canDownload

    companion object {
        const val TOOL_IDENTIFIER = "asset_report_tool"

        fun from(permissions: ProjectPermissions): AssetViewer {
            if (permissions.tools.isEmpty()) return AssetViewer()
            return AssetViewer(
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

interface AssetRepository {
    /** Every eligible PO line — `GET purchase-orders/line-items`. */
    suspend fun lines(): ZillitResult<List<AssetLine>>

    /** The register record for one line, or null when none exists yet. */
    suspend fun recordForLine(line: AssetLine): ZillitResult<AssetRecord?>

    /**
     * First write: one POST carrying category and note together — the only
     * call that accepts both (`PATCH /:id` silently drops `comments`).
     */
    suspend fun create(
        poId: String,
        lineItemId: String,
        category: AssetCategory,
        comments: String,
    ): ZillitResult<AssetRecord>

    /** `PATCH /:id` — category only; the note has its own route. */
    suspend fun updateCategory(assetId: String, category: AssetCategory): ZillitResult<AssetRecord>

    /** `PATCH /:id/comment` — the note; empty clears note and stamp. */
    suspend fun updateComment(assetId: String, comments: String): ZillitResult<AssetRecord>

    /** Vendor id → display name, from the account-hub host. */
    suspend fun vendors(): ZillitResult<Map<String, String>>
}

/** Runs the export and lands the file — bytes are the host's business. */
fun interface AssetExport {
    suspend fun export(format: String): ZillitResult<Unit>
}

/**
 * The visible sum: tax lines out, and only when one currency covers every
 * counted row — a mixed-currency sum is a number that means nothing.
 */
fun assetTotal(lines: List<AssetLine>): Pair<Double, String>? {
    val counted = lines.filter { !it.isTax }
    if (counted.isEmpty()) return 0.0 to (lines.firstOrNull()?.currency ?: "")
    val currencies = counted.map { it.currency }.distinct()
    if (currencies.size > 1) return null
    return counted.sumOf { it.total } to currencies.single()
}

/** `1,234,567.89` — en-GB grouping, always two decimals. */
fun moneyLabel(amount: Double): String {
    val negative = amount < 0
    val cents = kotlin.math.round(kotlin.math.abs(amount) * 100).toLong()
    val whole = (cents / 100).toString().reversed().chunked(3).joinToString(",").reversed()
    val fraction = (cents % 100).toString().padStart(2, '0')
    return (if (negative) "-" else "") + whole + "." + fraction
}
