@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.data.StillsUploadQueue
import com.zillit.desktop.feature.selectstills.data.UploadQueueState
import com.zillit.desktop.feature.selectstills.data.countsOf

/**
 * Select Stills — the tool's shell.
 *
 * The look is the web's (`StillKills.css`): its studio palette (and a light
 * one beside it, as the app has), a top bar with the tool's pages, and a page
 * that scrolls under it and takes the tool's whole width. One window owns all
 * five pages; the web nests them as routes, but nothing outside the tool links
 * to one.
 *
 * Rights gate everything. Until the tool's row has answered nothing is shown
 * or requested; a production without the tool, or a reader without view
 * rights, gets a plain explanation rather than a request the service would
 * answer with a 403 — which this app reads as "no longer a member".
 */
@Composable
fun StillsScreen(
    state: StillsUiState,
    onEvent: (StillsEvent) -> Unit,
    modifier: Modifier = Modifier,
    queue: StillsUploadQueue? = null,
) {
    StillsThemeProvider {
        val k = StillsTheme.c
        val uploads by (queue?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(UploadQueueState()) }).collectAsState()

        Box(modifier.fillMaxSize().background(k.bg)) {
            Column(Modifier.fillMaxSize()) {
                StillsTopBar(state, uploads, onEvent)
                // The tool's own width, for the layouts that change on a wide
                // tool (the web's `@container stk (min-width: 1100px)`): a
                // tool window can be narrow on a wide screen.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalStillsWidth provides maxWidth) {
                        when {
                            // The rights list has not answered: nothing may call the
                            // service until it does.
                            !state.viewer.resolved -> SPageBox { SLoading() }
                            !state.viewer.enabled -> SPageBox {
                                SStatePanel(str(S.desktop_stk_gate_disabled_title), str(S.desktop_stk_gate_disabled_hint))
                            }
                            !state.viewer.canView -> SPageBox {
                                SStatePanel(str(S.desktop_stk_gate_noview_title), str(S.desktop_stk_gate_noview_hint)) {
                                    SBtn(str(S.desktop_request_access), { onEvent(StillsEvent.AskForRights) }, kind = SBtnKind.Plain, small = true)
                                }
                            }
                            state.meState == MeState.Idle || state.meState == MeState.Loading -> SPageBox { SLoading() }
                            state.meState == MeState.Error -> SPageBox {
                                SStatePanel(str(S.desktop_stk_me_failed_title), str(S.desktop_stk_me_failed_hint), bad = true) {
                                    SBtn(str(S.desktop_csync_try_again), { onEvent(StillsEvent.ReloadMe) }, small = true)
                                }
                            }
                            else -> StillsPages(state, uploads, onEvent)
                        }
                    }
                }
            }

            state.lightbox?.let { box -> PhotoLightbox(state, box, onEvent) }
            state.memberDialog?.let { dialog -> MemberDialog(state, dialog, onEvent) }
            state.enroll?.takeIf { it.inDialog }?.let { form -> NewMemberDialog(state, form, onEvent) }

            ZillitErrorToast(
                message = state.error ?: state.notice,
                onDismiss = { onEvent(StillsEvent.DismissMessage) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

/** Which page is drawn, once the gate is open. */
@Composable
private fun StillsPages(state: StillsUiState, uploads: UploadQueueState, onEvent: (StillsEvent) -> Unit) {
    // A page the reader may not have is never drawn; the landing choice keeps
    // the bar and the body in step.
    val page = if (state.page in state.pages) state.page else state.pages.firstOrNull() ?: StillsPage.Photos
    when (page) {
        StillsPage.Photos -> PhotosPage(state, onEvent)
        StillsPage.Review -> ReviewPage(state, onEvent)
        StillsPage.Upload -> UploadPage(state, uploads, onEvent)
        StillsPage.Cast -> CastPage(state, onEvent)
        StillsPage.Settings -> SettingsPage(state, onEvent)
    }
}

/**
 * `.stk-topbar` — the tool's pages, and on the right what the service is still
 * working on (or an upload that is still running, which carries on behind
 * every page and is the way back to it).
 *
 * No wordmark: the demo's amber "Select Stills" read as a second logo inside
 * Zillit, and the web took it out at the product owner's request (8 October).
 * Do not put the tool's name or a logo back in the bar — the window's title
 * says which tool it is.
 *
 * No "‹ Film Tools" exit: the web draws one only when the tool is NOT in a
 * tool window (`!inWindow`), and here it always is — the window has its own
 * way out, and a second one in the bar would be the tool offering to close
 * something it does not own.
 */
@Composable
private fun StillsTopBar(state: StillsUiState, uploads: UploadQueueState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(k.bar)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val ready = state.meState == MeState.Ready
        val pages = if (ready) StillsPage.entries.filter { it in state.pages } else emptyList()
        val views = str(S.desktop_stk_views)
        Row(
            Modifier.weight(1f).semantics { contentDescription = views },
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { page ->
                NavLink(
                    label = str(page.title),
                    active = page == state.page,
                    // How many photos wait on this person, beside "Approvals".
                    count = state.reviewCounts.pending.takeIf { page == StillsPage.Review && it > 0 },
                    onClick = { onEvent(StillsEvent.Open(page)) },
                )
            }
        }

        if (ready) StatusSlot(state, uploads, onEvent)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
    }
}

/** One destination in the bar — `.stk-topbar nav a`, with the accent underline when it is open. */
@Composable
private fun NavLink(label: String, active: Boolean, count: Int?, onClick: () -> Unit) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        // `width(IntrinsicSize.Max)` is load-bearing: the accent underline
        // below fills this column's width, and a `fillMaxWidth` child inside a
        // Row makes its column take the whole bar — which left the first
        // destination holding all the space and pushed the other four off the
        // end (seen live 2026-10-07, invisible to the render tests because
        // `assertExists` finds a node that is composed but not displayed).
        Modifier
            .fillMaxHeight()
            .width(IntrinsicSize.Max)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SText(label, 15, androidx.compose.ui.text.font.FontWeight.SemiBold, if (active || hovered) k.ink else k.muted, maxLines = 1)
            if (count != null) {
                SText(
                    text = count.toString(),
                    size = 11,
                    weight = Bold,
                    color = k.accentInk,
                    modifier = Modifier
                        .background(k.accent.copy(alpha = 0.22f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) k.accent else Color.Transparent))
    }
}

