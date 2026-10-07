package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubPageHeader

/**
 * Admin Settings → Production Setup — the web's `ProductionSetupCompanies`.
 *
 * The web hosts the Account Hub's own Companies section on a page of the
 * settings tree rather than opening the console: no sidebar, no bank register,
 * none of the accounting sections, and a back arrow to Admin Settings where the
 * console's would go to Film Tools. Companies are the same slice the console's
 * Production Setup edits, so one created here is there with no reload.
 *
 * Banks are still attachable per company from the company editor, which is why
 * its dialogs are composed here too.
 */
@Composable
fun ProductionCompaniesPage(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    onBack: () -> Unit,
) {
    val setup = state.setup
    val scroll = rememberScrollState()

    HubPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            BackButton(label = str(S.desktop_back_to_admin_settings), onClick = onBack)
            HubPageHeader(
                eyebrow = str(S.desktop_setup),
                title = str(S.ps_production_setup),
                description = str(S.desktop_production_setup_companies_detail),
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.viewer.canEdit && setup.loaded) {
            ZillitNotice(
                text = str(S.desktop_hub_you_can_see_this_configuration_but_not_change_it_edits),
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        if (setup.loading && !setup.loaded) {
            // A plain loader, not the console's stack of card stubs: those
            // would flash sections this page never draws.
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    modifier = Modifier.padding(ZillitTheme.spacing.xl),
                ) {
                    ZillitSpinner(size = 28.dp)
                    FieldHint(str(S.desktop_loading_companies))
                }
            }
            return@HubPage
        }

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            CompaniesSection(state, onEvent)
        }
    }

    // In stacking order: the bank editor opens over the company editor (its
    // inline "Add bank account"), and a removal confirms over both.
    CompanyDialog(state, onEvent)
    BankAccountDialog(state, onEvent)
    SetupRemovalDialog(state, onEvent)
}
