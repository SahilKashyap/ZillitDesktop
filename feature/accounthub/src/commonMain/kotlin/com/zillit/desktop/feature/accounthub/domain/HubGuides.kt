@file:Suppress("MaxLineLength") // Published URLs, copied from the web byte for byte.

package com.zillit.desktop.feature.accounthub.domain

/**
 * The Guide page's links — the web's `GuideModule` and `GUIDE_LINKS`.
 *
 * One card per sidebar module that has a section on documentation.zillit.com
 * (`utils/tutorialURLLink.js`) or a tutorial video from the video team's sheet
 * (`utils/videoURL.js`); a card shows Watch Video and/or Read More for
 * whichever the module has. A module with neither is left off rather than
 * linked to nothing; add its row here when one ships.
 */
object HubGuides {

    /** One module's card: its page on the documentation site and its clip, either of which may be absent. */
    data class Guide(val docs: String?, val video: String?)

    private const val DOCS = "https://documentation.zillit.com/#"
    private const val VIDEOS = "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/Account%20Hub/"

    /**
     * Sidebar item id → that module's guide — the web's `GUIDE_LINKS`, one key
     * per module into both maps. The Invoices and Bank Reconciliation clips are
     * the team's "Video Coming Soon" placeholder for now; the same URL turns
     * real when they replace the file.
     */
    private val GUIDES: Map<String, Guide> = mapOf(
        "production-setup" to Guide(
            "${DOCS}production-setup",
            "${VIDEOS}How%20to%20set%20Production%20Setup%28Account%20Hub%29%20Web.mp4",
        ),
        "purchase-orders" to Guide(
            "${DOCS}account-hub-purchase-order",
            "${VIDEOS}How%20to%20use%20Purchase%20Orders%28Account%20Hub%29%20Web.mp4",
        ),
        "invoices" to Guide(
            "${DOCS}invoices",
            "${VIDEOS}How%20to%20use%20Invoices%28Account%20Hub%29%20Web.mp4",
        ),
        // Its own section, not the Film Tools tile's `#production-expense-cards` (web d6506b7a7).
        "card-expenses" to Guide(
            "${DOCS}account-hub-production-expense-cards",
            "${VIDEOS}How%20to%20use%20Production%20Expense%20Cards%28Account%20Hub%29%20Web.mp4",
        ),
        "cash-expenses" to Guide(
            "${DOCS}account-hub-petty-cash-expenses",
            "${VIDEOS}How%20to%20use%20Petty%20Cash%20Expenses%28Account%20Hub%29%20Web.mp4",
        ),
        "payroll" to Guide(
            "${DOCS}account-hub-payroll",
            "${VIDEOS}How%20to%20use%20Payroll%28Account%20Hub%29%20Web.mp4",
        ),
        "cost-report" to Guide(
            "${DOCS}account-hub-cost-report",
            "${VIDEOS}How%20to%20use%20Cost%20Report%28Account%20Hub%29%20Web.mp4",
        ),
        "period-close" to Guide(
            "${DOCS}account-hub-period-close",
            "${VIDEOS}How%20to%20use%20Period%20Close%28Account%20Hub%29%20Web.mp4",
        ),
        "vendors" to Guide(
            "${DOCS}account-hub-vendors",
            "${VIDEOS}How%20to%20add%2C%20edit%20and%20delete%20Vendors%28Account%20Hub%29%20Web.mp4",
        ),
        "trial-balance" to Guide(
            "${DOCS}trial-balance",
            "${VIDEOS}How%20to%20use%20Trial%20Balance%28Account%20Hub%29%20Web.mp4",
        ),
        "bible-report" to Guide(
            "${DOCS}bible-report",
            "${VIDEOS}How%20to%20use%20Bible%20Report%28Account%20Hub%29%20Web.mp4",
        ),
        "bank-reconciliation" to Guide(
            "${DOCS}bank-reconciliation",
            "${VIDEOS}How%20to%20use%20Bank%20Reconciliation%28Account%20Hub%29%20Web.mp4",
        ),
        "approvers" to Guide(
            "${DOCS}account-hub-approvers",
            "${VIDEOS}How%20to%20set%20Approvers%28Account%20Hub%29%20Web.mp4",
        ),
        "budget" to Guide(
            "${DOCS}account-hub-budget",
            "${VIDEOS}How%20to%20use%20Budget%28Account%20Hub%29%20Web.mp4",
        ),
        "chart-of-accounts" to Guide(
            "${DOCS}account-hub-chart-of-accounts",
            "${VIDEOS}How%20to%20use%20Chart%20of%20Accounts%28Account%20Hub%29%20Web.mp4",
        ),
        "form-config" to Guide(
            "${DOCS}account-hub-forms-configuration",
            "${VIDEOS}How%20to%20use%20Forms%20Configuration%28Account%20Hub%29%20Web.mp4",
        ),
    )

    /** The module's guide, or null when it has neither a docs section nor a clip. */
    fun guide(itemId: String): Guide? = GUIDES[itemId]?.takeIf { it.docs != null || it.video != null }

    /**
     * The sidebar's groups cut down to the modules that have a guide, empty
     * groups dropped. The page is accountant-only (the web's routes gate it),
     * so this is the whole sidebar rather than [HubNavigation.visibleTo]'s
     * per-viewer cut — and the Guide row itself has no guide, so it drops out.
     */
    val sections: List<HubSection> = HubNavigation.sections.mapNotNull { section ->
        val items = section.items.filter { guide(it.id) != null }
        if (items.isEmpty()) null else section.copy(items = items)
    }

    /**
     * The address a card opens: the page's link with `project_type` set in the
     * query ahead of the `#anchor`, "default" when the production's is unknown
     * — what the web does with `url.searchParams.set`, and the docs site reads
     * it. Null for an id with no docs section.
     */
    fun url(itemId: String, projectType: String?): String? {
        val base = guide(itemId)?.docs ?: return null
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