/**
 * The bar's right-hand slot. For production: an upload that is still running,
 * otherwise how many photos the service is still working on. Nothing for
 * anybody else — they have nothing in the queue.
 */
@Composable
private fun StatusSlot(state: StillsUiState, uploads: UploadQueueState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    if (!state.canPost) return
    val counts = countsOf(uploads.items)
    val showUpload = uploads.items.isNotEmpty() && (uploads.running || counts.failed > 0)

    if (showUpload) {
        Row(
            Modifier.clickable { onEvent(StillsEvent.Open(StillsPage.Upload)) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SDot(k.warn)
            SText(
                text = if (uploads.running) {
                    str(S.desktop_stk_upload_progress, counts.done, counts.expected)
                } else {
                    str(S.desktop_stk_upload_failed_n, counts.failed)
                },
                size = 13,
                color = k.accentInk,
                maxLines = 1,
            )
            SProgress(counts.percent, Modifier.width(64.dp), height = 4.dp)
        }
        return
    }

    val waiting = state.me.queueWaiting
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SDot(if (waiting > 0) k.warn else k.ok)
        SText(
            text = if (waiting > 0) str(S.desktop_stk_n_processing, waiting) else str(S.desktop_stk_up_to_date),
            size = 13,
            color = k.muted,
            maxLines = 1,
        )
    }
}

/**
 * `.stk-page` — the page's own column, scrolling under the bar, as wide as the
 * tool. (Held to a 1280 column until the web's 8 October pass: on a 1920
 * screen that left 340 empty on each side.) What should not stretch is held to
 * its own width where it is laid out.
 */
@Composable
internal fun SPageBox(
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val state = rememberScrollState()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (scroll) Modifier.zillitVerticalScroll(state) else Modifier)
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        if (scroll) ZillitScrollRail(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** The tool's own width; unspecified outside the shell (a page drawn on its own). */
internal val LocalStillsWidth = compositionLocalOf { Dp.Unspecified }

/**
 * Whether the tool is wide enough for the layouts that use its width — the
 * web's `@container stk (min-width: 1100px)`: Cast's form beside a grid of
 * cast, Settings' three sections side by side, an upload's list beside the
 * drop zone.
 */
@Composable
internal fun stillsWide(): Boolean {
    val width = LocalStillsWidth.current
    return width != Dp.Unspecified && width >= WIDE_AT
}

private val WIDE_AT = 1100.dp

/** Asks the view model to load once per view model, as every tool does. */
@Composable
internal fun StillsLoad(onEvent: (StillsEvent) -> Unit, key: Any?) {
    LaunchedEffect(key) { onEvent(StillsEvent.Load) }
}
