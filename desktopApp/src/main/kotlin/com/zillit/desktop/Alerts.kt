package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.TrayState
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.notifications.DesktopNotification
import com.zillit.desktop.core.notifications.NotificationCopy
import com.zillit.desktop.core.notifications.Notifier
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.calls.domain.CallPhase
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent

/**
 * Desktop alerts for things that arrive while the user is looking elsewhere.
 *
 * Posted through the same tray presence the calendar's reminders use — the OS
 * owns the banner, which is what makes it appear over other applications.
 *
 * ## The rule that decides whether to interrupt
 *
 * Never for what is already on screen. A notification for the conversation the
 * user is reading, in a focused window, is noise about something they can see —
 * so each source suppresses itself when the window has focus *and* it is the
 * thing being looked at. Everything else is fair game, because the user cannot
 * see it.
 *
 * A ringing call is the one exception: it posts whichever window has focus.
 * The in-app ring card is already on screen in that case, but a call lasts a
 * minute and then stops existing, and the cost of a duplicate is far below the
 * cost of the one that got away.
 */
@Composable
fun IncomingAlerts(
    graph: AppGraph,
    preferences: PreferenceStore,
    trayState: TrayState,
    frame: ComposeWindow?,
    chat: ChatViewModel?,
    email: EmailViewModel?,
    crewName: (String) -> String?,
) {
    val ready = graph as? AppGraph.Ready ?: return
    val notifier: Notifier = TrayNotifier(trayState)

    MessageAlerts(ready, preferences, notifier, frame, chat, crewName)
    MailAlerts(ready, preferences, notifier, frame, email)
    EditAlerts(ready, preferences, notifier, frame, chat, crewName)
    UpdateAlerts(ready, preferences, notifier, crewName)
    CallAlerts(ready, preferences, notifier)
    ActivityAlerts(ready, preferences, notifier)
}

/**
 * A message that changed after it was read.
 *
 * Shares [ZillitPreferences.NotifyMessages] with arrivals rather than owning a
 * switch of its own: someone who wants to be told about a message wants to be
 * told when it stops saying what it said, and a separate toggle for that is a
 * distinction nobody would go looking for.
 */
@Composable
@Suppress("LongParameterList")
private fun EditAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
    frame: ComposeWindow?,
    chat: ChatViewModel?,
    crewName: (String) -> String?,
) {
    LaunchedEffect(ready, chat) {
        if (chat == null) return@LaunchedEffect
        ready.chatRepository.edits.collect { message ->
            if (message.isMine) return@collect
            if (!preferences.get(ZillitPreferences.NotifyMessages)) return@collect

            val conversation = if (message.isGroup) message.receiverId else message.senderId
            if (frame.hasFocus() && chat.state.value.peer?.userId == conversation) return@collect

            notifier.post(
                NotificationCopy.messageEdited(
                    sender = crewName(message.senderId).orEmpty(),
                    body = message.body,
                    room = if (message.isGroup) crewName(message.receiverId) else null,
                ),
            )
        }
    }
}

/**
 * A post on a Home notice board.
 *
 * Unlike messages and mail this does not suppress itself for the board on
 * screen. The Home feed does not mark anything read by being looked at, so
 * "already visible" is a guess — and a notice is the thing a production is
 * most likely to have needed five minutes ago.
 */
@Composable
private fun UpdateAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
    crewName: (String) -> String?,
) {
    LaunchedEffect(ready) {
        val me = { ready.projectContext?.context?.value?.profile?.userId }
        ready.homeRealtime.stream.collect { event ->
            if (event !is HomeRealtimeEvent.NoticeAdded) return@collect
            if (!preferences.get(ZillitPreferences.NotifyUpdates)) return@collect

            // The server echoes a post back to whoever made it, and a locally
            // composed one still carries the id we generated — either is us.
            val notice = event.notice
            if (notice.localId != null) return@collect
            if (notice.authorId != null && notice.authorId == me()) return@collect

            notifier.post(
                NotificationCopy.notice(
                    author = notice.authorName.ifBlank { crewName(notice.authorId.orEmpty()).orEmpty() },
                    body = notice.body,
                    attachmentName = notice.attachment?.fileName,
                ),
            )
        }
    }
}

/**
 * A call ringing this device.
 *
 * Driven off the coordinator's phase rather than the socket, so it inherits
 * every gate the state machine already applies — our own ring echoed back, an
 * invite arriving while another call is up, a call answered on the user's
 * phone. Re-reading `cnc:incoming-call` here would notify for all three.
 */
