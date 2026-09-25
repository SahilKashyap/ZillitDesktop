package com.zillit.desktop

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.feature.auth.domain.DeviceReport
import java.util.concurrent.TimeUnit

/**
 * This machine, as the server is told about it — the desktop's answer to the
 * phones' `Build.MANUFACTURER`/`Build.MODEL`/`Build.VERSION` (Android
 * `AppHelper.getDeviceInfo`, `BaseViewModel.updateDevice`) and `UIDevice`
 * (iOS `FCMTokenManager`).
 *
 * One place, because four things describe the device — the `deviceInfo`
 * header, the `User-Agent`, the QR link and the `PUT device` record — and they
 * used to say four different things: "Mac OS X", "sahilkashyap's MacOs", the
 * JVM's version as the OS's, and whatever the HTTP library announces itself
 * as, which server-side agent parsers file under Android.
 *
 * Read once, lazily: the name and model come from two short OS commands, and
 * none of it changes while the app runs.
 */
internal object DesktopDevice {

    /** What this client is, beside the phones' `Android` and `IOS`. */
    const val TYPE = "desktop"

    private val os = currentPlatform().os

    /** `macOS 26.5.1`, `Windows 11 10.0`, `Linux 6.8.0` — the OS and its release, no JVM. */
    val osVersion: String by lazy {
        val release = System.getProperty("os.version").orEmpty().trim()
        listOf(osName, release).filter(String::isNotBlank).joinToString(" ")
    }

    /**
     * The hardware model — `Mac15,3`, `MacBookPro18,3` — or blank where the
     * OS will not say cheaply. The phones' `Build.MODEL`.
     */
    val model: String by lazy {
        if (os == OperatingSystem.MacOs) command("sysctl", "-n", "hw.model") else ""
    }

    /**
     * What the machine calls itself — `Sahil's MacBook Pro` — which is what
     * the user will recognise in a device list, as an iPhone reports its own
     * name. A plain description where there is none.
     */
    val name: String by lazy {
        val own = when (os) {
            OperatingSystem.MacOs -> command("scutil", "--get", "ComputerName")
            OperatingSystem.Windows -> System.getenv("COMPUTERNAME").orEmpty()
            else -> System.getenv("HOSTNAME").orEmpty()
        }.trim()
        own.ifBlank {
            when (os) {
                OperatingSystem.MacOs -> listOf("Apple", model.ifBlank { "Mac" }).joinToString(" ")
                OperatingSystem.Windows -> "Windows PC"
                OperatingSystem.Linux -> "Linux PC"
                OperatingSystem.Unknown -> "Zillit Desktop"
            }
        }
    }

    /**
     * `Zillit-Desktop/1.0.6 (macOS 26.5.1; aarch64; Mac15,3)`.
     *
     * Sent on every API call so the library's own agent is never what the
     * server sees: `okhttp/…` and `ktor-client` are what an Android app sends.
     */
    val userAgent: String by lazy {
        val arch = System.getProperty("os.arch").orEmpty()
        val detail = listOf(osVersion, arch, model).filter(String::isNotBlank).joinToString("; ")
        "Zillit-Desktop/${installedAppVersion()} ($detail)"
    }

    /** The server's device record for this Mac or PC (`PUT device`), as the phones send theirs every launch. */
    fun report(): DeviceReport = DeviceReport(
        name = name,
        type = TYPE,
        osVersion = osVersion,
        appVersion = installedAppVersion(),
    )

    private val osName: String
        get() = when (os) {
            OperatingSystem.MacOs -> "macOS"
            // `Windows 11` already names itself; the release is the NT version.
            else -> System.getProperty("os.name").orEmpty().ifBlank { "Desktop" }
        }

    /** One line from a short OS command, or blank if it fails or takes too long. */
    private fun command(vararg args: String): String = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ""
        }
        if (process.exitValue() != 0) return ""
        process.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull().orEmpty()
    }.getOrElse { thrown ->
        ZillitLog.w("DesktopDevice") { "${args.first()} failed: ${thrown.message}" }
        ""
    }

    private const val COMMAND_TIMEOUT_SECONDS = 2L
}
