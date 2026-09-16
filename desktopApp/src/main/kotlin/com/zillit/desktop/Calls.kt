package com.zillit.desktop

import androidx.compose.foundation.layout.fillMaxSize
import com.zillit.desktop.feature.calls.ui.CallLogEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.awt.BorderLayout
import javax.swing.JPanel
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.designsystem.ZillitTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.session.ProjectContext
import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallLogPane
import com.zillit.desktop.feature.calls.ui.CallLogViewModel
import com.zillit.desktop.feature.calls.ui.OngoingCallsSource
import com.zillit.desktop.feature.calls.ui.CallOverlay
import com.zillit.desktop.feature.calls.ui.displayTitle
import com.zillit.desktop.feature.calls.domain.CallCrewEntry
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.calls.ui.reactionJson
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

    // Its own window, so the call's heavyweight browser surface cannot paint
    // over it. See ShareSourceWindow.
    callState.sharePicker?.let { picker ->
        ShareSourceWindow(picker = picker, onEvent = calls::onEvent)
    }

    // The page draws the chrome for its own video tiles, because a heavyweight
    // browser surface owns every pixel inside its rectangle and nothing Compose
    // paints there survives. These three pushes are that model: the palette so
    // the stage is not a foreign slab, the tile identities, and whether it is
    // showing full-size or shrunk into the minimised pill.
    val theme = themeJson(ZillitTheme.colors)
    LaunchedEffect(engine, theme) { engine.setTheme(theme) }
    LaunchedEffect(engine, callState.stageJson) { engine.setStage(callState.stageJson) }
    // See CallUiState.pageCompact for which states are actually small — it is
    // not simply `!expanded`, and getting that wrong hides every participant
    // but one.
    LaunchedEffect(engine, callState.pageCompact) { engine.setCompact(callState.pageCompact) }

    /*
     * Reactions go to the page as they arrive, and each one only once.
     *
     * A video call's picture is a heavyweight surface, so the Compose layer
     * that draws these on an audio call would rise *behind* the video and never
     * be seen; the page draws them itself instead. Keyed on the list, and the
     * already-sent set is what stops a recomposition replaying every emoji
     * still in flight.
     */
    var sentReactions by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(engine, callState.reactions) {
        callState.reactions
            .filterNot { it.key in sentReactions }
            .forEach { engine.showReaction(reactionJson(it)) }
        sentReactions = callState.reactions.mapTo(mutableSetOf()) { it.key }
    }

    /*
     * Faces for the page's own tiles, fetched once each.
     *
     * The page draws the tile chrome — it has to, since a heavyweight browser
     * surface paints over anything Compose puts inside its rectangle — so a
     * tile whose camera is off falls back to whatever the PAGE can draw. That
     * used to be initials, which made a video call with the camera off look
     * plainer than the audio call it had just been, where Compose draws the
     * real photograph. Handing the same picture over closes that gap.
     *
     * Keyed on the tiles' people, and the fetched set is what stops a
     * recomposition re-fetching a face that is already on the page. Someone
     * with no picture is remembered as attempted, so a missing avatar costs
     * one request per call rather than one per recomposition.
     */
    var fetchedFaces by remember { mutableStateOf(emptySet<String>()) }
    val faceOwners = callState.tiles.map { it.userId }.filter { it.isNotBlank() }
    LaunchedEffect(engine, faceOwners) {
        faceOwners.filterNot { it in fetchedFaces }.forEach { userId ->
            fetchedFaces = fetchedFaces + userId
            fetchAvatar(ready, userId)?.let { bytes ->
                engine.setAvatar(userId, dataUri(bytes))
            }
        }
    }

    CallOverlay(
        state = callState,
        onEvent = calls::onEvent,
        loadAvatar = crewFaceLoader(ready),
        videoSurface = callVideoSurface(ready),
    )
}

/**
 * Image bytes as a `data:` URI the page can put in an `<img>`.
 *
 * The type is declared as PNG regardless of what the bytes actually are:
 * browsers sniff image data and ignore the declared type, and the storage
 * layer does not tell us which format it handed back.
 */
