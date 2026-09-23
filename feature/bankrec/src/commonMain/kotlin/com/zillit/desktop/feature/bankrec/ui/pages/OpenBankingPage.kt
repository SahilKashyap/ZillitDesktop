package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
        title = str(S.desktop_open_banking),
        message = str(S.desktop_br_open_banking_soon),
        icon = BankRecIcons.Link,
        tone = BrTone.Accent,
    )
}
