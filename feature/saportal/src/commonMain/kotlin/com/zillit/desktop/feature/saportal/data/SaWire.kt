package com.zillit.desktop.feature.saportal.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.feature.saportal.domain.AccountCheck
import com.zillit.desktop.feature.saportal.domain.ArtisteBank
import com.zillit.desktop.feature.saportal.domain.ArtisteQuery
import com.zillit.desktop.feature.saportal.domain.EarningsPoint
import com.zillit.desktop.feature.saportal.domain.HolidayPot
import com.zillit.desktop.feature.saportal.domain.MealBreak
import com.zillit.desktop.feature.saportal.domain.PayRun
import com.zillit.desktop.feature.saportal.domain.PayStatement
import com.zillit.desktop.feature.saportal.domain.QueryMessage
import com.zillit.desktop.feature.saportal.domain.SaProfile
import com.zillit.desktop.feature.saportal.domain.SaSummary
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherDetail
import com.zillit.desktop.feature.saportal.domain.VoucherLine
import com.zillit.desktop.feature.saportal.domain.VoucherSignature
import com.zillit.desktop.feature.saportal.domain.VoucherStatus
import com.zillit.desktop.feature.saportal.domain.VoucherTally
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shapes of `sae-server`'s sa-portal service.
 *
 * Everything is nullable and every DTO carries defaults: this service is
 * newer than most of the estate and its documents grow fields, so a missing
 * one must read as absent rather than fail the whole screen.
 *
 * Numbers arrive as numbers here (unlike the finance services, which quote
 * them), but the readers below still tolerate a quoted figure — costing
 * nothing and saving a blank column if that ever changes.
 */

@Serializable
internal data class VoucherDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("shoot_date") val shootDate: Double? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("call_time") val callTime: String? = null,
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("minutes_worked") val minutesWorked: Double? = null,
    @SerialName("gross") val gross: Double? = null,
    @SerialName("holiday") val holiday: Double? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("day_status") val dayStatus: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("week_starting") val weekStarting: Double? = null,
    @SerialName("attendance_status") val attendanceStatus: String? = null,
) {
    /** Null when the row has no id — an unopenable voucher is worse than an absent one. */
    fun toDomain(): Voucher? {
        val resolved = id ?: underscoreId ?: return null
        return Voucher(
            id = resolved,
            code = code.orEmpty(),
            shootDate = shootDate?.toLong(),
            timezone = timezone.orEmpty(),
            callTime = callTime.orEmpty(),
            wrapTime = wrapTime.orEmpty(),
            minutesWorked = minutesWorked?.toInt() ?: 0,
            gross = gross ?: 0.0,
            holiday = holiday ?: 0.0,
            currency = currency.orEmpty(),
            status = VoucherStatus.from(status),
            dayStatus = dayStatus.orEmpty(),
            category = category.orEmpty(),
            role = role.orEmpty(),
            weekStarting = weekStarting?.toLong(),
            attendanceStatus = attendanceStatus.orEmpty(),
        )
    }
}

@Serializable
internal data class MealDto(
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
) {
    fun toDomain() = MealBreak(
        label = label ?: name.orEmpty(),
        from = from.orEmpty(),
        to = to.orEmpty(),
    )
}

@Serializable
internal data class LineDto(
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("amount") val amount: Double? = null,
    @SerialName("total") val total: Double? = null,
    @SerialName("quantity") val quantity: Double? = null,
) {
    fun toDomain() = VoucherLine(
        label = label ?: name ?: description.orEmpty(),
        amount = amount ?: total ?: 0.0,
        quantity = quantity,
    )
}

@Serializable
internal data class SignatureDto(
    @SerialName("typed_name") val typedName: String? = null,
    @SerialName("consent_accuracy") val consentAccuracy: Boolean? = null,
    @SerialName("consent_esign") val consentESign: Boolean? = null,
    @SerialName("signed_at") val signedAt: Double? = null,
    @SerialName("signed_by_user_id") val signedByUserId: String? = null,
) {
    fun toDomain() = VoucherSignature(
        typedName = typedName.orEmpty(),
        consentAccuracy = consentAccuracy == true,
        consentESign = consentESign == true,
        signedAt = signedAt?.toLong(),
        signedByUserId = signedByUserId.orEmpty(),
    )
}

