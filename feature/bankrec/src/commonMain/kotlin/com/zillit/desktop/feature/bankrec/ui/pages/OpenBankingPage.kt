package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrTone

/**
 * Open Banking — a placeholder, as it is on the web.
 *
 * The web ships a full live-feed page behind this tab, but every figure in it
 * is hard-coded and nothing calls a service; the module renders "coming soon"
 * in its place. Porting the mock would show a production somebody else's bank.
 */
@Composable
fun ColumnScope.OpenBankingPage() {
    BrEmpty(
        title = "Open Banking",
        message = "Coming soon — connect bank accounts via PSD2 Open Banking for live transaction feeds and " +
            "automated reconciliation.",
        icon = BankRecIcons.Link,
        tone = BrTone.Accent,
    )
}
