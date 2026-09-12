package com.zillit.desktop.feature.invoices.ui

/**
 * The keys the invoices screen answers — the web's sidebar footer, wired.
 *
 * Kept as a plain mapping so the rule is testable without composing a screen;
 * the screen only decides *when* to consult it (never while a text field or a
 * dialog has the keyboard).
 */
enum class InvoiceShortcut(val key: String, val label: String) {
    /** Puts the cursor in the page's search box. */
    Search("/", "Search"),

    /** Enters an invoice, or uploads one, depending on who is looking. */
    New("N", "New"),

    /** Lists these keys. */
    Help("?", "Help"),
    ;

    companion object {
        /**
         * The shortcut a keystroke asks for, or null.
         *
         * `?` is shift and the same physical key as `/` on this layout, so the
         * modifier is what separates them.
         */
        fun of(keyLabel: String, shift: Boolean): InvoiceShortcut? = when {
            keyLabel == "/" && shift -> Help
            keyLabel == "/" -> Search
            keyLabel.equals("n", ignoreCase = true) -> New
            else -> null
        }
    }
}