@Serializable
internal data class VoucherDetailDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("shoot_date") val shootDate: Double? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("call_time") val callTime: String? = null,
    @SerialName("wrap_time") val wrapTime: String? = null,
    @SerialName("minutes_worked") val minutesWorked: Double? = null,
    @SerialName("gross") val gross: Double? = null,
    @SerialName("holiday") val holiday: Double? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("day_status") val dayStatus: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("week_starting") val weekStarting: Double? = null,
    @SerialName("attendance_status") val attendanceStatus: String? = null,
    @SerialName("day_type") val dayType: String? = null,
    @SerialName("meals") val meals: List<MealDto>? = null,
    @SerialName("rates_ots") val ratesOts: List<LineDto>? = null,
    @SerialName("allowances") val allowances: List<LineDto>? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("payroll_status") val payrollStatus: String? = null,
    @SerialName("signature") val signature: SignatureDto? = null,
    @SerialName("sign_status") val signStatus: String? = null,
) {
    fun toDomain(): VoucherDetail? {
        val head = VoucherDto(
            id = id,
            underscoreId = underscoreId,
            code = code,
            shootDate = shootDate,
            timezone = timezone,
            callTime = callTime,
            wrapTime = wrapTime,
            minutesWorked = minutesWorked,
            gross = gross,
            holiday = holiday,
            currency = currency,
            status = status,
            dayStatus = dayStatus,
            category = category,
            role = role,
            weekStarting = weekStarting,
            attendanceStatus = attendanceStatus,
        ).toDomain() ?: return null

        return VoucherDetail(
            voucher = head,
            dayType = dayType.orEmpty(),
            meals = meals.orEmpty().map { it.toDomain() },
            ratesAndOvertime = ratesOts.orEmpty().map { it.toDomain() },
            allowances = allowances.orEmpty().map { it.toDomain() },
            notes = notes.orEmpty(),
            payrollStatus = payrollStatus.orEmpty(),
            // An unsigned voucher has no signature object at all; an empty one
            // would read on screen as "signed by nobody".
            signature = signature?.toDomain(),
            signStatus = signStatus.orEmpty(),
        )
    }
}

@Serializable
internal data class TallyDto(
    @SerialName("total") val total: Int? = null,
    @SerialName("pending") val pending: Int? = null,
    @SerialName("signed") val signed: Int? = null,
    @SerialName("paid") val paid: Int? = null,
) {
    fun toDomain() = VoucherTally(
        total = total ?: 0,
        pending = pending ?: 0,
        signed = signed ?: 0,
        paid = paid ?: 0,
    )
}

@Serializable
internal data class EarningsDto(
    @SerialName("months") val months: List<String>? = null,
    @SerialName("gross") val gross: List<Double>? = null,
) {
    /**
     * Two parallel arrays on the wire. They are zipped rather than indexed so
     * a short `gross` drops the tail instead of throwing — the chart is
     * decoration, and a ragged pair must not take the dashboard down.
     */
    fun toDomain(): List<EarningsPoint> =
        months.orEmpty().zip(gross.orEmpty()) { label, amount -> EarningsPoint(label, amount) }
}

@Serializable
internal data class SummaryDto(
    @SerialName("vouchers") val vouchers: TallyDto? = null,
    @SerialName("ytd_gross") val ytdGross: Double? = null,
    @SerialName("total_gross") val totalGross: Double? = null,
    @SerialName("holiday_accrued") val holidayAccrued: Double? = null,
    @SerialName("productions_count") val productionsCount: Int? = null,
    @SerialName("earnings") val earnings: EarningsDto? = null,
    @SerialName("action_voucher") val actionVoucher: VoucherDto? = null,
    @SerialName("next_booking") val nextBooking: VoucherDto? = null,
) {
    fun toDomain() = SaSummary(
        vouchers = vouchers?.toDomain() ?: VoucherTally(),
        ytdGross = ytdGross ?: 0.0,
        totalGross = totalGross ?: 0.0,
        holidayAccrued = holidayAccrued ?: 0.0,
        productionsCount = productionsCount ?: 0,
        earnings = earnings?.toDomain().orEmpty(),
        actionVoucher = actionVoucher?.toDomain(),
        nextBooking = nextBooking?.toDomain(),
    )
}

@Serializable
internal data class PayRunDto(
    @SerialName("week_starting") val weekStarting: Double? = null,
    @SerialName("gross") val gross: Double? = null,
    @SerialName("days") val days: Int? = null,
    @SerialName("status") val status: String? = null,
) {
    fun toDomain() = PayRun(
        weekStarting = weekStarting?.toLong(),
        gross = gross ?: 0.0,
        days = days ?: 0,
        status = status.orEmpty(),
    )
}

@Serializable
internal data class HolidayDto(
    @SerialName("total") val total: Double? = null,
    @SerialName("paid") val paid: Double? = null,
    @SerialName("accrued") val accrued: Double? = null,
) {
    fun toDomain() = HolidayPot(
        total = total ?: 0.0,
        paid = paid ?: 0.0,
        accrued = accrued ?: 0.0,
    )
}

