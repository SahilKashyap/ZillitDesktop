package com.zillit.desktop.feature.cardexpenses.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.LocalHostedBy
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Card Expenses as a workspace window.
 *
 * Maximised and hosting its own routes, like the cash tool: fourteen surfaces
 * behind a sidebar is a sub-application, and the master–detail queues need the
 * width.
 *
 * ## Two doors, two views
 *
 * The web tells the Film Tools tile (`?entry=tool`) from the Account Hub's own
 * navigation, and an accountant gets a different module through each: the
 * crew view — file your own receipts — from the tile, the accountant's
 * console through the hub. Here the difference is [LocalHostedBy]: null when
 * this window stands alone.
 *
 * The view model is one instance for the app, so the hub's embed and a
 * stand-alone window can be on screen at the same time. The layout is
 * therefore chosen **per composition** — each draws its own viewer — and every
 * event a composition sends is preceded by its entry, so the view model's
 * handlers gate on the door the event actually came through rather than on
 * whichever window last spoke.
 */
class CardExpensesToolProvider(
    private val viewModel: CardExpensesViewModel,
    private val onOpenAttachment: (String) -> Unit = {},
) : ToolProvider {

    override val path: String = CARD_EXPENSES_PATH
    override val title: String get() = str(S.ah_card_expenses)
    override val icon = ZillitToolIcons.CardExpense
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }
        val asTool = LocalHostedBy.current == null
        val focused = LocalWindowInfo.current.isWindowFocused

        // Before `start`, so the first landing page is already this door's.
        LaunchedEffect(asTool) { viewModel.onEvent(CardEvent.Enter(asTool)) }
        // A window coming to the front takes the shared view model with it.
        LaunchedEffect(asTool, focused) { if (focused) viewModel.onEvent(CardEvent.Enter(asTool)) }

        // The first time this tool is shown: the view model is built with the
        // app, before a production is open, so it resolves who the viewer is
        // here rather than in its constructor.
        LaunchedEffect(viewModel) { viewModel.start() }

        val onEvent: (CardEvent) -> Unit = remember(viewModel, asTool) {
            { event ->
                viewModel.onEvent(CardEvent.Enter(asTool))
                viewModel.onEvent(event)
            }
        }

        // A deep link lands on the page it names. The workspace resolves this
        // provider by longest prefix, so `/film-tools/card-expenses/settings`
        // arrives here with its tail intact — which is how Production Setup's
        // Card tile opens the settings that live in this tool rather than
        // duplicating them in the hub.
        LaunchedEffect(route.path) {
            CardDestination.fromSlug(route.path.removePrefix(CARD_EXPENSES_PATH).trim('/'))
                ?.let { onEvent(CardEvent.Open(it)) }
        }

        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CardEffect.Failed -> failure = effect.message
                    is CardEffect.OpenAttachment -> onOpenAttachment(effect.key)
                }
            }
        }

        val shown = state.enteredThrough(asTool)

        LaunchedEffect(shown.destination) {
            navigator.setTitle(str(S.desktop_card_window_title, shown.destination.label))
        }

        // Closing is the way back: standing alone it closes the tool, and the
        // hub's navigator turns the same close into a return to the hub.
        CardExpensesScreen(state = shown, onEvent = onEvent, onBack = navigator::close)

        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

/**
 * This state as the composition that entered through [asTool] draws it.
 *
 * The viewer carries the door; a page the other door had open, and this
 * viewer may not see, reads as this viewer's landing page until an event from
 * here moves the view model over.
 */
internal fun CardUiState.enteredThrough(asTool: Boolean): CardUiState {
    if (viewer.enteredAsTool == asTool) return this
    val own = viewer.copy(enteredAsTool = asTool)
    val page = destination.takeIf { it.visibleTo(own) } ?: CardDestination.landing(own)
    return copy(viewer = own, destination = page)
}

const val CARD_EXPENSES_PATH = "/film-tools/card-expenses"
