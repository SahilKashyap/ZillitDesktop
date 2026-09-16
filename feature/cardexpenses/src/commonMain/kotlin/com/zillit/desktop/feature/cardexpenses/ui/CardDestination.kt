package com.zillit.desktop.feature.cardexpenses.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
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
    val label: String,
    val icon: ImageVector,
    val group: CardNavGroup,
) {
    // -- accountant --------------------------------------------------------
    Overview("", "Overview", ZillitIcons.Grid, CardNavGroup.Top),
    CardRegister("cards", "Card Register", ZillitIcons.CreditCard, CardNavGroup.Cards),
    ImportStatement("import", "Import Statement", ZillitIcons.Upload, CardNavGroup.Workflow),
    ReceiptInbox("inbox", "Receipt Inbox", ZillitIcons.Receipt, CardNavGroup.Workflow),
    AllTransactions("transactions", "All Transactions", ZillitIcons.Ledger, CardNavGroup.Workflow),
    PendingCoding("pending", "Pending Coding", ZillitIcons.Clock, CardNavGroup.Workflow),
    ApprovalQueue("approval", "Approval Queue", ZillitIcons.Shield, CardNavGroup.Workflow),
    ProcessQueue("process", "Process Expenses", ZillitIcons.Settings, CardNavGroup.Workflow),
    BulkProcess("bulk", "Bulk Process", ZillitIcons.Grid, CardNavGroup.Workflow),
    TopUpQueue("topups", "Top-Up To Do", ZillitIcons.Wallet, CardNavGroup.Workflow),
    History("history", "History", ZillitIcons.Ledger, CardNavGroup.Workflow),
    Analytics("analytics", "Analytics", ZillitIcons.BarChart, CardNavGroup.Management),
    Alerts("alerts", "Smart Alerts", ZillitIcons.Bell, CardNavGroup.Management),
    Settings("settings", "Settings", ZillitIcons.Settings, CardNavGroup.Management),

    // -- cardholder --------------------------------------------------------
    MyTransactions("my-receipts", "My Transactions", ZillitIcons.Receipt, CardNavGroup.Mine),
    MyCards("my-cards", "My Card", ZillitIcons.CreditCard, CardNavGroup.Mine),
    CardExtension("extension", "Card Extension", ZillitIcons.Wallet, CardNavGroup.Mine),
    CardsForApproval("card-approval", "Approval Queue", ZillitIcons.Shield, CardNavGroup.Mine),
    CodingQueue("coding", "Coding Queue", ZillitIcons.Clock, CardNavGroup.Mine),
    ;

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
enum class CardNavGroup(val title: String?) {
    /** Ungrouped, at the very top. */
    Top(null),
    Mine("My expenses"),
    Cards("Cards"),
    Workflow("Workflow"),
    Management("Management"),
}
