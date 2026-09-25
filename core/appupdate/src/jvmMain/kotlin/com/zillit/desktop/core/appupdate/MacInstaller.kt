package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.ZillitLog
import java.io.File
import java.nio.file.Files

/**
 * Installs a `.dmg` over the running `.app`.
 *
 * ## What is checked before anything is replaced
 *
 *  - the image holds exactly the app it should: a bundle whose
 *    `CFBundleIdentifier` is this one's;
 *  - `codesign --verify --deep --strict` passes on it, so no file inside was
 *    altered after signing;
 *  - its Team ID is this build's Team ID. A build signed by anyone else —
 *    even validly — is refused. An unsigned running build (a local
 *    `createDistributable`) has no Team ID to match, and then any valid
 *    signature is accepted; the digest from Remote Config still has to match.
 *
 * Notarization is not re-checked: a file this process downloaded carries no
 * quarantine flag, so Gatekeeper would not assess it either, and the signature
 * and digest are the two facts that matter.
 *
 * ## The swap
 *
 * The new bundle is copied out of the image into [workDir] while the app is
 * open, and the image is detached; the helper then only has to move folders.
 * It keeps the old bundle until the copy lands and puts it back if the copy
 * fails, so an interrupted update leaves the old Zillit, never no Zillit.
 *
 * `/Applications` is writable for an admin but not for a standard account; the
 * helper then asks for an administrator's password through `osascript`, which
 * is the same prompt a drag-install shows. Cancelled, the old build reopens.
 */
