package com.zillit.desktop.core.appupdate

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-app half: the file is the one Remote Config named, it is signed by
 * whoever signed this build, and nothing is handed to an installer otherwise.
 */
class InAppUpdateTest {

    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val root: File = Files.createTempDirectory("zillit-update-test").toFile()
    private var requests = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/Zillit-Desktop-2.0.0.dmg") { exchange ->
            requests++
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        createContext("/missing.dmg") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    // --- Remote Config -------------------------------------------------------

    @Test
    fun `installer needs both an https url and a 64-hex digest`() {
        val sha = "A".repeat(64)
        val expected = InstallerRef("https://cdn.example/z.dmg", "a".repeat(64))
        assertEquals(expected, installerRef("https://cdn.example/z.dmg", sha))
        assertEquals(expected, installerRef("\"https://cdn.example/z.dmg\"", "\"$sha\""))
        assertNull(installerRef("https://cdn.example/z.dmg", null))
        assertNull(installerRef("https://cdn.example/z.dmg", "abc123"))
        assertNull(installerRef("http://cdn.example/z.dmg", sha))
        assertNull(installerRef(null, sha))
    }

    // --- Download ------------------------------------------------------------

    @Test
    fun `a matching file is kept and a second fetch reuses it`() = runTest {
        val downloader = UpdateDownloader(File(root, "downloads"))
        val ref = InstallerRef("$base/Zillit-Desktop-2.0.0.dmg", digest)
        var last = 0L

        val first = downloader.fetch(ref) { copied, _ -> last = copied }
        val second = downloader.fetch(ref)

        assertTrue(first.name.endsWith(".dmg"))
        assertEquals(payload.size.toLong(), last)
        assertEquals(first, second)
        assertEquals(1, requests)
    }

