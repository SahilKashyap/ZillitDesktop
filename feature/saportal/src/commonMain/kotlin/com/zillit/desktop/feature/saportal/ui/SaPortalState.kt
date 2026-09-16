package com.zillit.desktop.feature.saportal.ui

import com.zillit.desktop.feature.saportal.domain.SaRefresh
import com.zillit.desktop.feature.saportal.domain.ArtisteQuery
import com.zillit.desktop.feature.saportal.domain.PayStatement
import com.zillit.desktop.feature.saportal.domain.SaProfile
import com.zillit.desktop.feature.saportal.domain.SaSummary
import com.zillit.desktop.feature.saportal.domain.SaViewer
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherDetail
import com.zillit.desktop.feature.saportal.domain.VoucherStatus

/** The portal's sections, in the order an artiste wants them. */
enum class SaDestination(val slug: String, val label: String) {
    Dashboard("dashboard", "Overview"),
    Vouchers("vouchers", "My days"),
    Pay("pay", "Pay"),
    Queries("queries", "Queries"),
    Profile("profile", "My details"),
    ;

    /**
     * Which socket refresh kind this page answers to.
     *
     * The overview is built from the vouchers, so it moves with them.
     */
    val refresh: SaRefresh
        get() = when (this) {
            Dashboard, Vouchers -> SaRefresh.Vouchers
            Pay -> SaRefresh.Pay
            Queries -> SaRefresh.Queries
            Profile -> SaRefresh.Profile
        }

    /**
     * The unit the notification service files this page's rows under — the
     * web's `SA_UNIT_BY_NAV` (`sa-portal-badge-helpers.js:33`); null for a
     * page nothing is filed under.
     */
    val badgeKey: String?
        get() = when (this) {
            Vouchers -> "my_vouchers_label"
            Queries -> "query_label"
            Profile -> "profile_label"
            Dashboard, Pay -> null
        }
}

/**
 * The signing dialog.
 *
 * Both consents and a typed name are required together — the server refuses
 * anything less, and splitting them across steps would let someone believe
 * they had signed when they had not.
 */
data class SignState(
    val voucher: Voucher,
    val typedName: String = "",
    val consentAccuracy: Boolean = false,
    val consentESign: Boolean = false,
    val saving: Boolean = false,
) {
    val ready: Boolean get() = typedName.isNotBlank() && consentAccuracy && consentESign
}

/** Raising a query, always about one day. */
data class QueryDraft(
    val voucherId: String,
    val voucherCode: String = "",
    val text: String = "",
    val saving: Boolean = false,
) {
    val ready: Boolean get() = text.isNotBlank()
}

data class SaUiState(
    val viewer: SaViewer = SaViewer(),
    val destination: SaDestination = SaDestination.Dashboard,
    /** Unread notifications per unit — the tabs' red chips. */
    val unread: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /**
     * True once the server has said this user is not an artiste here.
     *
     * Not an error: most crew are not supporting artistes, and the portal
     * says so plainly rather than showing them a failure they cannot act on.
     */
    val notAnArtiste: Boolean = false,
    val summary: SaSummary? = null,
    val profile: SaProfile? = null,
    val vouchers: List<Voucher> = emptyList(),
    val voucherFilter: VoucherStatus? = null,
    val openVoucher: VoucherDetail? = null,
    val loadingVoucher: Boolean = false,
    val pay: PayStatement? = null,
    val queries: List<ArtisteQuery> = emptyList(),
    val openQuery: ArtisteQuery? = null,
    val replyDraft: String = "",
    val sign: SignState? = null,
    val queryDraft: QueryDraft? = null,
) {
    /** The days still wanting a signature — what the overview leads with. */
    val awaitingSignature: List<Voucher>
        get() = vouchers.filter { it.signable }

    val hasOutstanding: Boolean get() = awaitingSignature.isNotEmpty()

    /** Unresolved threads, newest first — resolved ones fall to the bottom. */
    val openQueries: List<ArtisteQuery>
        get() = queries.sortedWith(compareBy({ it.resolved }, { -(it.updated ?: 0L) }))
}

/** Everything the artiste can do here. */
sealed interface SaEvent {
    data class Open(val destination: SaDestination) : SaEvent
    data object Refresh : SaEvent
    data object ClearNotice : SaEvent
    data object DismissError : SaEvent

    data class FilterVouchers(val status: VoucherStatus?) : SaEvent
    data class OpenVoucher(val id: String) : SaEvent
    data object CloseVoucher : SaEvent

    data class StartSigning(val voucher: Voucher) : SaEvent
    data class SignName(val text: String) : SaEvent
    data class SignAccuracy(val on: Boolean) : SaEvent
    data class SignESign(val on: Boolean) : SaEvent
    data object ConfirmSign : SaEvent
    data object CancelSign : SaEvent

    data class OpenQuery(val id: String) : SaEvent
    data object CloseQuery : SaEvent
    data class ReplyDraft(val text: String) : SaEvent
    data object SendReply : SaEvent
    data class ResolveQuery(val id: String) : SaEvent

    data class StartQuery(val voucher: Voucher) : SaEvent
    data class QueryText(val text: String) : SaEvent
    data object SubmitQuery : SaEvent
    data object CancelQuery : SaEvent
}

/** One-shot things the portal asks the host to do. */
sealed interface SaEffect {
    data class Failed(val message: String) : SaEffect
}