@Serializable
internal data class PayDto(
    @SerialName("pay_runs") val payRuns: List<PayRunDto>? = null,
    @SerialName("holiday") val holiday: HolidayDto? = null,
) {
    fun toDomain() = PayStatement(
        runs = payRuns.orEmpty().map { it.toDomain() },
        holiday = holiday?.toDomain() ?: HolidayPot(),
    )
}

@Serializable
internal data class AccountCheckDto(
    @SerialName("key") val key: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("ok") val ok: Boolean? = null,
    @SerialName("detail") val detail: String? = null,
) {
    fun toDomain(): AccountCheck? {
        val resolvedKey = key ?: return null
        return AccountCheck(
            key = resolvedKey,
            label = label ?: resolvedKey,
            // Absent is not satisfied: a check the server did not answer for
            // must read as outstanding, never as passed.
            ok = ok == true,
            detail = detail.orEmpty(),
        )
    }
}

@Serializable
internal data class BankDto(
    @SerialName("name") val name: String? = null,
    @SerialName("account_holder_name") val accountHolderName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("iban_number") val ibanNumber: String? = null,
    @SerialName("swift_code") val swiftCode: String? = null,
) {
    fun toDomain() = ArtisteBank(
        name = name.orEmpty(),
        accountHolderName = accountHolderName.orEmpty(),
        accountNumber = accountNumber.orEmpty(),
        sortCode = sortCode.orEmpty(),
        ibanNumber = ibanNumber.orEmpty(),
        swiftCode = swiftCode.orEmpty(),
    )
}

@Serializable
internal data class ProfileDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("is_external") val isExternal: Boolean? = null,
    @SerialName("is_minor") val isMinor: Boolean? = null,
    @SerialName("ref_number") val refNumber: String? = null,
    @SerialName("artiste_ref") val artisteRef: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("engagement_type") val engagementType: String? = null,
    @SerialName("agency_name") val agencyName: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("bank") val bank: BankDto? = null,
    @SerialName("account_status") val accountStatus: List<AccountCheckDto>? = null,
) {
    fun toDomain(): SaProfile? {
        val resolved = id ?: return null
        return SaProfile(
            id = resolved,
            status = status.orEmpty(),
            isExternal = isExternal == true,
            isMinor = isMinor == true,
            refNumber = refNumber.orEmpty(),
            artisteRef = artisteRef.orEmpty(),
            category = category.orEmpty(),
            engagementType = engagementType.orEmpty(),
            agencyName = agencyName.orEmpty(),
            currency = currency.orEmpty(),
            bank = bank?.toDomain() ?: ArtisteBank(),
            accountStatus = accountStatus.orEmpty().mapNotNull { it.toDomain() },
        )
    }
}

@Serializable
internal data class WhoDto(
    @SerialName("name") val name: String? = null,
    @SerialName("designation") val designation: String? = null,
)

@Serializable
internal data class MessageDto(
    @SerialName("who_type") val whoType: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("at") val at: Double? = null,
    @SerialName("who") val who: WhoDto? = null,
) {
    fun toDomain() = QueryMessage(
        text = text.orEmpty(),
        at = at?.toLong(),
        authorName = who?.name.orEmpty(),
        authorRole = who?.designation.orEmpty(),
        whoType = whoType.orEmpty(),
    )
}

@Serializable
internal data class QueryVoucherDto(
    @SerialName("id") val id: String? = null,
    @SerialName("code") val code: String? = null,
)

@Serializable
internal data class QueryDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("topic") val topic: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("priority") val priority: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("voucher_id") val voucherId: String? = null,
    @SerialName("updated") val updated: Double? = null,
    @SerialName("last_message") val lastMessage: String? = null,
    @SerialName("messages") val messages: List<MessageDto>? = null,
    @SerialName("voucher") val voucher: QueryVoucherDto? = null,
) {
    fun toDomain(): ArtisteQuery? {
        val resolved = id ?: return null
        return ArtisteQuery(
            id = resolved,
            topic = topic.orEmpty(),
            title = title.orEmpty(),
            priority = priority.orEmpty(),
            status = status.orEmpty(),
            voucherId = voucherId ?: voucher?.id.orEmpty(),
            voucherCode = voucher?.code.orEmpty(),
            updated = updated?.toLong(),
            lastMessage = lastMessage.orEmpty(),
            messages = messages.orEmpty().map { it.toDomain() },
        )
    }
}
