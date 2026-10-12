package com.zillit.desktop.feature.accounthub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.ui.components.ProvideHubFaces
import com.zillit.desktop.feature.accounthub.ui.pages.ApproversPage
import kotlinx.coroutines.delay

/**
 * Deal Memo's own Approvers page — the web's `/film-tools/deal-memo/approvers`.
 *
 * The Approvers screen locked to the Deal Memo chain (`ApproversModule`'s
 * `fixedModule`), opened from a deal's "Set Approval Level" and from All
 * Deals' "Approvers not configured" banner. It is not the console's page:
 * that one needs the hub's tool right and the accounts department, and bounced
 * Deal Memo users away. Here Deal Memo's posting users may edit (`canEdit`),
 * and only that chain is reachable. Whether the service accepts their saves is
 * unverified, as on the web; a refusal reads as a toast.
 *
 * Registered under Deal Memo's own path so the registry's longest-prefix match
 * picks it over the tool that owns everything else below `/film-tools/deal-memo`.
 * Opened in Deal Memo's window; Back steps back to the deal or list that led
 * here, never pushes it.
 *
 * It runs on a view model of its own ([AccountHubViewModel.openApprovers]), so
 * the console's Approvers page, if open in another window, keeps its module.
 */
class DealMemoApproversToolProvider(
    private val viewModel: AccountHubViewModel,
    /**
     * Whether the viewer may post in Deal Memo; null until the tool grid has
     * answered, so a window restored before the rights land is not bounced.
     */
    private val postingRight: () -> Boolean?,
    /** A crew member's photo by user id, for the people the page draws; null shows initials. */
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {
    override val path: String = DEAL_MEMO_APPROVERS_PATH
    override val title: String get() = str(S.desktop_approvers)
    override val icon = ZillitIcons.Shield
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        // Waits for the rights, then keeps the answer: a poll that stops once known.
        var posting by remember { mutableStateOf(postingRight()) }
        LaunchedEffect(Unit) {
            while (posting == null) {
                delay(RIGHTS_POLL_MS)
                posting = postingRight()
            }
        }

        // Posting users only, as the web's `isPostOnlyPath` bounce. The tool's
        // own default lands everyone else on My Deal.
        LaunchedEffect(posting) {
            when (posting) {
                true -> viewModel.openApprovers(ApprovalModule.DealMemo)
                false -> navigator.navigate(WorkspaceRoute.Tool(DEAL_MEMO_PATH))
                null -> Unit
            }
        }

        // Only a refusal is this page's to show; the console's hand-offs have nowhere to go from here.
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                if (effect is AccountHubEffect.Failed) failure = effect.message
            }
        }

        LaunchedEffect(Unit) { navigator.setTitle(title) }

        if (posting == true) {
            ProvideHubFaces(loadAvatar) {
                ApproversPage(
                    state = state,
                    onEvent = viewModel::onEvent,
                    // Back steps back to the deal or list that led here; opened on its own
                    // (a restored window, a new one) it replaces itself with Deal Memo's default tab.
                    onBack = {
                        if (navigator.canGoBack) {
                            navigator.back()
                        } else {
                            navigator.navigate(WorkspaceRoute.Tool(DEAL_MEMO_PATH))
                        }
                    },
                )
            }
        }

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

/** The web's `/film-tools/deal-memo`; that tool is `feature:dealmemo`, which this module does not depend on. */
private const val DEAL_MEMO_PATH = "/film-tools/deal-memo"

/** Deal Memo's `DEAL_MEMO_APPROVERS_PATH` — the page is Account Hub's, the path is Deal Memo's. */
const val DEAL_MEMO_APPROVERS_PATH = "$DEAL_MEMO_PATH/approvers"

private const val RIGHTS_POLL_MS = 500L
