package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.ZillitLog
import java.io.File

/**
 * Installs an `.msi` over the running build.
 *
 * ## What is checked
 *
 * `Get-AuthenticodeSignature` must call the package `Valid`, and its signer's
 * subject must be the one on this build's own launcher. The subject, not the
 * thumbprint: a code-signing certificate is renewed every year or so, and a
 * renewal must not strand every install on the previous release. An unsigned
 * running build accepts any valid signature; the digest still has to match.
 *
 * ## The install
 *
 * The package is per-machine (`perUserInstall = false` in the Gradle config),
 * so `msiexec` runs elevated through `Start-Process -Verb RunAs` and Windows
 * shows its usual UAC prompt. `/passive` shows progress with no questions;
 * the stable `upgradeUuid` is what makes it replace the old build rather than
 * sit beside it. `INSTALLDIR` pins it to the folder this build runs from, in
 * case someone chose a non-default one. Declined or failed, the helper
 * reopens whatever is installed.
 */
internal class WindowsInstaller(
    private val launcher: File,
    private val workDir: File,
    private val commands: CommandRunner,
) : PlatformInstaller {

    override fun accepts(url: String): Boolean = url.substringBefore('?').lowercase().endsWith(".msi")

    override fun prepare(installer: File): PreparedUpdate {
        val incoming = signature(installer)
        if (incoming?.status != VALID) {
            val status = incoming?.status ?: "unreadable"
            throw UpdateFailure(UpdateFailure.Reason.Signature, "package signature is $status")
        }
        val ours = signature(launcher)?.takeIf { it.status == VALID }
        if (ours != null && ours.subject != incoming.subject) {
            throw UpdateFailure(UpdateFailure.Reason.Signature, "package signed by a different publisher")
        }
        if (ours == null) ZillitLog.d(TAG) { "this build is unsigned; accepting the package on the digest" }
        return PreparedUpdate(installer)
    }

    private fun signature(file: File): Signature? {
        val result = commands.run(powershell("-Command", signatureCommand(file.path)))
        return if (result.ok) parseSignature(result.output) else null
    }

    override fun launch(update: PreparedUpdate, appPid: Long) {
        workDir.mkdirs()
        val script = File(workDir, "install-update.ps1").apply { writeText(SCRIPT) }
        val log = File(workDir, "install-update.log")
        try {
            ProcessBuilder(
                powershell(
                    "-WindowStyle", "Hidden", "-File", script.path,
                    "-AppPid", appPid.toString(),
                    "-Msi", update.payload.path,
                    "-Exe", launcher.path,
                    "-InstallDir", launcher.parentFile.path,
                    "-Log", log.path,
                ),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            throw UpdateFailure(UpdateFailure.Reason.Install, "could not start the update helper", failure)
        }
    }

    internal data class Signature(val status: String, val subject: String)

    internal companion object {
        private const val TAG = "AppUpdate"
        private const val VALID = "Valid"

        private fun powershell(vararg args: String) =
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass") + args

        /** Prints `Status|Subject`. The path is single-quoted, so only `'` needs escaping. */
        fun signatureCommand(path: String): String {
            val quoted = "'" + path.replace("'", "''") + "'"
            return "\$s = Get-AuthenticodeSignature -LiteralPath $quoted; " +
                "Write-Output (\"\" + \$s.Status + '|' + \$s.SignerCertificate.Subject)"
        }

        fun parseSignature(output: String): Signature? {
            val line = output.lineSequence().map { it.trim() }.lastOrNull { it.contains('|') } ?: return null
            return Signature(status = line.substringBefore('|').trim(), subject = line.substringAfter('|').trim())
        }

        /**
         * The helper. Waits up to two minutes for Zillit to exit, installs
         * elevated, then reopens the launcher. Exit 1602 is the user declining
         * UAC; it is logged like any other failure and the old build reopens.
         */
        val SCRIPT = """
            |# Written by Zillit's in-app updater. Installs the new build once Zillit has quit, then reopens it.
            |param([int]${'$'}AppPid, [string]${'$'}Msi, [string]${'$'}Exe, [string]${'$'}InstallDir, [string]${'$'}Log)
            |
            |try { Wait-Process -Id ${'$'}AppPid -Timeout 120 -ErrorAction SilentlyContinue } catch { }
            |
            |${'$'}msiArgs = @('/i', '"' + ${'$'}Msi + '"', '/passive', '/norestart',
            |    'INSTALLDIR="' + ${'$'}InstallDir.TrimEnd('\') + '"', '/l*v', '"' + ${'$'}Log + '"')
            |try {
            |    ${'$'}p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${'$'}msiArgs -Verb RunAs -Wait -PassThru
            |    ${'$'}code = ${'$'}p.ExitCode
            |} catch {
            |    ${'$'}code = -1
            |}
            |Add-Content -LiteralPath (${'$'}Log + '.helper') -Value ((Get-Date).ToString('s') + " msiexec exit ${'$'}code")
            |
            |if (${'$'}code -eq 0) { Remove-Item -LiteralPath ${'$'}Msi -ErrorAction SilentlyContinue }
            |Start-Process -FilePath ${'$'}Exe
            |
        """.trimMargin()
    }
}
