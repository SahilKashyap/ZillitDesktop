package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.TrayState
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.datastore.PreferenceKey
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
import com.zillit.desktop.feature.notifications.domain.ActivityBanner
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
 * A ringing call follows the same rule, read off the one surface that answers
 * it: the ring card drawn in the frame. With that card up in a focused window
 * the banner lands on top of the very buttons the user is reaching for, so it
 * stands down. Everywhere else — behind another app, minimised, in the tray,
 * on the production picker — it posts, because a call lasts a minute and then
 * stops existing, and the cost of a duplicate is far below the cost of the one
 * that got away.
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
    /**
     * Whether the frame has the in-window ring card up — true only while the
     * signed-in shell is on screen, matching [IncomingCallWidget]'s own gate.
     * False on the production picker and the sign-in pages, where the frame
     * rings for nobody and the banner is all there is.
     */
    shellShowsRing: Boolean,
) {
    val ready = graph as? AppGraph.Ready ?: return
    val notifier: Notifier = TrayNotifier(trayState)

    MessageAlerts(ready, preferences, notifier, frame, chat, crewName)
    MailAlerts(ready, preferences, notifier, frame, email)
    EditAlerts(ready, preferences, notifier, frame, chat, crewName)
    UpdateAlerts(ready, preferences, notifier, crewName)
    CallAlerts(ready, preferences, notifier, frame, shellShowsRing)
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
 *
 * Stands down for the frame's own ring card, on the rule every other source
 * here follows: the card covers the window, carries Accept and Decline, and a
 * banner over it hides the answer rather than announcing the call.
 */
@Composable
private fun CallAlerts(
    ready: AppGraph.Ready,
    preferences: PreferenceStore,
    notifier: Notifier,
    frame: ComposeWindow?,
    shellShowsRing: Boolean,
) {
    // Read through the latest composition: the shell comes and goes with the
    // open production, and a value captured when the collector started would
    // answer for whatever was on screen then.
    val ringsInFrame by rememberUpdatedState(shellShowsRing)
    LaunchedEffect(ready) {
        ready.callCoordinator.phase.collect { phase ->
            if (phase != CallPhase.Incoming) return@collect
            if (!preferences.get(ZillitPreferences.NotifyCalls)) return@collect
            if (frame.hasFocus() && ringsInFrame) return@collect
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
                    title = crewName(message.senderId) ?: str(S.notification_redacted_new_message),
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
                    title = if (arrived == 1) str(S.new_mail) else str(S.desktop_n_new_messages, arrived),
                    body = str(S.desktop_waiting_in_inbox),
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
 * What a dedicated alert above already covers is left out — see
 * [coveredByItsOwnAlert] — or one chat message would banner twice, once from
 * `private_chat` and once from its bell-list record. Not focus-suppressed, for
 * UpdateAlerts' reason: there is no one screen that marks this activity
 * "already seen".
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
            // The decode comes before the switch, unlike every other alert
            // here: which switch this record answers to is written inside it
            // (see [switch]), so there is nothing to check until it is read.
            val banner = ready.notificationsRepository.banner(message.payload) ?: return@collect
            if (!preferences.get(banner.switch())) {
                ZillitLog.d(ACTIVITY_TAG) { "${banner.section} banners are switched off" }
                return@collect
            }
            if (coveredByItsOwnAlert(banner)) {
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
 * The switch this record answers to.
 *
 * Not always [ZillitPreferences.NotifyActivity], because this one collector
 * now carries records that belong to three of the alerts above. A comment on a
 * notice, a unit renamed and a calendar invite are all `home_label`, and
 * someone who turned Home updates off meant those too. A missed call is a
 * call, and a room created or left is chat — the switches their siblings
 * already use, or Settings would say "calls" and still ring about one.
 */
internal fun ActivityBanner.switch(): PreferenceKey.BooleanKey = when {
    section == HOME_SECTION -> ZillitPreferences.NotifyUpdates
    action in MISSED_CALL_ACTIONS -> ZillitPreferences.NotifyCalls
    section == CNC_SECTION -> ZillitPreferences.NotifyMessages
    else -> ZillitPreferences.NotifyActivity
}

/**
 * Whether one of the dedicated alerts above already banners this record.
 *
 * Three different wire fields answer that, which is the whole point of reading
 * it off the record rather than off the section alone:
 *
 *  - **Section**, where a whole area has its own alert: mail rides
 *    `email_label`. `group_refresh` is not an area at all but the server's own
 *    refresh marker, and nobody needs telling.
 *  - **Tool**, for the budget's chat. A budget message travels the same
 *    `private_chat`/`group_chat` events [MessageAlerts] banners, so its
 *    bell-list record must not banner a second time — and that record is
 *    `section = tools_label`, `tool = *_budget_tool_chat_label`. This used to
 *    be written as the sections `main_budget_label` /
 *    `department_budget_label`, which are TOOL names (`ToolWireNames`
 *    `WIRE_TOOL_LABELS`): the wire has only the seven sections in
 *    `BadgeSections`, so neither value could ever match and every budget chat
 *    message bannered twice.
 *  - **Action**, for Home. [UpdateAlerts] banners a *post on a board* and
 *    nothing else, so only the post's own actions are covered here. Excluding
 *    the whole `home_label` section also swallowed comments and replies, units
 *    created, renamed and deleted, and every calendar invite, update and
 *    reply — all of which the web banners (`notificationRouteMatcher.js:86`).
 *  - **Action** again for `cnc_label`, the other way round: there the alerts
 *    above read nearly everything, so only the listed exceptions come through
 *    (see [CNC_ACTIONS_WITHOUT_AN_ALERT]). An allow-list rather than a
 *    deny-list because the chat vocabulary is long and an unrecognised message
 *    action must stay quiet here — getting it wrong the other way would
 *    double-banner the most frequent event in the app.
 */
internal fun coveredByItsOwnAlert(banner: ActivityBanner): Boolean = when {
    banner.tool in BUDGET_CHAT_TOOLS -> true
    banner.section == CNC_SECTION -> banner.action !in CNC_ACTIONS_WITHOUT_AN_ALERT
    banner.section == HOME_SECTION -> banner.action in NOTICE_ACTIONS
    banner.section in SECTIONS_WITH_OWN_ALERTS -> true
    else -> false
}

/** Areas whose every record is bannered by an alert above. */
private val SECTIONS_WITH_OWN_ALERTS = setOf(
    "email_label",
    "group_refresh",
)

/** Chat, calls and everything else that travels the chat socket. */
private const val CNC_SECTION = "cnc_label"

/**
 * A call that was not answered — the one `cnc_label` record [CallAlerts]
 * cannot raise, because the phase it watches has ended by then.
 *
 * The web banners these from the push and rewrites its ring notification into
 * one (`firebase-messaging-sw.js:127`, `CloudMessage.jsx:310`). Here the
 * ring banner was transient to begin with, so this is simply the record
 * arriving on its own.
 */
private val MISSED_CALL_ACTIONS = setOf(
    "cnc_missed_call_private",
    "cnc_missed_call_group",
)

/**
 * The `cnc_label` records no alert above reads: a missed call, and the life of
 * a room — created, renamed, members added or removed, somebody left, deleted,
 * an admin made or unmade.
 *
 * [MessageAlerts] and [EditAlerts] read `private_chat`, `group_chat`,
 * `update_reaction` and the two edit events, and [CallAlerts] the coordinator's
 * ringing phase; none of them carries any of these. The room's own socket
 * events reach the desktop as `ChatRepository.roomChanges`, which is a bare
 * pulse with no payload — there is nothing in it to word a banner from, so the
 * bell record is the only thing that can say what happened, in the server's own
 * wording ("A new chat group {{group_name}} has been created",
 * `zillit_web src/utils/language/en.js:4981`).
 *
 * Names from the web's own `cnc_label` list (`notificationRouteMatcher.js:
 * 183-195`) and its missed-call handler. The admin pair and the rename are the
 * `_message` keys at `en.js:4971-4987` with that suffix dropped: unverified
 * spellings, and deliberately harmless either way — a name no record carries
 * leaves that record exactly as quiet as it was before.
 */
private val CNC_ACTIONS_WITHOUT_AN_ALERT: Set<String> = MISSED_CALL_ACTIONS + setOf(
    "cnc_new_chat_group_created",
    "cnc_updated_chat_group",
    "cnc_chat_room_member_leaving",
    "cnc_chat_room_removed",
    "cnc_chat_group_name_change",
    "cnc_added_admin_chat_group",
    "cnc_removed_admin_chat_group",
)

/** The notice boards, where only some actions have an alert of their own. */
private const val HOME_SECTION = "home_label"

/**
 * A post on a board — what [UpdateAlerts] banners off `home:message:added`,
 * with or without the attachment that came instead of words ("New message
 * received", "New attachment has been shared by …", "A new attachment has been
 * posted in the home", `zillit_web src/utils/language/en.js:4436,4449,5159`).
 *
 * Each in both spellings, because the backend is not consistent about the
 * suffix and the web's own matcher hedges the same way
 * (`notificationRouteMatcher.js:94-99` tests `home_new_unit_created` and
 * `home_new_unit_created_message` side by side). Either spelling names the
 * same event, so covering both can only prevent a double banner.
 */
private val NOTICE_ACTIONS = setOf(
    "home_new_unit_chat",
    "home_new_unit_chat_message",
    "home_new_unit_chat_file_received",
    "home_new_unit_chat_file_received_message",
    "home_new_unit_file_received",
    "home_new_unit_file_received_message",
)

/**
 * The budget's own chat, as the rows carry it (the web reads the same name off
 * live rows: `badgeUtils.js:1541`, `TabsComponents.jsx:619`). Deliberately not
 * the budget tools themselves — a budget notification that is not a chat
 * message has no alert above and must banner here.
 */
private val BUDGET_CHAT_TOOLS = setOf(
    "main_budget_tool_chat_label",
    "department_budget_tool_chat_label",
)

/** Enough ids to outlive any reconnect replay without growing forever. */
private const val RECENT_ACTIVITY_IDS = 64

private const val ACTIVITY_TAG = "ActivityAlerts"
