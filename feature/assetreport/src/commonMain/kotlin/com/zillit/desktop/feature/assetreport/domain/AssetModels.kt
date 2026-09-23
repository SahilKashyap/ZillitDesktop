package com.zillit.desktop.feature.assetreport.domain

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
    /** The order's currency; amounts are converted from it for display. */
    val currency: String = "",
    val expenditureType: ExpenditureType = ExpenditureType.Unknown,
    /** A standalone tax line — rendered, excluded from every total. */
    val isTax: Boolean = false,
    val rentalStartMillis: Long? = null,
    val rentalEndMillis: Long? = null,
    val poNumber: String = "",
    val poId: String = "",
    /** The register record's id; null until the first save. */
    val assetId: String? = null,
    val category: AssetCategory = AssetCategory.None,
)

/**
 * One stored file — the Account Hub's canonical attachment model,
 * `{media, bucket, region, name, content_type, content_subtype, caption}`.
 *
 * `content_type` is a *family* on this wire (`image`, `document`), not a MIME
 * type, and `content_subtype` the extension; the web derives the real type
 * from the extension for the same reason.
 */
data class AssetAttachment(
    val media: String,
    val bucket: String = "",
    val region: String = "",
    val name: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
    val caption: String = "",
) {
    /** The name's extension, or the stored subtype when the name has none. */
    val extension: String
        get() = extensionOf(name).ifBlank { contentSubtype.trim().lowercase() }

    val isImage: Boolean
        get() = contentType.equals(IMAGE_FAMILY, ignoreCase = true) ||
            contentType.startsWith("$IMAGE_FAMILY/", ignoreCase = true) ||
            extension in AssetFileRules.IMAGE_EXTENSIONS

    /** A half-upload — no key, bucket or region — points at no file and is refused. */
    val isComplete: Boolean
        get() = media.isNotBlank() && bucket.isNotBlank() && region.isNotBlank()

    val displayName: String
        get() = name.ifBlank { media.substringAfterLast('/') }.ifBlank { str(S.attachment) }

    companion object {
        const val IMAGE_FAMILY = "image"
        const val DOCUMENT_FAMILY = "document"
    }
}

/** One register record — the mutable half of a row. */
data class AssetRecord(
    val id: String = "",
    val lineItemId: String = "",
    val category: AssetCategory = AssetCategory.None,
    /** ONE free-text note, not a thread. */
    val comments: String = "",
    /** A user id — resolved to "Name · Designation" where the crew list knows it. */
    val commentBy: String = "",
    val commentAtMillis: Long? = null,
    val attachments: List<AssetAttachment> = emptyList(),
)

/** `Keep` / `Sell`, capitalised on the wire; empty clears. */
enum class AssetCategory(val wire: String, private val subtitleKey: String) {
    None("", ""),
    Keep("Keep", S.asset_keep_sub),
    Sell("Sell", S.asset_sell_sub),
    ;

    val subtitle: String get() = if (subtitleKey.isEmpty()) "" else str(subtitleKey)

    companion object {
        /** The two a person can pick, in the web's order. */
        val choices: List<AssetCategory> = listOf(Keep, Sell)

        fun fromWire(value: String?): AssetCategory =
            entries.firstOrNull { it != None && it.wire.equals(value?.trim(), ignoreCase = true) } ?: None
    }
}

/**
 * `Purchase` / `Rent` / `Consumption` on the wire, read as the web labels them:
 * "Rent" is *Rental* and "Consumption" is *Consumables*. Android's tolerance
 * (`rental`, `consume`, any case) is kept — strictness against a live wire only
 * manufactures Unknowns.
 */
enum class ExpenditureType(val wire: String, private val labelKey: String) {
    Purchase("Purchase", S.ah_exp_purchase),
    Rent("Rent", S.desktop_rental),
    Consumption("Consumption", S.ah_exp_consumption),
    Unknown("", ""),
    ;

    val label: String get() = if (labelKey.isEmpty()) "" else str(labelKey)

    companion object {
        fun fromWire(value: String?): ExpenditureType = when (value?.trim()?.lowercase()) {
            "purchase" -> Purchase
            "rent", "rental" -> Rent
            "consumption", "consume", "consumables" -> Consumption
            else -> Unknown
        }
    }
}

/**
 * Rights from `asset_report_tool`.
 *
 * Editing follows Android's rule — `posting_access` (or admin) gates the
 * category, the note and the attachments; the controls stay on screen for
 * everyone and a press without the right asks an administrator. The web
 * lets any viewer edit, which reads like an omission rather than a decision.
 *
 * [privileged] is the web's and Android's own word: an accountant or anyone
 * who can post picks departments, and only their exports carry
 * `department_ids` — everyone else is scoped to their department server-side.
 */
data class AssetViewer(
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val isAccountant: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !canPost && !isAdmin
    val mayEdit: Boolean get() = isAdmin || canPost
    val privileged: Boolean get() = isAccountant || mayEdit

    companion object {
        const val TOOL_IDENTIFIER = "asset_report_tool"

        fun from(permissions: ProjectPermissions, isAccountant: Boolean = false): AssetViewer {
            // Before the tools call lands every right reads false; that is
            // "not known yet", not a refusal.
            if (permissions.tools.isEmpty()) return AssetViewer(isAccountant = isAccountant)
            return AssetViewer(
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                isAccountant = isAccountant,
                ready = true,
            )
        }
    }
}

/** `photo.JPG` → `jpg`; nothing for a name without one (or a leading-dot name). */
fun extensionOf(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot <= 0) "" else fileName.substring(dot + 1).trim().lowercase()
}
