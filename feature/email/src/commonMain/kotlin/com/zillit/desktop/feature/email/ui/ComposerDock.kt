package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage

/**
 * The composers standing on the bottom edge of the mailbox.
 *
 * ## Why the view model is created above the chrome
 *
 * Each composer's [ComposeViewModel] is remembered against its id, *outside*
 * the branch that decides whether it is drawn as a card, a strip or a
 * full-height pane. Creating it inside would mean minimising a composer
 * disposed the view model and threw away everything typed into it — which is
 * the one thing minimising must never do.
 *
 * ## Why closing is not cancelling
 *
 * Closing saves. `DisposableEffect` fires the save on the way out because the
 * view model's own scope is already gone by then, and a network call started
 * from a dead scope is how the last edit gets lost.
 */
@Composable
fun BoxScope.ComposerDock(
    composers: List<OpenComposer>,
    deps: Composing,
    messageById: (String) -> EmailMessage?,
    draftById: (String) -> EmailDraft?,
    onWindow: (String, ComposerWindow) -> Unit,
    onClose: (String) -> Unit,
    onOpenSignatures: () -> Unit,
) {
    if (composers.isEmpty()) return

    // One view model per composer, owned HERE — above both call sites below.
    // A composer switching shape moves between the standing row and the
    // expanded pane, which are different composition positions: anything
    // remembered inside the host dies in the move, and the first casualty
    // was every word typed into the message. The map survives the move; only
    // a composer leaving the list retires its view model (with a save).
    val viewModels = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateMapOf<String, ComposeViewModel>()
    }
    val liveIds = composers.map { it.id }.toSet()
    androidx.compose.runtime.LaunchedEffect(liveIds) {
        (viewModels.keys - liveIds).forEach { id ->
            viewModels.remove(id)?.onEvent(ComposeEvent.Closing)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModels.values.forEach { it.onEvent(ComposeEvent.Closing) } }
    }

    @Composable
    fun viewModelFor(composer: OpenComposer): ComposeViewModel =
        viewModels.getOrPut(composer.id) {
            ComposeViewModel(
                deps = deps,
                mode = composer.mode,
                replyTo = composer.replyToId?.let(messageById),
                editing = composer.draftId?.let(draftById),
            )
        }

    val expanded = composers.firstOrNull { it.window == ComposerWindow.Expanded }

    // The standing row, laid out left-to-right in the order they were opened so
    // the newest takes the corner. Anything expanded is not in it — it is the
    // pane below, filling the mailbox.
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = DOCK_INSET),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        composers.filter { it.window != ComposerWindow.Expanded }.forEach { composer ->
            key(composer.id) {
                ComposerHost(composer, viewModelFor(composer), deps, onWindow, onClose, onOpenSignatures)
            }
        }
    }

    expanded?.let { composer ->
        key(composer.id) {
            ComposerHost(composer, viewModelFor(composer), deps, onWindow, onClose, onOpenSignatures)
        }
    }
}

/**
 * One composer, in whichever of the three shapes it is currently wearing.
 *
 * The view model is built here and the shape chosen below it, so switching
 * shape is a re-layout rather than a rebuild — see the note on [ComposerDock].
 */
