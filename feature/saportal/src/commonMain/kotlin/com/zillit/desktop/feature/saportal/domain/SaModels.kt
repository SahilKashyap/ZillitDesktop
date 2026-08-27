package com.zillit.desktop.feature.saportal.domain

/**
 * The supporting artiste's own view of their work — vouchers, pay, queries.
 *
 * The shapes come from `sae-server`'s `services/v2/sa-portal.js`, as written
 * up in the Android team's design spec and confirmed against the live
 * develop service (2026-08-26). Every field this client reads is modelled;
 * the rest of each document is ignored rather than mirrored, because this
 * surface only ever displays.
 */

/** How far along a day's voucher is. */
enum class VoucherStatus(val wire: String, val label: String) {
    Pending("pending", "Awaiting signature"),
    Signed("signed", "Signed"),
    Paid("paid", "Paid"),
    Unknown("", "Unknown"),
    ;

    companion object {
        fun from(wire: String?): VoucherStatus {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
        }
    }
}

/**
 * One shoot day, shaped as a voucher.
 *
 * [dayStatus] is the raw day record's state and is **not** interchangeable
 * with [status]: the server only accepts a signature while the day itself is
 * `submitted`, so the sign control follows [signable], not the pill.
 */
data class Voucher(
    val id: String,
    /** `VCH-<ref>-<YYYYMMDD>`, as the server mints it. */
    val code: String = "",
    val shootDate: Long? = null,
    val timezone: String = "",
    val callTime: String = "",
    val wrapTime: String = "",
    val minutesWorked: Int = 0,
    val gross: Double = 0.0,
    val holiday: Double = 0.0,
    /** ISO code; the display layer maps it to a symbol. */
    val currency: String = "",
    val status: VoucherStatus = VoucherStatus.Unknown,
    val dayStatus: String = "",
    val category: String = "",
    val role: String = "",
    val weekStarting: Long? = null,
    val attendanceStatus: String = "",
) {
    /** Only a submitted day may be signed — the server refuses any other. */
    val signable: Boolean get() = dayStatus.equals("submitted", ignoreCase = true)

    val hoursWorked: Double get() = minutesWorked / MINUTES_PER_HOUR

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
    }
}

/** A meal break taken on a shoot day. */
data class MealBreak(val label: String = "", val from: String = "", val to: String = "")

/** One priced line on a voucher — an overtime band, an allowance, a rate. */
data class VoucherLine(
    val label: String = "",
    val amount: Double = 0.0,
    val quantity: Double? = null,
)

/** The artiste's signature on a voucher, once given. */
data class VoucherSignature(
    val typedName: String = "",
    val consentAccuracy: Boolean = false,
    val consentESign: Boolean = false,
    val signedAt: Long? = null,
    val signedByUserId: String = "",
) {
    /** The server requires *both* consents; one without the other is refused. */
    val complete: Boolean get() = consentAccuracy && consentESign
}

/** A voucher opened in full. */
data class VoucherDetail(
    val voucher: Voucher,
    val dayType: String = "",
    val meals: List<MealBreak> = emptyList(),
    val ratesAndOvertime: List<VoucherLine> = emptyList(),
    val allowances: List<VoucherLine> = emptyList(),
    val notes: String = "",
    val payrollStatus: String = "",
    val signature: VoucherSignature? = null,
    val signStatus: String = "",
)

/** What the artiste is owed, grouped by the week it was worked. */
data class PayRun(
    val weekStarting: Long? = null,
    val gross: Double = 0.0,
    val days: Int = 0,
    val status: String = "",
)

/** Holiday pay: what has accrued and what has been paid. */
data class HolidayPot(
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val accrued: Double = 0.0,
)

data class PayStatement(
    val runs: List<PayRun> = emptyList(),
    val holiday: HolidayPot = HolidayPot(),
)

/** The counts behind the dashboard's tiles. */
data class VoucherTally(
    val total: Int = 0,
    val pending: Int = 0,
    val signed: Int = 0,
    val paid: Int = 0,
)

/** One month of the earnings chart. */
data class EarningsPoint(val label: String, val gross: Double)

/**
 * The dashboard.
 *
 * [actionVoucher] is the one thing wanting attention and [nextBooking] the
 * next day of work — both nullable, and both the whole point of the screen:
 * an artiste opens this to answer "do I need to do something" and "when am
 * I next in".
 */
data class SaSummary(
    val vouchers: VoucherTally = VoucherTally(),
    val ytdGross: Double = 0.0,
    val totalGross: Double = 0.0,
    val holidayAccrued: Double = 0.0,
    val productionsCount: Int = 0,
    val earnings: List<EarningsPoint> = emptyList(),
    val actionVoucher: Voucher? = null,
    val nextBooking: Voucher? = null,
)

/** One line of the account checklist the portal shows on the profile. */
data class AccountCheck(
    val key: String,
    val label: String,
    val ok: Boolean,
    val detail: String = "",
)

/** The artiste's bank details, as far as this surface shows them. */
data class ArtisteBank(
    val name: String = "",
    val accountHolderName: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
    val ibanNumber: String = "",
    val swiftCode: String = "",
)

/**
 * The artiste's own record.
 *
 * The display name comes from the signed-in user's profile rather than
 * `personal_details` — the spec is explicit about it, because the two can
 * disagree and the profile is the one the person recognises.
 */
data class SaProfile(
    val id: String,
    val status: String = "",
    val isExternal: Boolean = false,
    val isMinor: Boolean = false,
    val refNumber: String = "",
    val artisteRef: String = "",
    val category: String = "",
    val engagementType: String = "",
    val agencyName: String = "",
    val currency: String = "",
    val bank: ArtisteBank = ArtisteBank(),
    val accountStatus: List<AccountCheck> = emptyList(),
) {
    /** What the profile screen leads with: anything still outstanding. */
    val outstanding: List<AccountCheck> get() = accountStatus.filterNot { it.ok }

    val complete: Boolean get() = accountStatus.isNotEmpty() && outstanding.isEmpty()
}

/** One message in a query thread. */
data class QueryMessage(
    val text: String = "",
    val at: Long? = null,
    val authorName: String = "",
    val authorRole: String = "",
    /** `artiste` or the production side — decides which way the bubble faces. */
    val whoType: String = "",
) {
    val fromArtiste: Boolean get() = whoType.equals("artiste", ignoreCase = true)
}

/** A question the artiste raised about one day. */
data class ArtisteQuery(
    val id: String,
    val topic: String = "",
    val title: String = "",
    val priority: String = "",
    val status: String = "",
    val voucherId: String = "",
    val voucherCode: String = "",
    val updated: Long? = null,
    val lastMessage: String = "",
    val messages: List<QueryMessage> = emptyList(),
) {
    val resolved: Boolean get() = status.equals("resolved", ignoreCase = true)
}
