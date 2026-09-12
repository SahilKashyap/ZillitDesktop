package com.zillit.desktop.feature.purchaseorder.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult

/**
 * The production's purchase-order settings — the web's `POSettings` document
 * at `/api/v2/purchase-orders/settings`.
 *
 * The Account Hub's Production Setup edits three of these sections in a modal
 * over the same document; the tool's own Settings tab, ported from the web's
 * `POSettings.jsx`, is where the asset register rule and the auto-assignment
 * rules are edited as well. Each card saves on its own, as the web's do.
 */
data class PoSettings(
    val descriptionFormat: PoDescriptionFormat = PoDescriptionFormat.Default,
    /** Absent means on — only an explicit false turns auto-splitting off. */
    val autoSplitRentals: Boolean = true,
    val splitType: PoSplitType = PoSplitType.Default,
    val numberPrefix: String = "",
    /** The terms and conditions issued with every order, or null when none is set. */
    val termsDocument: PoAttachment? = null,
    /** Round-tripped, never shown: amendments are paused behind the web's `AMENDMENTS_ENABLED = false`. */
    val allowAmendAfterApproval: Boolean = false,
    val assetFilters: AssetFilters = AssetFilters(),
) {
    companion object {
        const val PREFIX_MAX = 8

        /** Under the prefix field — the web's `PO_PREFIX_HINT`. */
        const val PREFIX_HINT =
            "Goes at the start of every new PO number. POs you've already created keep their existing numbers."

        /** The currency the settings' amounts print in until the production says otherwise. */
        const val DEFAULT_CURRENCY = "GBP"

        /** Upper case, letters and digits only, at most eight — the web's `normalizePoPrefix`. */
        fun normalisePrefix(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }.take(PREFIX_MAX)
    }
}

/** How a posted line's ledger description is built from the PO line and the posting date. */
enum class PoDescriptionFormat(val wire: String, val label: String, val sample: String) {
    DayMonthItem("DDMON_ITEM", "DDMON → ITEM", "03MAR ALEXA MINI LF HIRE"),
    DayMonthNumericItem("DDMM_ITEM", "DDMM → ITEM", "03/03 ALEXA MINI LF HIRE"),
    ItemDayMonth("ITEM_DDMON", "ITEM → DDMON", "ALEXA MINI LF HIRE 03MAR"),

    /** Decoded when stored, never offered — the web lists the three above only. */
    Custom("CUSTOM", "Custom", "Define your own pattern"),
    ;