@Composable
private fun ComposerHost(
    composer: OpenComposer,
    viewModel: ComposeViewModel,
    deps: Composing,
    onWindow: (String, ComposerWindow) -> Unit,
    onClose: (String) -> Unit,
    onOpenSignatures: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                // Sent or binned, there is nothing left to show.
                ComposeEffect.Sent, ComposeEffect.Discarded -> onClose(composer.id)
                ComposeEffect.ChooseFiles ->
                    deps.chooseFiles().forEach { viewModel.onEvent(ComposeEvent.AttachFile(it)) }
                ComposeEffect.OpenSignatures -> onOpenSignatures()
            }
        }
    }

    when (composer.window) {
        ComposerWindow.Minimised -> MinimisedComposer(
            title = state.title,
            onRestore = { onWindow(composer.id, ComposerWindow.Docked) },
            onClose = { onClose(composer.id) },
        )

        ComposerWindow.Docked -> ComposerCard(
            title = state.title,
            window = composer.window,
            modifier = Modifier.width(CARD_WIDTH).height(CARD_HEIGHT),
            onMinimise = { onWindow(composer.id, ComposerWindow.Minimised) },
            onToggleExpand = { onWindow(composer.id, ComposerWindow.Expanded) },
            onClose = { onClose(composer.id) },
        ) {
            ComposeScreen(state = state, onEvent = viewModel::onEvent)
        }

        ComposerWindow.Expanded -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim)
                .padding(EXPANDED_INSET),
            contentAlignment = Alignment.Center,
        ) {
            ComposerCard(
                title = state.title,
                window = composer.window,
                modifier = Modifier.fillMaxSize(),
                onMinimise = { onWindow(composer.id, ComposerWindow.Minimised) },
                onToggleExpand = { onWindow(composer.id, ComposerWindow.Docked) },
                onClose = { onClose(composer.id) },
            ) {
                ComposeScreen(state = state, onEvent = viewModel::onEvent)
            }
        }
    }
}

/**
 * The card: a title bar with the three window controls, and the form under it.
 *
 * The bar is dark on purpose — see `ZillitColors.titleBar`. A composer standing
 * over a mailbox has to read as *in front of* it rather than as another panel
 * of it, and in a light interface a dark header is what does that at a glance.
 */
@Composable
private fun ComposerCard(
    title: String,
    window: ComposerWindow,
    modifier: Modifier,
    onMinimise: () -> Unit,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors

    Column(
        modifier = modifier
            .shadow(CARD_LIFT, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(colors.surface),
    ) {
        TitleBar(
            title = title,
            window = window,
            onMinimise = onMinimise,
            onToggleExpand = onToggleExpand,
            onClose = onClose,
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A composer collapsed to its title, still holding everything typed into it. */
@Composable
private fun MinimisedComposer(title: String, onRestore: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .width(STRIP_WIDTH)
            .shadow(CARD_LIFT, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(ZillitTheme.colors.surface),
    ) {
        TitleBar(
            title = title,
            window = ComposerWindow.Minimised,
            onMinimise = onRestore,
            onToggleExpand = onRestore,
            onClose = onClose,
        )
    }
}

/**
 * The bar, and the three things you can do to a window.
 *
 * Clicking the title itself restores a minimised composer — the strip is small
 * and the whole of it should be the target, not just the chevron on the end.
 */
@Composable
private fun TitleBar(
    title: String,
    window: ComposerWindow,
    onMinimise: () -> Unit,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val minimised = window == ComposerWindow.Minimised

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.titleBar)
            .then(if (minimised) Modifier.clickable(onClick = onMinimise) else Modifier)
            .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.xs)
            .height(TITLE_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.label,
            color = colors.titleBarText,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        BarButton(
            icon = if (minimised) ZillitIcons.Maximize else ZillitIcons.Minimize,
            description = if (minimised) "Restore $title" else "Minimise $title",
            onClick = onMinimise,
        )
        BarButton(
            icon = if (window == ComposerWindow.Expanded) ZillitIcons.Restore else ZillitIcons.Detach,
            description = if (window == ComposerWindow.Expanded) {
                "Return $title to the corner"
            } else {
                "Fill the window with $title"
            },
            onClick = onToggleExpand,
        )
        BarButton(icon = ZillitIcons.Close, description = "Close $title", onClick = onClose)
    }
}

/** A window control: the bar's own tint, because the bar is inverted. */
@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    ZillitIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        tint = ZillitTheme.colors.titleBarText,
        size = BAR_BUTTON,
    )
}

private val CARD_WIDTH = 460.dp
private val CARD_HEIGHT = 520.dp
private val STRIP_WIDTH = 280.dp
private val TITLE_BAR_HEIGHT = 40.dp
private val BAR_BUTTON = 28.dp
private val CARD_LIFT = 12.dp
private val DOCK_INSET = 24.dp
private val EXPANDED_INSET = 48.dp
private val CARD_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
