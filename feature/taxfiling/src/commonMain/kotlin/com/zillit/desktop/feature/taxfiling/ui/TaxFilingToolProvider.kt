package com.zillit.desktop.feature.taxfiling.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Tax filing as a workspace tool.
 *
 * Registered *under* the Account Hub's own path, because that is where it is
 * reached from and nowhere else — it has no tile in the Film Tools grid, on
 * the web or here. The longest-prefix route resolution means the hub keeps
 * `/film-tools/account-hub` and this owns the deeper route.
 *
 * It opens as its own window rather than inside the hub's shell, which is the
 * shape every hand-off in that sidebar already has here: the hub is one
 * window, its tools are theirs.
 *
 * [openConsent] takes the accountant to HMRC's consent page in their browser.
 * Deliberately not in an embedded view: signing in to HMRC belongs in the
 * browser the person already trusts, and an OAuth grant typed into a window
 * this application drew is a habit worth not teaching.
 */
class TaxFilingToolProvider(
    private val viewModel: TaxFilingViewModel,
    private val openConsent: (String) -> Unit,
) : ToolProvider {

    override val path: String = TAX_FILING_PATH
    override val title: String = "Tax Filing"
    override val icon = ZillitIcons.Bank
    override val openMode: OpenMode = OpenMode.Maximized
    override val defaultSize: DpSize = DpSize(WIDTH.dp, HEIGHT.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is TaxFilingEffect.Failed -> failure = effect.message
                    is TaxFilingEffect.OpenInBrowser -> openConsent(effect.url)
                }
            }
        }

        TaxFilingScreen(state = state, onEvent = viewModel::onEvent)

        ZillitToast(
            message = failure,
            onDismiss = { failure = null },
            tone = ZillitToastTone.Danger,
        )
    }

    companion object {
        /** Under the hub's path, which is the only place it is reached from. */
        const val TAX_FILING_PATH = "/film-tools/account-hub/tax-filing"

        private const val WIDTH = 1280
        private const val HEIGHT = 900
    }
}
