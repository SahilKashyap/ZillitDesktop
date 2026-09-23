package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Whether the operating system lets Zillit show notifications. */
enum class NotificationPermission {
    Granted,
    Denied,

    /** Never asked — the system's own prompt is still available. */
    NotDetermined,

    /** This platform, or this build, cannot say (a dev run, Windows, Linux). */
    Unknown,
}

/**
 * The startup check the phones make (Android's `POST_NOTIFICATIONS` request,
 * the web's `Notification.requestPermission`): is Zillit allowed to notify?
 *
 * macOS answers only to the process with the bundle identity, so the question
 * goes through `zillit-notify --status`, and an undecided state is settled by
 * `--request`, which shows the system's own prompt. Where the answer cannot be
 * known the check stays quiet: a dialog that sends someone to a settings pane
 * that already says "allowed" is worse than none.
 */
object DesktopNotifications {

    private var checkedThisLaunch = false

    /**
     * Whether to ask the person to allow notifications — once per launch, and
     * only when the system has actually refused.
     */
    suspend fun needsPromptAtStartup(): Boolean {
        if (checkedThisLaunch) return false
        checkedThisLaunch = true
        val decision = startupDecision(status()) { request() }
        ZillitLog.i(TAG) { "startup permission check: prompt=${decision}" }
        return decision
    }

    suspend fun status(): NotificationPermission =
        helperAnswer("--status")?.let(::permissionFrom) ?: NotificationPermission.Unknown

    /** Asks the system; on macOS this is the native prompt when undecided. */
    suspend fun request(): NotificationPermission =
        helperAnswer("--request")?.let(::permissionFrom) ?: NotificationPermission.Unknown

    /**
     * Opens the operating system's notification settings for Zillit.
     *
     * macOS 13 and later deep-link to the app's own row
     * (`Notifications-Settings.extension?id=`); older releases open the
     * Notifications pane. Windows opens its notifications page. Returns
     * whether anything was opened.
     */
    fun openSettings(): Boolean = runCatching {
        val platform = currentPlatform().os
        val command = when (platform) {
            OperatingSystem.MacOs -> listOf("open", macSettingsUrl(System.getProperty("os.version").orEmpty()))
            OperatingSystem.Windows -> listOf("cmd", "/c", "start", "", "ms-settings:notifications")
            else -> return false
        }
        ProcessBuilder(command).redirectErrorStream(true).start()
        true
    }.getOrElse { error ->
        ZillitLog.w(TAG) { "could not open notification settings: $error" }
        false
    }

    private suspend fun helperAnswer(mode: String): String? {
        val helper = TrayNotifier.macNotifyHelper ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder(helper.absolutePath, mode).redirectErrorStream(false).start()
                val answer = process.inputStream.bufferedReader().readText().trim()
                process.errorStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
                    .forEach { line -> ZillitLog.d(TAG) { line } }
                process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                answer.ifBlank { null }
            }.getOrElse { error ->
                ZillitLog.w(TAG) { "notification helper $mode failed: $error" }
                null
            }
        }
    }

    private const val TAG = "Notifications"
    private const val HELPER_TIMEOUT_SECONDS = 130L

    /** The app's bundle identifier — what the notification daemon files the permission under. */
    const val BUNDLE_ID = "com.zillit.desktop"

    /** For tests: the helper file the answers come from. */
    internal val helperPresent: Boolean get() = TrayNotifier.macNotifyHelper?.let(File::canExecute) == true
}

/** The helper's one-word answer, as a permission. */
internal fun permissionFrom(answer: String): NotificationPermission = when (answer.trim()) {
    "authorized", "provisional", "ephemeral", "granted" -> NotificationPermission.Granted
    "denied" -> NotificationPermission.Denied
    "notDetermined" -> NotificationPermission.NotDetermined
    else -> NotificationPermission.Unknown
}

/**
 * Whether the startup check ends in the in-app dialog.
 *
 * An undecided state gets the system's own prompt first ([request]); only a
 * refusal — "Don't Allow" then, or a denied state from before — sends the
 * person to settings. Unknown stays quiet, and so does a request the system
 * could not process (the helper answers `error`): that is not a refusal, and
 * a dialog for it came back on every launch.
 */
internal inline fun startupDecision(
    status: NotificationPermission,
    request: () -> NotificationPermission,
): Boolean = when (status) {
    NotificationPermission.Denied -> true
    NotificationPermission.NotDetermined -> request() == NotificationPermission.Denied
    NotificationPermission.Granted, NotificationPermission.Unknown -> false
}

/** The Notifications pane URL for this macOS release, keyed to the app's row where the release supports it. */
internal fun macSettingsUrl(osVersion: String, bundleId: String = DesktopNotifications.BUNDLE_ID): String {
    val major = osVersion.substringBefore('.').toIntOrNull() ?: 0
    return if (major >= VENTURA) {
        "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=$bundleId"
    } else {
        "x-apple.systempreferences:com.apple.preference.notifications?id=$bundleId"
    }
}

private const val VENTURA = 13

/**
 * The startup check and its dialog, self-contained: the phones ask for
 * notification permission at startup; here the OS is asked whether Zillit
 * may notify, and a refusal opens the dialog.
 */
@Composable
fun NotificationPermissionPrompt() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (DesktopNotifications.needsPromptAtStartup()) visible = true
    }
    NotificationPermissionDialog(
        visible = visible,
        onOpenSettings = {
            DesktopNotifications.openSettings()
            visible = false
        },
        onDismiss = { visible = false },
    )
}

/**
 * The dialog: what is off, what it costs, and the one button that takes the
 * person to the switch. Dismissable — it comes back at the next launch while
 * notifications stay off, the way the phones keep asking.
 */
@Composable
fun NotificationPermissionDialog(visible: Boolean, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_allow_notifications),
        subtitle = str(S.desktop_notifications_blocked),
        icon = ZillitIcons.Bell,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(text = str(S.desktop_not_now), variant = ButtonVariant.Secondary, onClick = onDismiss)
            ZillitButton(text = str(S.desktop_open_notification_settings), onClick = onOpenSettings)
        },
    ) {
        ZillitText(
            text = str(S.desktop_notifications_blocked_body),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}
