package com.zillit.desktop.core.datastore

/**
 * Every preference the app stores.
 *
 * Declared in one place so the full set is reviewable — the Android equivalent
 * is spread across 66 accessor pairs, which is why nobody can say what is
 * actually persisted there.
 *
 * Ported selectively: only the entries from `SharedPref.kt` that are genuinely
 * user settings appear here. Cached server data goes to `core:database`, and
 * secrets to `core:security` (see [PreferenceKey]).
 */
object ZillitPreferences {

    // -- appearance (device-scoped: survives sign-out) ---------------------

    /**
     * `Light` | `Dark` | `System`.
     *
     * Stored as a name rather than an ordinal — reordering the enum must not
     * silently change every existing user's theme.
     */
    val ThemeMode = PreferenceKey.StringKey("theme.mode", "System", PreferenceScope.Device)

    /** UI scale percentage. Android's `fontSize`, generalised for desktop. */
    val UiScalePercent = PreferenceKey.IntKey("ui.scale.percent", DEFAULT_UI_SCALE, PreferenceScope.Device)

    /**
     * The language labels are fetched in — a BCP-47 code, or **empty for
     * "follow the OS"**.
     *
     * Empty rather than `"en"` as the default so an unset preference and a
     * deliberate choice of English are distinguishable. With `"en"` they are
     * not, and a French user who never opened settings would be served English
     * labels on a French machine — which is what the Android client does.
     */
    val Language = PreferenceKey.StringKey("ui.language", "", PreferenceScope.Device)

    // -- window and workspace (device-scoped) ------------------------------

    val WindowWidth = PreferenceKey.IntKey("window.width", DEFAULT_WINDOW_WIDTH, PreferenceScope.Device)
    val WindowHeight = PreferenceKey.IntKey("window.height", DEFAULT_WINDOW_HEIGHT, PreferenceScope.Device)
    val WindowX = PreferenceKey.IntKey("window.x", UNSET_POSITION, PreferenceScope.Device)
    val WindowY = PreferenceKey.IntKey("window.y", UNSET_POSITION, PreferenceScope.Device)
    val WindowMaximized = PreferenceKey.BooleanKey("window.maximized", false, PreferenceScope.Device)

    /** The floating call card with Accept and Decline, shown while Zillit's window is not in front. */
    val CallWidget = PreferenceKey.BooleanKey("widget.calls", true, PreferenceScope.Device)

    /** The floating message card, shown while Zillit's window is not in front. */
    val MessageWidget = PreferenceKey.BooleanKey("widget.messages", true, PreferenceScope.Device)

    /** Closing the window hides it; Zillit keeps running in the tray until Quit. */
    val CloseToTray = PreferenceKey.BooleanKey("app.close_to_tray", true, PreferenceScope.Device)

    /** Start Zillit hidden when the user signs in to this computer. Mirrors the OS login item. */
    val StartAtLogin = PreferenceKey.BooleanKey("app.start_at_login", false, PreferenceScope.Device)

    /** `Tabs` | `Cascade` — the workspace layout mode (plan §3). */
    val WorkspaceLayoutMode = PreferenceKey.StringKey("workspace.layout", "Tabs", PreferenceScope.Device)

    val RestoreWorkspaceOnLaunch = PreferenceKey.BooleanKey("workspace.restore", true, PreferenceScope.Device)

    // -- the Drive widget (device-scoped: a window, not an account) --------