private fun dataUri(bytes: ByteArray): String =
    "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)

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
internal fun callVideoSurface(ready: AppGraph.Ready): (@Composable () -> Unit)? {
    val engine = ready.callEngine as? KcefCallEngine ?: return null
    return {
        val component by engine.surface.collectAsState()
        component?.let { awtComponent ->
            /*
             * A holder of this host's own, with the browser inside it.
             *
             * Handing the shared browser component straight to SwingPanel
             * makes it that panel's only child, so moving it to another host
             * leaves an empty interop group behind — and Compose measures that
             * group while the losing window is being disposed:
             *
             *     ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
             *       at SwingInteropViewGroup.getPreferredSize
             *       ... ComposeWindow.dispose ... Recomposer.runRecomposeAndApplyChanges
             *
             * That throw escapes into the recomposer, which stops the whole UI
             * updating — the call is still connected, still audible, and there
             * is no longer any way back to it. It is what happens when the call
             * window is shrunk or re-homed mid-call, and a screen share is when
             * users actually do that.
             *
             * With a holder, each host's interop group always has exactly one
             * child of its own and the contested re-parenting happens a level
             * down, where nothing measures. Correct whichever way the two
             * SwingPanels' disposal happens to interleave, which is why it is
             * preferred to depending on that order.
             */
            val holder = remember(awtComponent) { JPanel(BorderLayout()) }
            // Whether the picture ever reaches a window is the first
            // question about "video is not rendering"; say when it does.
            androidx.compose.runtime.LaunchedEffect(holder) {
                com.zillit.desktop.core.common.ZillitLog.i("Calls") { "video surface mounted in a window" }
            }
            // Taken as this host mounts and handed back as it leaves, so a
            // host that has already been superseded cannot park a component
            // the next one is holding. See [KcefCallEngine.hostSurface].
            DisposableEffect(awtComponent) {
                holder.add(awtComponent, BorderLayout.CENTER)
                val lease = engine.hostSurface()
                onDispose {
                    // Out of the holder first: the engine parks the component
                    // in a window of its own between calls, and a browser left
                    // with no parent at all is one that will not work for the
                    // next call.
                    holder.remove(awtComponent)
                    lease.release()
                }
            }
            SwingPanel(
                factory = { holder },
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
internal fun CallLogTab(
    ready: AppGraph.Ready,
    calls: CallViewModel,
    /** Another production's history — a widget showing one. Null is the open production's. */
    otherProjectId: String? = null,
    /** The reader's id ON [otherProjectId]. */
    otherUserId: String = "",
) {
    val openProjectId = ready.projectContext?.context?.collectAsState()?.value?.project?.projectId
    val projectId = otherProjectId ?: openProjectId
    val logs = remember(ready, projectId) {
        CallLogViewModel(
            api = ready.callApi,
            selfUserId = {
                otherUserId.takeIf { it.isNotBlank() }
                    ?: ready.projectContext?.context?.value?.profile?.userId
            },
            nowMillis = System::currentTimeMillis,
            onRedial = { entry, line -> redialFromLog(ready, calls, entry, line, otherProjectId, otherUserId) },
            projectId = otherProjectId,
            callerUserId = otherUserId,
            // The web's Ongoing rows: the calling socket's live list, and
            // which call is open here so its row says Return.
            ongoing = OngoingCallsSource(
                activeCalls = ready.callCoordinator.activeCalls,
                liveCallId = ready.callCoordinator.session.map { it?.callUuid?.takeIf(String::isNotBlank) },
                onJoin = { ongoing -> ready.callCoordinator.joinActiveCall(ongoing.callId) },
                onReturn = { if (!calls.state.value.expanded) calls.onEvent(CallEvent.ToggleStage) },
            ),
        )
    }
    // A call that just ended belongs in Recents now, not after the user
    // thinks to switch tabs. The web had the same hole and closed it by
    // listening for the call-ended event (`CNC_FIXES_CHANGELOG.md` Fix 6);
    // this listens to the coordinator both tabs already share.
    LaunchedEffect(logs) {
        ready.callCoordinator.ended.collect { logs.onEvent(CallLogEvent.Refresh) }
    }
    val state by logs.state.collectAsState()
    CallLogPane(
        state = state,
        onEvent = logs::onEvent,
        nameFor = { id -> crewNameOf(ready, id) },
        // Read once per composition rather than per row, so every row in one
        // frame decides "today" against the same instant.
        nowMillis = remember(state.entries) { System.currentTimeMillis() },
        // The same lines the thread header offers: Line 3 where the
        // roll-out list names this production.
        lines = if (ready.lineThreeEnabled(projectId)) CallLine.DEFAULT + CallLine.Three else CallLine.DEFAULT,
    )
}

/**
 * A history row rung again, on the line the picker chose — Android's
 * `RecentCallFragment` call-back (`RecentCallFragment.kt:519-575`).
 *
 * A group row rings its room. A 1:1 row rings the person: the row's own
 * device id when it has one (Line 2 rows do), else the person's current
 * device off the crew list — Line 1 and Line 3 rows carry no device id at
 * all, which is why they could not be rung from here before. Line 1 rings
 * by user id regardless (`CallEvent.Place.receiverUserId`), and someone no
 * longer on the production is refused with Android's `user_not_active_txt`
 * rather than rung into silence.
 *
 * Answers the refusal, or null once the call is being placed.
 */
@Suppress("LongParameterList") // The row, the line, and the widget's other-production pair.
private fun redialFromLog(
    ready: AppGraph.Ready,
    calls: CallViewModel,
    entry: CallLogEntry,
    line: CallLine,
    otherProjectId: String?,
    otherUserId: String,
): String? {
    val isGroup = entry.mode == CallMode.Group
    val receiverDeviceId = if (isGroup) {
        ""
    } else {
        when (val target = ready.directTarget(entry, otherProjectId)) {
            is DirectTarget.Device -> target.deviceId
            is DirectTarget.Refused -> return target.reason
        }
    }
    calls.onEvent(
        CallEvent.Place(
            chatRoomId = if (isGroup) entry.roomId else "",
            receiverDeviceId = receiverDeviceId,
            mode = entry.mode,
            type = entry.type,
            displayName = entry.displayTitle { id -> crewNameOf(ready, id) },
            provider = line.provider,
            receiverUserId = if (isGroup) "" else entry.peerUserId,
            projectId = otherProjectId,
            callerUserId = otherUserId,
        ),
    )
    return null
}

/** What a 1:1 row resolves to: a device to ring, or why not. */
private sealed interface DirectTarget {
    data class Device(val deviceId: String) : DirectTarget
    data class Refused(val reason: String) : DirectTarget
}

/**
 * The device a 1:1 row rings: its own when it has one, else the person's
 * current device off the crew list. The widget's other-production history
 * has no crew list here, so the row's own device is all it can go on. A
 * device may legitimately be blank for Line 3, which rings by user id.
 */
private fun AppGraph.Ready.directTarget(entry: CallLogEntry, otherProjectId: String?): DirectTarget {
    if (otherProjectId != null) return DirectTarget.Device(entry.peerDeviceId)
    val peer = projectContext?.context?.value?.user(entry.peerUserId)
    val gone = peer == null || !peer.hasJoined() || peer.status in LEFT_STATUSES
    if (gone) return DirectTarget.Refused(USER_NOT_ACTIVE)
    val device = entry.peerDeviceId.ifBlank { peer.deviceId.orEmpty() }
    if (device.isBlank() && entry.peerUserId.isBlank()) return DirectTarget.Refused(CALL_FAILED)
    return DirectTarget.Device(device)
}

/** Crew rows that are still listed but cannot be rung — `ProjectUser.status`. */
private val LEFT_STATUSES = setOf("left", "removed")

/** Android's `user_not_active_txt` (`res/values/strings.xml:3431`). */
private const val USER_NOT_ACTIVE = "User is not active in this project."

/** Android's `txt_error_call` (`res/values/strings.xml:942`). */
private const val CALL_FAILED = "Facing issues while starting a call."

/** A crew member's name, honouring the keep-private flag the lists apply. */
internal fun crewNameOf(ready: AppGraph.Ready, userId: String): String? =
    ready.projectContext?.context?.value?.user(userId)
        ?.takeUnless { it.keepNamePrivate }
        ?.fullName


/**
 * The in-call name book: user id → the name we are allowed to show.
 *
 * Line 1 group rosters arrive with no names on them at all, so every face on
 * the stage read "Guest". This is what the tiles fall back to.
 *
 * Keep-name-private members are left OUT of the map rather than mapped to a
 * blank: a lookup then cannot reveal one, and no future refactor of the
 * caption ladder can turn their absence into an empty caption. Placeholder
 * names go too — `fullName` degrades to the email address and then to
 * "Unknown" (ProjectContextLoader), and a call stage is screen-shared and
 * recorded, which is the wrong place to paint somebody's email address.
 * Ourselves as well: the self tile is built separately and says "You".
 */
internal fun ProjectContext.callNameDirectory(): Map<String, String> {
    val self = profile?.userId
    return users.asSequence()
        .filter { !it.keepNamePrivate && it.userId != self }
        .mapNotNull { user ->
            user.fullName
                .takeIf { it.isNotBlank() && it != user.email && it != "Unknown" }
                ?.let { user.userId to it }
        }
        .toMap()
}

/** [callNameDirectory] as the calls view model wants it — see its `nameDirectory`. */
internal fun AppGraph.Ready.callNameDirectory(): Flow<Map<String, String>> =
    projectContext?.context?.map { it.callNameDirectory() }?.distinctUntilChanged()
        ?: flowOf(emptyMap())


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
                designation = user.designationText().orEmpty(),
            )
        }
}
