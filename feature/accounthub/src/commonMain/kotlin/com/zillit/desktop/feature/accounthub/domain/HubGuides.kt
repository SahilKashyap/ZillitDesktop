@file:Suppress("MaxLineLength") // Published URLs, copied from the web byte for byte.

package com.zillit.desktop.feature.accounthub.domain

/**
 * The Guide page's links — the web's `GuideModule` and `GUIDE_LINKS`.
 *
 * One card per sidebar module that has a section on documentation.zillit.com
 * (`utils/tutorialURLLink.js`, "account hub guide"). A module without a section
 * is left off rather than linked to nothing; add its row here when the docs
 * site ships one.
 */
object HubGuides {

    /** Sidebar item id → that module's page on the documentation site. */
    private val LINKS: Map<String, String> = mapOf(
        "production-setup" to "https://documentation.zillit.com/#production-setup",
        "purchase-orders" to "https://documentation.zillit.com/#account-hub-purchase-order",
        "invoices" to "https://documentation.zillit.com/#invoices",
        "card-expenses" to "https://documentation.zillit.com/#account-hub-production-expense-cards",
        "cash-expenses" to "https://documentation.zillit.com/#account-hub-petty-cash-expenses",
        "payroll" to "https://documentation.zillit.com/#account-hub-payroll",
        "cost-report" to "https://documentation.zillit.com/#account-hub-cost-report",
        "trial-balance" to "https://documentation.zillit.com/#trial-balance",
        "bible-report" to "https://documentation.zillit.com/#bible-report",
        "bank-reconciliation" to "https://documentation.zillit.com/#bank-reconciliation",
    )

    /**
     * The sidebar's groups cut down to the modules that have a guide, empty
     * groups dropped. The page is accountant-only (the web's routes gate it),
     * so this is the whole sidebar rather than [HubNavigation.visibleTo]'s
     * per-viewer cut — and the Guide row itself has no guide, so it drops out.
     */
    val sections: List<HubSection> = HubNavigation.sections.mapNotNull { section ->
        val items = section.items.filter { it.id in LINKS }
        if (items.isEmpty()) null else section.copy(items = items)
    }

    /**
     * The address a card opens: the page's link with `project_type` set in the
     * query ahead of the `#anchor`, "default" when the production's is unknown
     * — what the web does with `url.searchParams.set`, and the docs site reads
     * it. Null for an id with no guide.
     */
    fun url(itemId: String, projectType: String?): String? {
        val base = LINKS[itemId] ?: return null
        val type = projectType?.takeIf { it.isNotBlank() } ?: DEFAULT_TYPE
        val anchor = base.substringAfter('#', "")
        return base.substringBefore('#') + "?project_type=" + encode(type) +
            if (anchor.isEmpty()) "" else "#$anchor"
    }

    /** `URLSearchParams`' escaping: unreserved characters stay, the rest go as UTF-8 percent bytes. */
    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            when {
                byte >= 0 && (c.isLetterOrDigit() || c in "-_.~") -> append(c)
                else -> {
                    val value = byte.toInt() and BYTE_MASK
                    append('%').append(HEX[value / HEX.length]).append(HEX[value % HEX.length])
                }
            }
        }
    }

    private const val DEFAULT_TYPE = "default"
    private const val HEX = "0123456789ABCDEF"
    private const val BYTE_MASK = 0xFF
}