    @Test
    fun `a file that hashes differently is deleted and refused`() = runTest {
        val directory = File(root, "downloads")
        val downloader = UpdateDownloader(directory)

        val failure = assertFailsWith<UpdateFailure> {
            downloader.fetch(InstallerRef("$base/Zillit-Desktop-2.0.0.dmg", "0".repeat(64)))
        }

        assertEquals(UpdateFailure.Reason.Checksum, failure.reason)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an http error is a network failure`() = runTest {
        val failure = assertFailsWith<UpdateFailure> {
            UpdateDownloader(File(root, "downloads")).fetch(InstallerRef("$base/missing.dmg", digest))
        }
        assertEquals(UpdateFailure.Reason.Network, failure.reason)
    }

    // --- macOS ---------------------------------------------------------------

    @Test
    fun `bundle and team id are read the way codesign prints them`() {
        assertEquals(
            File("/Applications/Zillit-Desktop.app"),
            MacInstaller.bundleOf(File("/Applications/Zillit-Desktop.app/Contents/MacOS/Zillit-Desktop")),
        )
        assertNull(MacInstaller.bundleOf(File("/usr/bin/java")))
        assertEquals("ABCDE12345", MacInstaller.teamId("Identifier=com.zillit.desktop\nTeamIdentifier=ABCDE12345\n"))
        assertNull(MacInstaller.teamId("Signature=adhoc\nTeamIdentifier=not set\n"))
    }

    @Test
    fun `a build signed by the same team is staged`() {
        val (installer, _) = mac(newTeam = "TEAM1", ourTeam = "TEAM1")

        val prepared = installer.prepare(File(root, "update.dmg"))

        assertTrue(prepared.payload.path.endsWith("staged/Zillit-Desktop.app"))
        assertTrue(prepared.payload.isDirectory)
    }

    @Test
    fun `a build signed by another team is refused and nothing is staged`() {
        val (installer, _) = mac(newTeam = "SOMEONE", ourTeam = "TEAM1")

        val failure = assertFailsWith<UpdateFailure> { installer.prepare(File(root, "update.dmg")) }

        assertEquals(UpdateFailure.Reason.Signature, failure.reason)
        assertFalse(File(root, "work/staged/Zillit-Desktop.app").exists())
    }

    @Test
    fun `an unsigned new build is refused even when this one is unsigned`() {
        val (installer, _) = mac(newTeam = null, ourTeam = null)

        val failure = assertFailsWith<UpdateFailure> { installer.prepare(File(root, "update.dmg")) }

        assertEquals(UpdateFailure.Reason.Signature, failure.reason)
    }

    @Test
    fun `the image is detached whether or not the build passes`() {
        val (installer, log) = mac(newTeam = "SOMEONE", ourTeam = "TEAM1")

        runCatching { installer.prepare(File(root, "update.dmg")) }

        assertTrue(log.any { it.take(2) == listOf("hdiutil", "detach") })
    }

    @Test
    fun `the helper script is valid sh`() {
        val script = File(root, "install-update.sh").apply { writeText(MacInstaller.SCRIPT) }
        val check = ProcessBuilder("/bin/sh", "-n", script.path).redirectErrorStream(true).start()
        assertEquals(0, check.waitFor(), check.inputStream.bufferedReader().readText())
    }

    /** A fake `hdiutil`/`codesign`/`ditto` that plays the parts [prepare] needs. */
    private fun mac(newTeam: String?, ourTeam: String?): Pair<MacInstaller, List<List<String>>> {
        val bundle = File(root, "Applications/Zillit-Desktop.app").apply { mkdirs() }
        val log = mutableListOf<List<String>>()
        val commands = CommandRunner { command ->
            log += command
            when {
                command.take(2) == listOf("hdiutil", "attach") -> {
                    val mount = File(command[command.indexOf("-mountpoint") + 1])
                    File(mount, "Zillit-Desktop.app/Contents").mkdirs()
                    CommandResult(0, "")
                }
                command.first() == "/usr/libexec/PlistBuddy" -> CommandResult(0, "com.zillit.desktop\n")
                command.take(2) == listOf("codesign", "--verify") -> CommandResult(if (newTeam == null) 1 else 0, "")
                command.take(2) == listOf("codesign", "-dv") -> {
                    val team = if (command.last() == bundle.path) ourTeam else newTeam
                    CommandResult(0, "TeamIdentifier=${team ?: "not set"}\n")
                }
                command.first() == "ditto" -> {
                    File(command[2]).mkdirs()
                    CommandResult(0, "")
                }
                else -> CommandResult(0, "")
            }
        }
        return MacInstaller(bundle, File(root, "work"), commands) to log
    }

    // --- Windows -------------------------------------------------------------

    @Test
    fun `authenticode output is parsed and the subject must match`() {
        assertEquals(
            WindowsInstaller.Signature("Valid", "CN=Zillit Ltd, O=Zillit Ltd, C=GB"),
            WindowsInstaller.parseSignature("\r\nValid|CN=Zillit Ltd, O=Zillit Ltd, C=GB\r\n"),
        )

        val ours = "Valid|CN=Zillit Ltd"
        val msi = File(root, "z.msi")
        assertEquals(msi, windows(incoming = "Valid|CN=Zillit Ltd", ours = ours).prepare(msi).payload)
        for (incoming in listOf("Valid|CN=Someone Else", "NotSigned|")) {
            val failure = assertFailsWith<UpdateFailure> { windows(incoming, ours).prepare(msi) }
            assertEquals(UpdateFailure.Reason.Signature, failure.reason)
        }
    }

    @Test
    fun `a quote in the path cannot break out of the powershell string`() {
        val command = WindowsInstaller.signatureCommand("C:\\Users\\O'Brien\\z.msi")
        assertTrue(command.contains("'C:\\Users\\O''Brien\\z.msi'"))
    }

    private fun windows(incoming: String, ours: String): WindowsInstaller {
        val launcher = File(root, "Zillit-Desktop/Zillit-Desktop.exe")
        return WindowsInstaller(launcher, File(root, "work"), CommandRunner { command ->
            CommandResult(0, if (command.last().contains(launcher.name)) ours else incoming)
        })
    }

    // --- The coordinator -----------------------------------------------------

    @Test
    fun `download then prepare then launch hands the helper this pid`() = runBlocking {
        val launched = mutableListOf<Long>()
        val updater = InAppUpdater(
            downloader = UpdateDownloader(File(root, "downloads")),
            installer = FakeInstaller(onLaunch = { launched += it }),
            scope = this,
            appPid = { 4242L },
        )
        val ref = InstallerRef("$base/Zillit-Desktop-2.0.0.dmg", digest)

        assertTrue(updater.canInstall(ref))
        assertFalse(updater.launchInstaller())
        updater.start("2.0.0", ref)
        withTimeout(10_000) { updater.state.first { it is InstallState.Ready || it is InstallState.Failed } }

        assertEquals(InstallState.Ready("2.0.0"), updater.state.value)
        assertTrue(updater.launchInstaller())
        assertEquals(listOf(4242L), launched)
    }

    @Test
    fun `a url the platform cannot install is not offered`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        val updater = InAppUpdater(UpdateDownloader(root), FakeInstaller(), scope)
        assertFalse(updater.canInstall(InstallerRef("https://cdn.example/z.msi", digest)))
        val nowhere = InAppUpdater(UpdateDownloader(root), installer = null, scope)
        assertFalse(nowhere.canInstall(InstallerRef("https://cdn.example/z.dmg", digest)))
    }

    private class FakeInstaller(private val onLaunch: (Long) -> Unit = {}) : PlatformInstaller {
        override fun accepts(url: String) = url.endsWith(".dmg")
        override fun prepare(installer: File) = PreparedUpdate(installer)
        override fun launch(update: PreparedUpdate, appPid: Long) = onLaunch(appPid)
    }
}