    /** Whether the always-on-top Drive widget was open when the app last quit. */
    val DriveWidgetOpen = PreferenceKey.BooleanKey("drive.widget.open", false, PreferenceScope.Device)
    val DriveWidgetWidth = PreferenceKey.IntKey("drive.widget.width", DEFAULT_WIDGET_WIDTH, PreferenceScope.Device)
    val DriveWidgetHeight = PreferenceKey.IntKey("drive.widget.height", DEFAULT_WIDGET_HEIGHT, PreferenceScope.Device)
    val DriveWidgetX = PreferenceKey.IntKey("drive.widget.x", UNSET_POSITION, PreferenceScope.Device)
    val DriveWidgetY = PreferenceKey.IntKey("drive.widget.y", UNSET_POSITION, PreferenceScope.Device)
    /** The production the widget last showed; falls back to the open one. */
    val DriveWidgetProject = PreferenceKey.StringKey("drive.widget.project", "", PreferenceScope.User)
    /** `Floating` (on top) or `Desktop` (on the desktop layer, like an OS widget). */
    val DriveWidgetMode = PreferenceKey.StringKey("drive.widget.mode", "Floating", PreferenceScope.Device)

    /** The Drive widget's window, as one group — see [WidgetKeys]. */
    val DriveWidget = WidgetKeys(
        open = DriveWidgetOpen,
        width = DriveWidgetWidth,
        height = DriveWidgetHeight,
        x = DriveWidgetX,
        y = DriveWidgetY,
        mode = DriveWidgetMode,
    )

    // -- the Chat & Calls and Crew List widgets ---------------------------

    /**
     * Chat opens narrower than the others: its compact layout shows one pane
     * at a time, so the width only has to fit a conversation, not a
     * conversation beside its directory.
     */
    val ChatWidget = WidgetKeys.named("chat.widget", width = DEFAULT_CHAT_WIDGET_WIDTH)

    val CrewWidget = WidgetKeys.named("crew.widget")

    /** The production each tool widget last showed; falls back to the open one. */
    val ChatWidgetProject = PreferenceKey.StringKey("chat.widget.project", "", PreferenceScope.User)
    val CrewWidgetProject = PreferenceKey.StringKey("crew.widget.project", "", PreferenceScope.User)

    // -- security (device-scoped) ------------------------------------------

    /** Android's `appLock`. */
    val AppLockEnabled = PreferenceKey.BooleanKey("security.applock", false, PreferenceScope.Device)

    /** Idle minutes before re-auth (plan §8.5). */
    val IdleLockMinutes = PreferenceKey.IntKey("security.idle.minutes", DEFAULT_IDLE_MINUTES, PreferenceScope.Device)

    // -- notifications (user-scoped: cleared on sign-out) ------------------

    /** Android's `isMuteNotification`. */
    val MuteNotifications = PreferenceKey.BooleanKey("notify.mute", false, PreferenceScope.User)

    val NotificationSound = PreferenceKey.BooleanKey("notify.sound", true, PreferenceScope.User)

    /**
     * Desktop alerts per source. Separate keys rather than one switch: someone
     * who wants to be told about a direct message rarely wants the same
     * interruption for every mail, and one flag cannot express that.
     *
     * Phrased positively, unlike the older [MuteNotifications] — that one is
     * kept as-is because the reminder scheduler and its tests already read it.
     */
    val NotifyMessages = PreferenceKey.BooleanKey("notify.messages", true, PreferenceScope.User)
    val NotifyMail = PreferenceKey.BooleanKey("notify.mail", true, PreferenceScope.User)

    /** Posts on the Home notice boards. */
    val NotifyUpdates = PreferenceKey.BooleanKey("notify.updates", true, PreferenceScope.User)

    /**
     * A ringing call.
     *
     * Its own switch, and the only source [MuteNotifications] does not cover:
     * a ring lasts a minute and somebody is waiting through it, so silencing
     * it has to be a decision about calls rather than a side effect of muting
     * everything this morning.
     */
    val NotifyCalls = PreferenceKey.BooleanKey("notify.calls", true, PreferenceScope.User)

    /**
     * The ringtone itself — the sound that plays while a call rings this
     * device, on every line. Separate from [NotifyCalls], which is the banner:
     * a desk that wants the card but not the noise is a real desk.
     */
    val RingOnIncomingCall = PreferenceKey.BooleanKey("notify.ringtone", true, PreferenceScope.User)

