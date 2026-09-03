package com.zillit.desktop

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Start Zillit when you sign in": the OS-side half of the preference.
 *
 * macOS gets a LaunchAgent in `~/Library/LaunchAgents` — `RunAtLoad` starts
 * the packaged launcher with [BackgroundLaunch.FLAG] at the next sign-in, and
 * System Settings lists it under Login Items so the user can see and remove
 * it there too. Windows gets the per-user `Run` registry value. Both point at
 * the launcher jpackage records in `jpackage.app-path`; under Gradle there is
 * none, so [available] is false and the switch says why.
 *
 * The agent is written, never `launchctl bootstrap`ped: bootstrapping a
 * `RunAtLoad` agent starts it *now*, which would launch a second Zillit into
 * the single-instance lock every time the switch is turned on.
 */
object LoginItem {
    const val LABEL = "com.zillit.desktop"

    /** The packaged launcher, or null under Gradle where there is nothing the OS could start. */
    fun launcher(): File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.canExecute() }

    val available: Boolean
        get() = launcher() != null && currentPlatform().os in setOf(OperatingSystem.MacOs, OperatingSystem.Windows)

    fun isInstalled(): Boolean = when (currentPlatform().os) {
        OperatingSystem.MacOs -> plistFile().exists()
        OperatingSystem.Windows -> run("reg", "query", RUN_KEY, "/v", WINDOWS_VALUE) == 0
        else -> false
    }

    fun apply(enabled: Boolean): Result<Unit> = if (enabled) install() else remove()

    /**
     * Applies [enabled] to the OS and records what is true afterwards, so the
     * switch never shows a login item the OS refused.
     */
    suspend fun sync(preferences: PreferenceStore, enabled: Boolean) {
        val outcome = withContext(Dispatchers.IO) { apply(enabled) }
        outcome.onFailure { ZillitLog.w(TAG) { "start at login ($enabled) refused: $it" } }
        preferences.set(ZillitPreferences.StartAtLogin, if (outcome.isSuccess) enabled else isInstalled())
    }

    /** At start-up: the switch reflects the OS, which the user may have changed in System Settings. */
    suspend fun reconcile(preferences: PreferenceStore) {
        if (!available) return
        val installed = withContext(Dispatchers.IO) { runCatching { isInstalled() }.getOrDefault(false) }
        preferences.set(ZillitPreferences.StartAtLogin, installed)
    }

    fun install(): Result<Unit> = runCatching {
        val launcher = launcher() ?: error("Start at login needs the installed Zillit app.")
        when (currentPlatform().os) {
            OperatingSystem.MacOs -> {
                val file = plistFile()
                file.parentFile.mkdirs()
                file.writeText(plist(launcher.absolutePath))
            }
            OperatingSystem.Windows -> {
                val code = run(
                    "reg", "add", RUN_KEY, "/v", WINDOWS_VALUE, "/t", "REG_SZ",
                    "/d", windowsCommand(launcher.absolutePath), "/f",
                )
                check(code == 0) { "Windows refused the startup entry (exit $code)." }
            }
            else -> error("Start at login is not available on this platform.")
        }
    }

    fun remove(): Result<Unit> = runCatching {
        when (currentPlatform().os) {
            OperatingSystem.MacOs -> {
                // Unloads the agent if this session loaded it at sign-in; harmless otherwise.
                run("launchctl", "bootout", "gui/${uid()}/$LABEL")
                plistFile().delete()
                Unit
            }
            OperatingSystem.Windows -> {
                run("reg", "delete", RUN_KEY, "/v", WINDOWS_VALUE, "/f")
                Unit
            }
            else -> Unit
        }
    }

    /** The LaunchAgent, as launchd reads it. Pure, so a test can hold it to the light. */
    internal fun plist(launcher: String, label: String = LABEL): String = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |    <key>Label</key>
        |    <string>$label</string>
        |    <key>ProgramArguments</key>
        |    <array>
        |        <string>${xml(launcher)}</string>
        |        <string>${BackgroundLaunch.FLAG}</string>
        |    </array>
        |    <key>RunAtLoad</key>
        |    <true/>
        |    <key>KeepAlive</key>
        |    <false/>
        |    <key>ProcessType</key>
        |    <string>Interactive</string>
        |    <key>LimitLoadToSessionType</key>
        |    <string>Aqua</string>
        |</dict>
        |</plist>
        |""".trimMargin()

    /** The Run value: the launcher quoted against spaces in `Program Files`, then the flag. */
    internal fun windowsCommand(launcher: String): String = "\"$launcher\" ${BackgroundLaunch.FLAG}"

    private fun xml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun plistFile(): File =
        File(System.getProperty("user.home"), "Library/LaunchAgents/$LABEL.plist")

    private fun uid(): String = runCatching {
        val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        output
    }.getOrDefault("")

    /** Exit code, or -1 when the command could not run or did not finish. */
    private fun run(vararg command: String): Int = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching -1
        }
        process.exitValue()
    }.onFailure { ZillitLog.d(TAG) { "${command.first()} failed: $it" } }.getOrDefault(-1)

    private const val WINDOWS_VALUE = "Zillit"
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val TAG = "LoginItem"
}
