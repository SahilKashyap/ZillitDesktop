package com.zillit.desktop.feature.cardexpenses.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer

/**
 * Every page the card tool can show.
 *
 * ## Two layouts, one destination set
 *
 * An accountant works fourteen surfaces from a sidebar; a cardholder works
 * five from a tab strip. Both come from this one enum, filtered by
 * [visibleTo], so a destination cannot exist in one layout and be unreachable
 * in the other — which is how the web ended up with routes only reachable by
 * typing the URL.
 */
enum class CardDestination(
    val slug: String,
    private val labelKey: String,
    val icon: ImageVector,
    val group: CardNavGroup,
) {
    // -- accountant --------------------------------------------------------
    Overview("", S.ah_overview, ZillitIcons.Grid, CardNavGroup.Top),
    CardRegister("cards", S.ah_card_register, ZillitIcons.CreditCard, CardNavGroup.Cards),
    ImportStatement("import", S.ah_import_statement, ZillitIcons.Upload, CardNavGroup.Workflow),
    ReceiptInbox("inbox", S.ah_receipt_inbox, ZillitIcons.Receipt, CardNavGroup.Workflow),
    AllTransactions("transactions", S.ah_all_transactions, ZillitIcons.Ledger, CardNavGroup.Workflow),
    PendingCoding("pending", S.ah_pending_coding, ZillitIcons.Clock, CardNavGroup.Workflow),
    ApprovalQueue("approval", S.ah_approval_queue, ZillitIcons.Shield, CardNavGroup.Workflow),
    ProcessQueue("process", S.desktop_card_process_expenses, ZillitIcons.Settings, CardNavGroup.Workflow),
    BulkProcess("bulk", S.ah_bulk_process, ZillitIcons.Grid, CardNavGroup.Workflow),
    TopUpQueue("topups", S.ah_topup_todo, ZillitIcons.Wallet, CardNavGroup.Workflow),
    History("history", S.history, ZillitIcons.Ledger, CardNavGroup.Workflow),
    Analytics("analytics", S.analytics, ZillitIcons.BarChart, CardNavGroup.Management),
    Alerts("alerts", S.desktop_card_smart_alerts, ZillitIcons.Bell, CardNavGroup.Management),
    Settings("settings", S.settings, ZillitIcons.Settings, CardNavGroup.Management),

    // -- cardholder --------------------------------------------------------
    MyTransactions("my-receipts", S.ah_my_transactions, ZillitIcons.Receipt, CardNavGroup.Mine),
    MyCards("my-cards", S.desktop_card_my_card, ZillitIcons.CreditCard, CardNavGroup.Mine),
    CardExtension("extension", S.ah_card_extension, ZillitIcons.Wallet, CardNavGroup.Mine),
    CardsForApproval("card-approval", S.ah_approval_queue, ZillitIcons.Shield, CardNavGroup.Mine),
    CodingQueue("coding", S.ah_coding_queue, ZillitIcons.Clock, CardNavGroup.Mine),
    ;

    val label: String get() = str(labelKey)

    /**
     * Whether [viewer] may open this page.
     *
     *  - Accountant surfaces need an accountant, and Settings additionally a
     *    senior one, because it rewrites what everyone else may do.
     *  - Cardholder surfaces are for everyone who is not processing cards; an
     *    accountant reaches them by opening the tool from the Film Tools grid,
     *    which is what `enteredAsTool` means.
     *  - The two queues a cardholder can hold — approving other people's cards,
     *    coding for their department — need the grant from metadata, and coding
     *    additionally needs the production to use coding at all.
     */
    fun visibleTo(viewer: CardViewer): Boolean = when (this) {
        Settings -> viewer.canOpenSettings
        CardsForApproval -> !viewer.isAccountant && viewer.isApprover
        CodingQueue -> !viewer.isAccountant && viewer.isCoordinator && viewer.metadata.codingRequired
        MyTransactions, MyCards, CardExtension -> !viewer.isAccountant
        else -> viewer.isAccountant
    }

    /**
     * The `level_1` keys the notification service files this page's rows
     * under (`constants.js:201-224`, card-expenses-badges.xlsx). Empty for a
     * page nothing is filed under; the non-accountant approval queue sums
     * its two (cards, and receipts / transactions).
     */
    val badgeKeys: List<String>
        get() = when (this) {
            CardRegister -> listOf("card_register")
            ReceiptInbox -> listOf("receipt_inbox")
            AllTransactions -> listOf("all_transactions")
            PendingCoding -> listOf("pending_coding")
            ApprovalQueue -> listOf("approval_queue")
            ProcessQueue -> listOf("process_queue")
            BulkProcess -> listOf("bulk_process")
            TopUpQueue -> listOf("topup_todo")
            Alerts -> listOf("smart_alerts")
            MyTransactions -> listOf("my_transactions")
            MyCards -> listOf("my_cards")
            CardExtension -> listOf("card_extension")
            CardsForApproval -> listOf("card_approval_queue", "receipt_approval_queue")
            CodingQueue -> listOf("coding_queue")
            else -> emptyList()
        }

    companion object {
        fun fromSlug(slug: String?): CardDestination? = entries.firstOrNull { it.slug == slug }

        /** An accountant opens on the dashboard; a cardholder on their own spend. */
        fun landing(viewer: CardViewer): CardDestination =
            if (viewer.isAccountant) Overview else MyTransactions
    }
}

/** The heading a destination sits under in the sidebar. */
enum class CardNavGroup(private val titleKey: String?) {
    /** Ungrouped, at the very top. */
    Top(null),
    Mine(S.desktop_card_nav_my_expenses),
    Cards(S.ah_cards),
    Workflow(S.desktop_card_nav_workflow),
    Management(S.desktop_management),
    ;

    val title: String? get() = titleKey?.let { str(it) }
}