    /**
     * Everything else the production did — the phones' bell list, as banners:
     * a purchase order approved, a document shared, an SOS raised. Chat, mail,
     * notices and calls stay on their own switches above; this one covers the
     * sections none of them owns.
     */
    val NotifyActivity = PreferenceKey.BooleanKey("notify.activity", true, PreferenceScope.User)

    /**
     * The call devices this machine last chose. Device-scoped, not user: the
     * headset belongs to the computer, and signing in as somebody else does
     * not change which socket it is plugged into. Empty means the OS default.
     */
    val CallMicrophoneId = PreferenceKey.StringKey("call.device.microphone", "", PreferenceScope.Device)
    val CallSpeakerId = PreferenceKey.StringKey("call.device.speaker", "", PreferenceScope.Device)

    /**
     * Calendar occurrences already reminded about, so restarting the app inside
     * a reminder's grace window does not deliver it twice. Pruned on write.
     */
    val CalendarRemindersFired =
        PreferenceKey.StringKey("notify.calendar.fired", "", PreferenceScope.User)

    /** Android's `ignoreBanner`. */
    val IgnoreUpdateBanner = PreferenceKey.BooleanKey("notify.ignoreUpdateBanner", false, PreferenceScope.User)

    // -- update notices (device-scoped) ------------------------------------

    /**
     * A random id identifying this *install* to Firebase Remote Config, empty
     * until first generated.
     *
     * Device-scoped, so it survives sign-out: Firebase buckets percentage
     * rollouts by this value, and an id that changed whenever somebody logged
     * out would make one machine look like a stream of new installs and skew
     * every staged rollout the console runs. It identifies a copy of the app,
     * never a person — which is also why it belongs here and not in
     * `core:security` beside the real device id.
     *
     * Written once by `AppGraph`; read by `AppUpdateChecker`'s instance-id
     * provider.
     */
    val UpdateInstanceId = PreferenceKey.StringKey("update.instanceId", "", PreferenceScope.Device)

    // -- last-used context (user-scoped) -----------------------------------

    val LastProjectId = PreferenceKey.StringKey("session.lastProjectId", "", PreferenceScope.User)
    val LastUnitId = PreferenceKey.StringKey("session.lastUnitId", "", PreferenceScope.User)

    // -- per-project view settings -----------------------------------------
    //
    // Project-scoped because a user's chosen view for one production has no
    // bearing on another. On Android these are single global keys, so switching
    // project silently carries the previous production's layout over.

    /** Android's `boxScheduleDefaultView`. */
    val BoxScheduleView = PreferenceKey.StringKey("boxschedule.view", "grid", PreferenceScope.Project)

    /** Android's `boxScheduleCalendarMode`. */
    val BoxScheduleCalendarMode = PreferenceKey.StringKey("boxschedule.calendarMode", "month", PreferenceScope.Project)

    /** Android's `boxScheduleListMode`. */
    val BoxScheduleListMode = PreferenceKey.BooleanKey("boxschedule.listMode", false, PreferenceScope.Project)

    /** Android's `isEmailTrailingEnabled`. */
    val EmailTrailingEnabled = PreferenceKey.BooleanKey("email.trailing", true, PreferenceScope.Project)

    val ToolGroupOrder = PreferenceKey.StringKey("tools.groupOrder", "", PreferenceScope.Project)

    /**
     * The mention picker's recency memory: crew names most recently completed,
     * newest first, newline-joined. Project-scoped — each production has its
     * own crew, and one set's regulars mean nothing on another.
     */
    val RecentMentions = PreferenceKey.StringKey("home.recentMentions", "", PreferenceScope.Project)

    /** Starred chat conversations, newline-joined ids, per production. */
    val ChatFavourites = PreferenceKey.StringKey("chat.favourites", "", PreferenceScope.Project)