    companion object {
        val Default = DayMonthItem

        /** The formats the settings offer, in the web's order. */
        val offered: List<PoDescriptionFormat> get() = entries.filter { it != Custom }

        fun from(wire: String?): PoDescriptionFormat = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** How a rental order is split into periods when it posts. */
enum class PoSplitType(val wire: String, val label: String, val days: Int) {
    Weekly("weekly", "Weekly", days = 7),
    Daily("daily", "Daily", days = 1),
    // A calendar month is not a fixed number of days, and a rental split does
    // not need it to be: the web divides the window evenly and clips the last
    // period to the line's own end, so 30 is the cadence, not a claim about
    // February.
    Monthly("monthly", "Monthly", days = 30),
    FourWeek("four_week", "Four Week", days = 28),
    ;

    companion object {
        val Default = Weekly

        fun from(wire: String?): PoSplitType = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** The expenditure types the asset register rule can be narrowed to — the web's `ASSET_EXP_TYPES`. */
enum class AssetExpenditureType(val wire: String, val label: String) {
    Purchase("Purchase", "Purchase"),

    /** The stored value stays the server's enum; only the wording changed. */
    Consumption("Consumption", "Consumables"),
    ;

    companion object {
        fun labelFor(wire: String): String = entries.firstOrNull { it.wire == wire }?.label ?: wire
    }
}

/**
 * Which line items on posted and closed orders qualify as assets — the web's
 * `asset_filters`.
 *
 * The bounds are kept as typed so the field can hold "1,500" while it is
 * edited; a blank bound is no bound. Every sub-rule empty is "no constraint",
 * and the wire collapses that to null.
 */
data class AssetFilters(
    val priceLow: String = "",
    val priceHigh: String = "",
    /** The server's enum values; empty means every type. */
    val expTypes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val low: Double? get() = priceLow.toAmount()
    val high: Double? get() = priceHigh.toAmount()

    val isEmpty: Boolean get() = low == null && high == null && expTypes.isEmpty() && tags.isEmpty()

    /** Why the rule cannot be saved, or null — the web's `assetFilterError`. */
    val error: String?
        get() {
            val lowBound = low
            val highBound = high
            return when {
                lowBound != null && highBound != null && lowBound > highBound ->
                    "Price low must not exceed price high."
                lowBound != null && lowBound < 0 -> "Price low must be zero or more."
                highBound != null && highBound < 0 -> "Price high must be zero or more."
                else -> null
            }
        }

    /** Spells the rule back out, so "no constraint" cannot pass for "nothing saved" — `buildAssetSummary`. */
    fun summary(symbol: String): String {
        val lowBound = low
        val highBound = high
        val parts = mutableListOf<String>()
        when {
            lowBound != null && highBound != null ->
                parts += "total ${amount(symbol, lowBound)}–${amount(symbol, highBound)}"
            lowBound != null -> parts += "total ≥ ${amount(symbol, lowBound)}"
            highBound != null -> parts += "total ≤ ${amount(symbol, highBound)}"
        }
        if (expTypes.isNotEmpty()) parts += expTypes.joinToString(" or ") { AssetExpenditureType.labelFor(it) }
        if (tags.isNotEmpty()) parts += "tagged ${tags.joinToString(" or ")}"
        if (parts.isEmpty()) return "Every line item on a posted or closed PO — no constraint set."
        return "Lines on a posted or closed PO matching ${parts.joinToString(", and ")}."
    }
}

/**
 * One auto-assignment rule — who an order lands with when its conditions match.
 *
 * `/api/v2/account-hub/assignment-rules`, module `purchase_orders`. Conditions
 * OR: any department, vendor or nominal in the lists, or an amount at or over
 * [amountMin], sends the order to [assignTo].
 */
data class PoAssignmentRule(
    val id: String,
    val departments: List<String> = emptyList(),
    val vendors: List<String> = emptyList(),
    val nominalCodes: List<String> = emptyList(),
    /** As typed; blank is no minimum. */
    val amountMin: String = "",
    val assignTo: String = "",
    val isActive: Boolean = true,
    val priority: Int = 0,
    /** Whether the server holds this row; a new one has a local id until it is saved. */
    val persisted: Boolean = false,
) {
    val amountMinValue: Double? get() = amountMin.toAmount()

    /** "2 depts | 1 vendor | ≥ £500", or what the web says when nothing is set — `buildRuleSummary`. */
    fun summary(symbol: String): String {
        val parts = mutableListOf<String>()
        if (departments.isNotEmpty()) parts += "${departments.size} dept${if (departments.size > 1) "s" else ""}"
        if (vendors.isNotEmpty()) parts += "${vendors.size} vendor${if (vendors.size > 1) "s" else ""}"
        if (nominalCodes.isNotEmpty()) parts += "${nominalCodes.size} nominal"
        amountMinValue?.let { parts += "≥ ${amount(symbol, it)}" }
        return if (parts.isEmpty()) "No condition set" else parts.joinToString(" | ")
    }

    companion object {
        /** A rule not yet saved carries one of these; the server mints the real id on create. */
        const val LOCAL_ID_PREFIX = "temp-"
    }
}

/** Someone on the accounts team an order can be routed to. */
data class PoTeamMember(val id: String, val name: String, val role: String = "") {
    /** "Jane Smith (Production Accountant)" — how the web's pickers print one. */
    val label: String get() = if (role.isBlank()) name else "$name ($role)"
}

data class PoDepartment(val id: String, val name: String)

/** A postable line of the chart, offered to a rule by its code. */
data class PoNominal(val code: String, val name: String = "") {
    /** "2400 — Camera — Equipment Hire", the web's `coaLabel`. */
    val label: String get() = if (name.isBlank()) code else "$code — $name"
}

/** GET settings answers the document and the module's rules together. */
data class PoSettingsBundle(val settings: PoSettings, val rules: List<PoAssignmentRule>)

/**
 * The cadence a rental line splits at — the web's `poSplitPeriodType`.
 *
 * Auto-split on means the configured type; off, or settings that never loaded,
 * means the legacy monthly. Derived here rather than at each call site because
 * the purchase order form and the processing page both split rentals and must
 * not disagree about how.
 */
val PoSettings.splitCadence: PoSplitType
    get() = if (autoSplitRentals) splitType else PoSplitType.Monthly

/** The people and departments the rule pickers offer — the host's crew list, the web's `useAccountHubUsers`. */
interface PoSettingsPeople {
    /** The accounts team — who an order can be assigned to. */
    suspend fun team(): List<PoTeamMember>

    suspend fun departments(): List<PoDepartment>

    /**
     * Everyone accepted on the production, for turning an id into a name.
     *
     * Separate from [team] on purpose: [team] is who a rule or a reassignment
     * may hand an order *to*, which is the accounts department only, while an
     * order's raiser is usually in another department entirely. Defaulted to
     * [team] so a host that has not wired it still names the accountants.
     */
    suspend fun everyone(): List<PoTeamMember> = team()
}

/** A document the user chose but has not uploaded yet. */
data class PoPickedFile(val name: String, val bytes: Long, val handle: String)

/**
 * Choosing and storing the terms document — a host seam, because the picker is
 * a desktop file dialog and the upload goes straight to storage. Null on the
 * view model leaves the block read-only.
 */
interface PoTermsFiles {
    /** Null when the user cancelled; a refusal is reported through [onRefused]. */
    suspend fun pick(onRefused: (String) -> Unit): PoPickedFile?

    suspend fun upload(file: PoPickedFile): ZillitResult<PoAttachment>
}

/** "£1,500" or "£1,500.50" — the web's `groupAmountAuto`: grouped, up to two decimals, none when whole. */
internal fun amount(symbol: String, value: Double): String =
    symbol + Money.format(value, currencyCode = null, decimals = 2).removeSuffix(".00")

/** A typed amount — grouping commas and a leading symbol tolerated; blank is null. */
internal fun String.toAmount(): Double? =
    trim().replace(",", "").trimStart { !it.isDigit() && it != '-' && it != '.' }.toDoubleOrNull()