internal class MacInstaller(
    private val bundle: File,
    private val workDir: File,
    private val commands: CommandRunner,
) : PlatformInstaller {

    override fun accepts(url: String): Boolean = url.substringBefore('?').lowercase().endsWith(".dmg")

    override fun prepare(installer: File): PreparedUpdate {
        val mount = Files.createTempDirectory("zillit-update-mount").toFile()
        val attach = commands.run(
            listOf(
                "hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen",
                "-mountpoint", mount.path, installer.path,
            ),
        )
        if (!attach.ok) {
            mount.delete()
            throw UpdateFailure(UpdateFailure.Reason.Install, "hdiutil attach failed (${attach.exitCode})")
        }
        try {
            val candidate = mount.listFiles()?.singleOrNull { it.isDirectory && it.name.endsWith(".app") }
                ?: throw UpdateFailure(UpdateFailure.Reason.Install, "disk image holds no single .app")
            verify(candidate)
            return PreparedUpdate(stage(candidate))
        } finally {
            commands.run(listOf("hdiutil", "detach", "-force", mount.path))
            mount.delete()
        }
    }

    private fun verify(candidate: File) {
        val expectedId = bundleId(bundle)
        val actualId = bundleId(candidate)
        if (expectedId != null && actualId != expectedId) refuse("bundle id $actualId is not $expectedId")
        if (!commands.run(listOf("codesign", "--verify", "--deep", "--strict", candidate.path)).ok) {
            refuse("codesign could not verify the new build")
        }
        val newTeam = teamId(commands.run(listOf("codesign", "-dv", "--verbose=2", candidate.path)).output)
            ?: refuse("the new build carries no Team ID")
        val ourTeam = teamId(commands.run(listOf("codesign", "-dv", "--verbose=2", bundle.path)).output)
        if (ourTeam != null && ourTeam != newTeam) refuse("signed by team $newTeam, this build by $ourTeam")
        if (ourTeam == null) ZillitLog.d(TAG) { "this build is unsigned; accepting team $newTeam on the digest" }
    }

    private fun refuse(message: String): Nothing = throw UpdateFailure(UpdateFailure.Reason.Signature, message)

    private fun bundleId(app: File): String? {
        val plist = File(app, "Contents/Info.plist").path
        val read = commands.run(listOf("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier", plist))
        return read.output.trim().takeIf { read.ok && it.isNotEmpty() }
    }

    /** `ditto` rather than a JVM copy: it keeps the symlinks, modes and extended attributes a signature covers. */
    private fun stage(candidate: File): File {
        val stagedDir = File(workDir, "staged").apply { deleteRecursively(); mkdirs() }
        val staged = File(stagedDir, bundle.name)
        if (!commands.run(listOf("ditto", candidate.path, staged.path)).ok) {
            stagedDir.deleteRecursively()
            throw UpdateFailure(UpdateFailure.Reason.Install, "could not copy the new build out of the image")
        }
        return staged
    }

    override fun launch(update: PreparedUpdate, appPid: Long) {
        workDir.mkdirs()
        val script = File(workDir, "install-update.sh").apply {
            writeText(SCRIPT)
            setExecutable(true, true)
        }
        val log = File(workDir, "install-update.log")
        try {
            // nohup: the helper outlives this JVM by design, and must not take
            // a hangup from whatever launched Zillit with it.
            ProcessBuilder(
                "/usr/bin/nohup", "/bin/sh", script.path,
                appPid.toString(), update.payload.path, bundle.path,
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            throw UpdateFailure(UpdateFailure.Reason.Install, "could not start the update helper", failure)
        }
    }

    internal companion object {
        private const val TAG = "AppUpdate"

        /**
         * `/Applications/Zillit-Desktop.app` from the launcher
         * `/Applications/Zillit-Desktop.app/Contents/MacOS/Zillit-Desktop`.
         * Null when the launcher is not inside a bundle.
         */
        fun bundleOf(launcher: File): File? {
            var current: File? = launcher
            while (current != null) {
                if (current.name.endsWith(".app")) return current
                current = current.parentFile
            }
            return null
        }

        /** `TeamIdentifier=ABCDE12345` from `codesign -dv`; null for "not set" or an unsigned bundle. */
        fun teamId(codesignOutput: String): String? = codesignOutput.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("TeamIdentifier=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "not set" }

        /**
         * The helper: `install-update.sh <pid> <staged.app> <target.app>`.
         *
         * Waits up to two minutes for Zillit to exit, swaps (elevating through
         * `osascript` when the folder is not writable), clears Chromium's
         * profile lock if it still names the dead process — a stale
         * `SingletonLock` parks the next launch at startup — and reopens
         * whichever build is now in place.
         */
        val SCRIPT = """
            |#!/bin/sh
            |# Written by Zillit's in-app updater. Replaces the app once Zillit has quit, then reopens it.
            |APP_PID="${'$'}1"; STAGED="${'$'}2"; TARGET="${'$'}3"
            |
            |swap() {
            |  rm -rf "${'$'}TARGET.old" || return 1
            |  mv "${'$'}TARGET" "${'$'}TARGET.old" || return 1
            |  if ditto "${'$'}STAGED" "${'$'}TARGET"; then
            |    rm -rf "${'$'}TARGET.old"
            |  else
            |    rm -rf "${'$'}TARGET"; mv "${'$'}TARGET.old" "${'$'}TARGET"; return 1
            |  fi
            |}
            |
            |if [ "${'$'}4" = "--swap" ]; then swap; exit ${'$'}?; fi
            |
            |echo "${'$'}(date): waiting for Zillit (${'$'}APP_PID) to quit"
            |i=0
            |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
            |  i=${'$'}((i + 1))
            |  if [ "${'$'}i" -gt 600 ]; then echo "Zillit did not quit; update abandoned"; exit 1; fi
            |  sleep 0.2
            |done
            |
            |if [ -w "${'$'}(dirname "${'$'}TARGET")" ] && [ -w "${'$'}TARGET" ]; then
            |  swap && echo "installed" || echo "swap failed; the previous build is still in place"
            |else
            |  /usr/bin/osascript \
            |    -e 'on run argv' \
            |    -e 'do shell script "/bin/sh " & quoted form of item 1 of argv & " " & quoted form of item 2 of argv & " " & quoted form of item 3 of argv & " " & quoted form of item 4 of argv & " --swap" with administrator privileges with prompt "Zillit wants to install an update."' \
            |    -e 'end run' "${'$'}0" "${'$'}APP_PID" "${'$'}STAGED" "${'$'}TARGET" \
            |    && echo "installed (as administrator)" || echo "not installed; the previous build is still in place"
            |fi
            |
            |LOCK_DIR="${'$'}HOME/Library/Application Support/CEF/User Data"
            |case "${'$'}(readlink "${'$'}LOCK_DIR/SingletonLock" 2>/dev/null)" in
            |  *-"${'$'}APP_PID") rm -f "${'$'}LOCK_DIR/SingletonLock" "${'$'}LOCK_DIR/SingletonCookie" "${'$'}LOCK_DIR/SingletonSocket" ;;
            |esac
            |
            |rm -rf "${'$'}(dirname "${'$'}STAGED")"
            |open "${'$'}TARGET"
            |
        """.trimMargin()
    }
}