    /**
     * The place the Weather tool last showed — name, latitude, longitude on
     * three lines, per production. A unit shoots in the same few places for
     * weeks, so re-picking on every open would be a chore.
     */
    val WeatherPlace = PreferenceKey.StringKey("weather.place", "", PreferenceScope.Project)

    /** Every declared key — used by tests to catch collisions. */
    val all: List<PreferenceKey<*>> = listOf(
        ThemeMode, UiScalePercent, Language,
        WindowWidth, WindowHeight, WindowX, WindowY, WindowMaximized,
        WorkspaceLayoutMode, RestoreWorkspaceOnLaunch,
        AppLockEnabled, IdleLockMinutes,
        MuteNotifications, NotificationSound, IgnoreUpdateBanner, CalendarRemindersFired, UpdateInstanceId,
        NotifyMessages, NotifyMail, NotifyUpdates, NotifyCalls, NotifyActivity, RingOnIncomingCall,
        WeatherPlace,
        CallMicrophoneId, CallSpeakerId,
        LastProjectId, LastUnitId,
        BoxScheduleView, BoxScheduleCalendarMode, BoxScheduleListMode,
        EmailTrailingEnabled, ToolGroupOrder, RecentMentions, ChatFavourites,
        CallWidget, MessageWidget, CloseToTray, StartAtLogin,
        DriveWidgetProject, ChatWidgetProject, CrewWidgetProject,
    ) + DriveWidget.all + ChatWidget.all + CrewWidget.all

    const val UNSET_POSITION = -1
    private const val DEFAULT_UI_SCALE = 100
    private const val DEFAULT_WINDOW_WIDTH = 1440
    private const val DEFAULT_WINDOW_HEIGHT = 900
    const val DEFAULT_WIDGET_WIDTH = 520
    const val DEFAULT_WIDGET_HEIGHT = 680
    private const val DEFAULT_CHAT_WIDGET_WIDTH = 420
    private const val DEFAULT_IDLE_MINUTES = 15
}

/**
 * One widget window's remembered state: whether it was open when the app last
 * quit, where it sat, how big it was, and whether it floats on top or lies on
 * the desktop layer.
 *
 * Grouped rather than loose because every widget needs exactly these six and
 * the shell that draws them is written once — passing the group is what lets
 * `WidgetShell` serve Drive, Chat and the Crew List without knowing which it
 * is drawing.
 */
class WidgetKeys(
    val open: PreferenceKey.BooleanKey,
    val width: PreferenceKey.IntKey,
    val height: PreferenceKey.IntKey,
    val x: PreferenceKey.IntKey,
    val y: PreferenceKey.IntKey,
    val mode: PreferenceKey.StringKey,
) {

    /** Every key in the group, for the collision check in [ZillitPreferences.all]. */
    val all: List<PreferenceKey<*>> = listOf(open, width, height, x, y, mode)

    companion object {

        /** The six keys under one dotted [prefix] — `chat.widget` gives `chat.widget.open` and the rest. */
        fun named(
            prefix: String,
            width: Int = ZillitPreferences.DEFAULT_WIDGET_WIDTH,
            height: Int = ZillitPreferences.DEFAULT_WIDGET_HEIGHT,
        ): WidgetKeys = WidgetKeys(
            open = PreferenceKey.BooleanKey("$prefix.open", false, PreferenceScope.Device),
            width = PreferenceKey.IntKey("$prefix.width", width, PreferenceScope.Device),
            height = PreferenceKey.IntKey("$prefix.height", height, PreferenceScope.Device),
            x = PreferenceKey.IntKey("$prefix.x", ZillitPreferences.UNSET_POSITION, PreferenceScope.Device),
            y = PreferenceKey.IntKey("$prefix.y", ZillitPreferences.UNSET_POSITION, PreferenceScope.Device),
            mode = PreferenceKey.StringKey("$prefix.mode", "Floating", PreferenceScope.Device),
        )
    }
}