@Composable
private fun CallAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
) {
    LaunchedEffect(ready) {
        ready.callCoordinator.phase.collect { phase ->
            if (phase != CallPhase.Incoming) return@collect
            if (!preferences.get(ZillitPreferences.NotifyCalls)) return@collect
            val session = ready.callCoordinator.session.value ?: return@collect

            notifier.post(
                NotificationCopy.incomingCall(
                    caller = session.displayName,
                    room = session.title,
                    hasVideo = session.hasVideo,
                ),
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun MessageAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
    frame: ComposeWindow?,
    chat: ChatViewModel?,
    crewName: (String) -> String?,
) {
    LaunchedEffect(ready, chat) {
        if (chat == null) return@LaunchedEffect
        ready.chatRepository.incoming.collect { message ->
            if (message.isMine) return@collect
            if (!preferences.get(ZillitPreferences.NotifyMessages)) return@collect

            val conversation = if (message.isGroup) message.receiverId else message.senderId
            val open = chat.state.value.peer?.userId
            if (frame.hasFocus() && open == conversation) return@collect

            notifier.post(
                DesktopNotification(
                    title = crewName(message.senderId) ?: "New message",
                    body = message.cardPreview(crewName),
                ),
            )
        }
    }
}

@Composable
private fun MailAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
    frame: ComposeWindow?,
    email: EmailViewModel?,
) {
    LaunchedEffect(ready, email) {
        // Unread *rising* is what means new mail. `FolderChanged` also fires
        // for a move or a delete made on another device, and announcing those
        // as arrivals would be a lie the user cannot check.
        var lastUnread: Int? = null

        ready.emailRealtime.stream.collect { event ->
            if (event !is EmailRealtimeEvent.FolderChanged) return@collect
            if (!preferences.get(ZillitPreferences.NotifyMail)) return@collect

            val unread = ready.emailRepository.inboxUnread() ?: return@collect
            val previous = lastUnread
            lastUnread = unread
            // The first reading is the baseline, not an event.
            if (previous == null || unread <= previous) return@collect

            val inboxOpen = email?.state?.value?.selectedFolder?.name
                ?.equals(EmailFolder.INBOX, ignoreCase = true) == true
            if (frame.hasFocus() && inboxOpen) return@collect

            val arrived = unread - previous
            notifier.post(
                DesktopNotification(
                    title = if (arrived == 1) "New mail" else "$arrived new messages",
                    body = "Waiting in your inbox.",
                ),
            )
        }
    }
}

/** Null before the frame exists, and false while it sits behind something. */
private fun ComposeWindow?.hasFocus(): Boolean = this?.isFocused == true

/** The inbox's unread count, or null when the mailbox cannot say. */
private suspend fun com.zillit.desktop.feature.email.domain.EmailRepository.inboxUnread(): Int? =
    (folders() as? ZillitResult.Success)
        ?.data
        ?.firstOrNull { it.name.equals(EmailFolder.INBOX, ignoreCase = true) }
        ?.unreadCount

/**
 * Everything else the production did — the phones' bell list, as banners: a
 * purchase order approved, a document shared, an SOS raised.
 *
 * On the phones and the web these banners are FCM pushes; the socket's
 * `notification:save` only feeds badges there. The desktop has no push channel
 * (plan risk 15), so the same record is decoded off the socket instead — the
 * banner that on every other platform the server composes remotely.
 *
 * Sections a dedicated alert above already covers are left out, or one chat
 * message would banner twice — once from `private_chat`, once from its
 * bell-list record. Not focus-suppressed, for UpdateAlerts' reason: there is
 * no one screen that marks this activity "already seen".
 */
@Composable
private fun ActivityAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
) {
    LaunchedEffect(ready) {
        // A reconnect can replay a frame the socket already delivered; iOS
        // dedupes the same way (a 60-second id memory in willPresent).
        val recent = ArrayDeque<String>()

        ready.socketEvents.on(ZillitSocketEvents.Badges.Save).collect { message ->
            if (!preferences.get(ZillitPreferences.NotifyActivity)) return@collect

            val banner = ready.notificationsRepository.banner(message.payload) ?: return@collect
            if (banner.section in SECTIONS_WITH_OWN_ALERTS) {
                ZillitLog.d(ACTIVITY_TAG) { "left to the ${banner.section} alert" }
                return@collect
            }
            if (banner.id in recent) {
                ZillitLog.d(ACTIVITY_TAG) { "duplicate frame for ${banner.section}" }
                return@collect
            }

            recent.addLast(banner.id)
            if (recent.size > RECENT_ACTIVITY_IDS) recent.removeFirst()

            ZillitLog.i(ACTIVITY_TAG) { "posting a ${banner.section} banner" }
            notifier.post(NotificationCopy.activity(area = banner.area, body = banner.body))
        }
    }
}

/**
 * The wire sections whose banners already come from a dedicated alert above:
 * chat and calls ride `cnc_label`, mail `email_label`, notice boards
 * `home_label` — and the two budget sections, because a budget chat message
 * travels the same `private_chat`/`group_chat` events MessageAlerts banners
 * (iOS handles budget tools inside its private_chat handler, and its bell
 * records for them carry these sections), so letting them through here would
 * banner one message twice. `group_refresh` is the server's own refresh
 * marker, not an event anyone needs told about.
 */
private val SECTIONS_WITH_OWN_ALERTS = setOf(
    "cnc_label",
    "email_label",
    "home_label",
    "group_refresh",
    "main_budget_label",
    "department_budget_label",
)

/** Enough ids to outlive any reconnect replay without growing forever. */
private const val RECENT_ACTIVITY_IDS = 64

private const val ACTIVITY_TAG = "ActivityAlerts"
