package com.zillit.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.designsystem.ZillitTheme
import androidx.compose.runtime.remember
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallLogPane
import com.zillit.desktop.feature.calls.ui.CallLogViewModel
import com.zillit.desktop.feature.calls.ui.CallOverlay
import com.zillit.desktop.feature.calls.ui.displayTitle
import com.zillit.desktop.feature.calls.domain.CallCrewEntry
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.calls.ui.themeJson
import com.zillit.desktop.feature.home.ui.decodeImageBitmap

/**
 * The calling surface, floating over the whole workspace: a ring must
 * interrupt whatever tool is open, and the in-call bar must survive tab
 * switches — parenting it to any one screen would tie the call to that
 * screen's life.
 */
@Composable
internal fun CallSurface(ready: AppGraph.Ready, calls: CallViewModel?) {
    calls ?: return
    val callState by calls.state.collectAsState()
    val engine = ready.callEngine

    // The page draws the chrome for its own video tiles, because a heavyweight
    // browser surface owns every pixel inside its rectangle and nothing Compose
    // paints there survives. These three pushes are that model: the palette so
    // the stage is not a foreign slab, the tile identities, and whether it is
    // showing full-size or shrunk into the minimised pill.
    val theme = themeJson(ZillitTheme.colors)
    LaunchedEffect(engine, theme) { engine.setTheme(theme) }
    LaunchedEffect(engine, callState.stageJson) { engine.setStage(callState.stageJson) }
    LaunchedEffect(engine, callState.expanded) { engine.setCompact(!callState.expanded) }

    CallOverlay(
        state = callState,
        onEvent = calls::onEvent,
        loadAvatar = crewFaceLoader(ready),
        videoSurface = callVideoSurface(ready),
    )
}

/**
 * The Chromium call page as a composable, when the media engine is real.
 *
 * A SwingPanel because JCEF renders into a heavyweight AWT component; it sits
 * above Compose content wherever it is placed, which is why the overlay keeps
 * the in-call bar clear of it rather than drawing over it.
 *
 * The component arrives asynchronously — Chromium takes seconds to come up —
 * so this collects it rather than reading it once, and renders nothing until
 * it exists.
 */
private fun callVideoSurface(ready: AppGraph.Ready): (@Composable () -> Unit)? {
    val engine = ready.callEngine as? KcefCallEngine ?: return null
    return {
        val component by engine.surface.collectAsState()
        component?.let { awtComponent ->
            // Handed back when the call surface leaves the screen: the engine
            // parks the component in a window of its own between calls, and a
            // browser component left with no parent at all is a browser that
            // will not work for the next call.
            DisposableEffect(awtComponent) {
                onDispose { engine.releaseSurface() }
            }
            SwingPanel(
                factory = { awtComponent },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Ends any live call when the session does.
 *
 * The call surface is mounted inside the signed-in branch, but the coordinator
 * lives on appScope and knows nothing about it. A 401 or a project switch
 * swaps that branch for the auth screen and the overlay simply vanishes — the
 * Agora client stays joined, both tracks stay published and the camera
 * indicator stays lit, with no hang-up button left anywhere to press.
 */
@Composable
internal fun EndCallOnSignOut(ready: AppGraph.Ready, signedIn: Boolean) {
    LaunchedEffect(signedIn) {
        if (!signedIn) ready.callCoordinator.hangUp()
    }
}

/** The caller's face for the ring card — the chat screen's loader, shared. */
internal fun crewFaceLoader(
    ready: AppGraph.Ready,
): suspend (String) -> ImageBitmap? =
    { userId -> fetchAvatar(ready, userId)?.let(::decodeImageBitmap) }

/**
 * The Calls tab inside Chat & Calls.
 *
 * Built here rather than in the chat tool because redialling is a calling
 * concern: the row knows a room or a device, and turning that into a ring is
 * the same [CallEvent.Place] the thread header sends. Remembered against the
 * open production, so switching set does not leave one production's history on
 * another's screen.
 */
@Composable
internal fun CallLogTab(ready: AppGraph.Ready, calls: CallViewModel) {
    val projectId = ready.projectContext?.context?.collectAsState()?.value?.project?.projectId
    val logs = remember(ready, projectId) {
        CallLogViewModel(
            api = ready.callApi,
            selfUserId = { ready.projectContext?.context?.value?.profile?.userId },
            nowMillis = System::currentTimeMillis,
            onRedial = { entry ->
                calls.onEvent(
                    CallEvent.Place(
                        // A group row rings its room; a 1:1 row the device it
                        // reached last time.
                        chatRoomId = if (entry.mode == CallMode.Group) entry.roomId else "",
                        receiverDeviceId =
                            if (entry.mode == CallMode.Group) "" else entry.peerDeviceId,
                        mode = entry.mode,
                        type = entry.type,
                        displayName = entry.displayTitle { id -> crewNameOf(ready, id) },
                    ),
                )
            },
        )
    }
    val state by logs.state.collectAsState()
    CallLogPane(
        state = state,
        onEvent = logs::onEvent,
        nameFor = { id -> crewNameOf(ready, id) },
        // Read once per composition rather than per row, so every row in one
        // frame decides "today" against the same instant.
        nowMillis = remember(state.entries) { System.currentTimeMillis() },
    )
}

/** A crew member's name, honouring the keep-private flag the lists apply. */
private fun crewNameOf(ready: AppGraph.Ready, userId: String): String? =
    ready.projectContext?.context?.value?.user(userId)
        ?.takeUnless { it.keepNamePrivate }
        ?.fullName


/**
 * The production's crew as the add-people picker wants it: only people a call
 * can actually reach (a registered device), honouring keep-name-private the
 * way the chat directory does, and never ourselves.
 */
internal fun AppGraph.Ready.callableCrew(): List<CallCrewEntry> {
    val context = projectContext?.context?.value ?: return emptyList()
    val self = context.profile?.userId
    return context.users
        .filter { !it.keepNamePrivate && it.userId != self }
        .mapNotNull { user ->
            val device = user.deviceId ?: return@mapNotNull null
            CallCrewEntry(
                userId = user.userId,
                deviceId = device,
                name = user.fullName,
                designation = user.designation.orEmpty(),
            )
        }
}
