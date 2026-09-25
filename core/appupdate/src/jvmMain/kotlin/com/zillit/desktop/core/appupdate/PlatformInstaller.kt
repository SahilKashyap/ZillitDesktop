package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.OperatingSystem
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turns a verified installer into a running newer build.
 *
 * Two steps, because only the second may end the process:
 *
 *  1. [prepare] — with the app still open: check the file's code signature
 *     against this build's and stage whatever the swap needs. Everything that
 *     can fail for a reason worth telling the user fails here, while there is
 *     still a window to say it in.
 *  2. [launch] — start a detached helper that waits for this process to exit,
 *     installs, and reopens Zillit. The caller quits straight after.
 *
 * The helper is a script and not this JVM because the files being replaced are
 * the ones this JVM is running from.
 */
interface PlatformInstaller {

    /** True when [url] names a file this platform can install — `.dmg` or `.msi`. */
    fun accepts(url: String): Boolean

    /** @throws UpdateFailure */
    fun prepare(installer: File): PreparedUpdate

    /**
     * Starts the helper for [update], which waits for [appPid] to exit.
     *
     * @throws UpdateFailure when the helper could not be started; the app
     *   must then stay open.
     */
    fun launch(update: PreparedUpdate, appPid: Long)

    companion object {

        /**
         * The installer for this machine, or null when in-app install cannot
         * work here: Linux (a `.deb` needs root and a package manager), or a
         * run with no `jpackage.app-path` — `:desktopApp:run` has no bundle to
         * replace, and swapping one it did not come from would be worse than
         * doing nothing.
         *
         * @param appPath `jpackage.app-path`: the launcher inside the bundle.
         * @param workDir where staged files and the helper script live.
         */
        fun forCurrent(
            os: OperatingSystem,
            appPath: String?,
            workDir: File,
            commands: CommandRunner = CommandRunner.System,
        ): PlatformInstaller? {
            val launcher = appPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
            return when (os) {
                OperatingSystem.MacOs -> MacInstaller.bundleOf(launcher)?.let { MacInstaller(it, workDir, commands) }
                OperatingSystem.Windows -> WindowsInstaller(launcher, workDir, commands)
                OperatingSystem.Linux, OperatingSystem.Unknown -> null
            }
        }
    }
}

/**
 * What [PlatformInstaller.prepare] left ready for the helper.
 *
 * @param payload the thing the helper installs: the staged `.app` on macOS,
 *   the `.msi` on Windows.
 */
data class PreparedUpdate(val payload: File)

/**
 * Runs an OS tool and collects what it printed.
 *
 * An interface so the signature checks — which are parsing, mostly — are
 * tested against recorded `codesign` and PowerShell output rather than against
 * whatever certificates the test machine happens to hold.
 */
fun interface CommandRunner {

    /** Output is stdout and stderr together: `codesign -dv` writes to stderr. */
    fun run(command: List<String>): CommandResult

    companion object {
        private const val TIMEOUT_MINUTES = 5L

        val System = CommandRunner { command ->
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                CommandResult(exitCode = -1, output = output)
            } else {
                CommandResult(process.exitValue(), output)
            }
        }
    }
}

data class CommandResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}
