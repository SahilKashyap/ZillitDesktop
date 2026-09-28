package com.zillit.desktop.core.appupdate

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Downloading an update inside the app and handing it over.
 *
 * The report was "tap Download and it goes outside the app". This path keeps
 * the download inside with a progress bar, then opens the file for the person
 * to install — and the tests below that matter most are the ones about what it
 * must NEVER do: open a file the OS has not been told to check, touch anything
 * else in Downloads, or reach the installer that runs as administrator.
 */
class InstallerHandoffTest {

    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val root: File = Files.createTempDirectory("zillit-handoff-test").toFile()
    private val downloads = File(root, "Downloads")
    private val slowStarted = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/Zillit-Desktop-Mac-Silicon.dmg") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/x-apple-diskimage")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        createContext("/gone.dmg") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        createContext("/page.dmg") { exchange ->
            val html = "<html>not found</html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        createContext("/slow.dmg") { exchange -> stall(exchange, 3_000) { slowStarted.countDown() } }
        // Same file name, a longer stall: a second attempt still running when the first wakes.
        createContext("/later/") { exchange -> stall(exchange, 5_000) }
        // Stalls, then hangs up short of the promised length.
        createContext("/broken.dmg") { exchange ->
            stall(exchange, 1_500, finish = false)
        }
        // Answers each request on its own thread, so one stalled download
        // does not hold up the next.
        executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        start()
    }

    /**
     * Sends the first KB, waits [millis] — long enough for a test to act
     * mid-download — then the rest, or, unless [finish], hangs up.
     */
    private fun stall(
        exchange: com.sun.net.httpserver.HttpExchange,
        millis: Long,
        finish: Boolean = true,
        started: () -> Unit = {},
    ) {
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.use { body ->
            body.write(payload, 0, 1024)
            body.flush()
            started()
            Thread.sleep(millis)
            if (finish) runCatching { body.write(payload, 1024, payload.size - 1024) }
        }
        if (!finish) exchange.close()
    }

    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    /** Records what the handoff asked of the OS. */
    private class FakeSystem(var marks: Boolean = true) : HandoffSystem {
        val calls = mutableListOf<String>()
        val marked = mutableListOf<String>()
        override fun quarantine(file: File): Boolean {
            calls += "quarantine"
            marked += file.name
            return marks
        }
        override fun open(file: File) {
            calls += "open"
        }
    }

    /** An installer that fails the test if anything reaches it. */
    private class ForbiddenInstaller : PlatformInstaller {
        override fun accepts(url: String) = true
        override fun prepare(installer: File): PreparedUpdate =
            throw AssertionError("download-and-open must never prepare an install")
        override fun launch(update: PreparedUpdate, appPid: Long) =
            throw AssertionError("download-and-open must never launch the administrator installer")
    }

    private fun handoff(system: FakeSystem = FakeSystem()) =
        InstallerHandoff(downloads, UpdateDownloader.defaultClient(), system, requireHttps = false)

    // --- which links it takes ------------------------------------------------

    @Test
    fun `only a direct https installer link is downloaded in the app`() {
        val production = InstallerHandoff(downloads)
        assertTrue(production.accepts("https://downloads.zillit.com/desktop-app/Zillit-Desktop-Mac-Silicon.dmg"))
        assertFalse(production.accepts("http://downloads.zillit.com/Zillit.dmg"), "never plain http")
        assertFalse(production.accepts("https://zillit.com/download"), "a page keeps the browser")
        assertFalse(production.accepts("https://zillit.com/download.html"))
        assertFalse(production.accepts(null))
    }

    @Test
    fun `the saved name keeps the server's and drops anything unsafe`() {
        assertEquals(
            "Zillit-Desktop-Mac-Silicon.dmg",
            InstallerHandoff.installerFileName("https://cdn.example/a/Zillit-Desktop-Mac-Silicon.dmg"),
        )
        // Legal in a URL, not wanted in a file name.
        assertEquals(
            "Zillit Desktop_1.0.8.dmg",
            InstallerHandoff.installerFileName("https://cdn.example/Zillit%20Desktop$1.0.8.dmg"),
        )
        // Not a URL at all: refused here, so the browser gets it rather than a guess.
        assertNull(InstallerHandoff.installerFileName("https://cdn.example/Zillit[1.0.8].dmg"))
    }

    // --- downloading -----------------------------------------------------------

    @Test
    fun `downloads into Downloads under its own name with progress`() = runBlocking {
        val progress = mutableListOf<Pair<Long, Long?>>()
        val file = handoff().fetch("$base/Zillit-Desktop-Mac-Silicon.dmg") { copied, total ->
            progress += copied to total
        }

        assertEquals(File(downloads, "Zillit-Desktop-Mac-Silicon.dmg"), file)
        assertTrue(file.readBytes().contentEquals(payload))
        assertEquals(payload.size.toLong(), progress.last().first)
        assertEquals(payload.size.toLong(), progress.last().second, "a known size gives a real percentage")
        assertTrue(downloads.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `nothing already in Downloads is replaced or removed`() = runBlocking {
        downloads.mkdirs()
        val theirs = File(downloads, "holiday photos.zip").apply { writeText("keep me") }
        val earlier = File(downloads, "Zillit-Desktop-Mac-Silicon.dmg").apply { writeText("an older one") }

        val file = handoff().fetch("$base/Zillit-Desktop-Mac-Silicon.dmg")

        assertEquals("Zillit-Desktop-Mac-Silicon (2).dmg", file.name)
        assertEquals("keep me", theirs.readText())
        assertEquals("an older one", earlier.readText())
    }

    /**
     * Marked while still the hidden partial. Marking only at hand-over left a
     * window — Cancel between the last byte and the hand-over — in which a
     * complete installer sat in Downloads under its real name, unmarked, for
     * someone to double-click past Gatekeeper.
     */
    @Test
    fun `the file is marked before it takes the installer's name`() = runBlocking {
        val system = FakeSystem()

        val file = handoff(system).fetch("$base/Zillit-Desktop-Mac-Silicon.dmg")

        val mark = system.marked.single()
        assertTrue(mark.startsWith(".Zillit-Desktop-Mac-Silicon.dmg.") && mark.endsWith(".part"), "marked $mark")
        assertEquals(listOf("quarantine"), system.calls, "and nothing is opened by fetching")
        assertTrue(file.exists())
    }

    @Test
    fun `an installer that cannot be marked is never saved`() = runBlocking {
        val failure = assertFailsWith<UpdateFailure> {
            handoff(FakeSystem(marks = false)).fetch("$base/Zillit-Desktop-Mac-Silicon.dmg")
        }

        assertEquals(UpdateFailure.Reason.Install, failure.reason)
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
    }

    /** The person saved the same installer from a browser while the app was still downloading it. */
    @Test
    fun `a file saved meanwhile under the same name is kept`() = runBlocking {
        val download = async(Dispatchers.IO) { handoff().fetch("$base/slow.dmg") }
        assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
        val browsers = File(downloads, "slow.dmg").apply { writeText("the browser's copy") }

        val file = download.await()

        assertEquals("slow (2).dmg", file.name)
        assertEquals("the browser's copy", browsers.readText())
    }

    /**
     * macOS refused the folder (the person declined the prompt): no retry
     * fixes that. Made unwritable by putting it under a plain file, which
     * holds on every OS — Windows ignores a folder's read-only flag.
     */
    @Test
    fun `a Downloads folder that refuses writes is not reported as a network failure`() = runBlocking {
        val blocker = File(root, "blocker").apply { writeText("") }
        val refusing =
            InstallerHandoff(File(blocker, "Downloads"), UpdateDownloader.defaultClient(), FakeSystem(), false)

        val failure = assertFailsWith<UpdateFailure> { refusing.fetch("$base/Zillit-Desktop-Mac-Silicon.dmg") }

        // Network would offer a Try Again that cannot work.
        assertEquals(UpdateFailure.Reason.Install, failure.reason)
    }

    /** The app quit mid-download last time: its hidden partial is cleared, and nothing else. */
    @Test
    fun `partials an earlier run left behind are removed, and nothing else`() = runBlocking {
        downloads.mkdirs()
        val twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        fun file(name: String, old: Boolean) = File(downloads, name).apply {
            writeText(name)
            if (old) setLastModified(twoHoursAgo)
        }
        val leftover = file(".Zillit-Desktop-Mac-Silicon.dmg.8123456789.part", old = true)
        val inProgress = file(".Zillit-Desktop-Mac-Silicon.dmg.555.part", old = false)
        val notOurs = listOf(
            file("Zillit-Desktop-Mac-Silicon.dmg.part", old = true),
            file(".Zillit-Desktop-Mac-Silicon.dmg.part", old = true),
            file(".Other.dmg.8123456789.part", old = true),
            file(".Zillit-Desktop-Mac-Silicon.dmg.backup.part", old = true),
        )

        handoff().fetch("$base/Zillit-Desktop-Mac-Silicon.dmg")

        assertFalse(leftover.exists(), "an hour-old partial of ours is gone")
        assertTrue(inProgress.exists(), "a partial still being written is not")
        notOurs.forEach { assertTrue(it.exists(), "${it.name} is not one of ours") }
    }

    @Test
    fun `a missing file fails and leaves nothing behind`() = runBlocking {
        val failure = assertFailsWith<UpdateFailure> { handoff().fetch("$base/gone.dmg") }
        assertEquals(UpdateFailure.Reason.Network, failure.reason)
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an error page is never saved as an installer`() = runBlocking {
        assertFailsWith<UpdateFailure> { handoff().fetch("$base/page.dmg") }
        assertTrue(downloads.listFiles().orEmpty().isEmpty(), "an HTML page named .dmg would open as a broken image")
    }

    // --- handing over ----------------------------------------------------------

    @Test
    fun `the file is marked as downloaded before it is opened`() {
        val system = FakeSystem()
        val file = File(downloads.apply { mkdirs() }, "Zillit.dmg").apply { writeBytes(payload) }

        handoff(system).handOver(file)

        assertEquals(listOf("quarantine", "open"), system.calls)
    }

    @Test
    fun `a file that could not be marked is never opened, and is removed`() {
        val system = FakeSystem(marks = false)
        val file = File(downloads.apply { mkdirs() }, "Zillit.dmg").apply { writeBytes(payload) }

        val failure = assertFailsWith<UpdateFailure> { handoff(system).handOver(file) }

        assertEquals(UpdateFailure.Reason.Install, failure.reason)
        assertFalse("open" in system.calls, "an unmarked installer would open without the OS checking it")
        assertFalse(file.exists(), "and it must not be left for someone to open by hand")
    }

    // --- through the updater -------------------------------------------------------

    @Test
    fun `download and open ends Downloaded and never reaches the installer`() = runBlocking {
        val system = FakeSystem()
        val updater = InAppUpdater(
            downloader = UpdateDownloader(File(root, "cache")),
            installer = ForbiddenInstaller(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            handoff = handoff(system),
        )

        updater.downloadAndOpen("1.0.8", "$base/Zillit-Desktop-Mac-Silicon.dmg")
        val final = withTimeout(10_000) {
            updater.state.first { it is InstallState.Downloaded || it is InstallState.Failed }
        }

        assertEquals(InstallState.Downloaded("1.0.8"), final)
        assertEquals("open", system.calls.last())
        assertTrue(system.calls.dropLast(1).all { it == "quarantine" }, "nothing opened before it was marked")
    }

    private fun updater(system: FakeSystem = FakeSystem()) = InAppUpdater(
        downloader = UpdateDownloader(File(root, "cache")),
        installer = ForbiddenInstaller(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        handoff = handoff(system),
    )

    private suspend fun InAppUpdater.settled(): InstallState = withTimeout(20_000) {
        state.first { it is InstallState.Downloaded || it is InstallState.Failed }
    }

    /**
     * Waits until the first KB has been read, so the attempt is now blocked on
     * the stalled rest. Cancelled any earlier, it would exit on that first read
     * and never reach the late wake-up these tests are about.
     */
    private suspend fun InAppUpdater.awaitFirstBytes() = withTimeout(10_000) {
        state.first { it is InstallState.Downloading && it.fraction != null }
    }

    /**
     * Cancel, then Download again, while the first attempt is still blocked
     * on the network. Both once wrote the same `.part`; when the first woke and
     * cleaned up, it deleted the second's file, which then failed at 100%.
     */
    @Test
    fun `a cancelled download that wakes late does not undo the next one`() = runBlocking {
        val updater = updater()
        updater.downloadAndOpen("1.0.8", "$base/slow.dmg")
        updater.awaitFirstBytes()
        updater.cancel()

        // Stalls past the moment the first attempt wakes (3 s in).
        updater.downloadAndOpen("1.0.8", "$base/later/slow.dmg")

        assertEquals(InstallState.Downloaded("1.0.8"), updater.settled())
        withTimeout(10_000) { while (downloads.list().orEmpty().any { it.endsWith(".part") }) delay(50) }
        assertEquals(listOf("slow.dmg"), downloads.list().orEmpty().toList())
    }

    /** A socket that breaks after Cancel is not news: Cancel's Idle stands. */
    @Test
    fun `a cancelled download that breaks on its way out reports nothing`() = runBlocking {
        val updater = updater()
        updater.downloadAndOpen("1.0.8", "$base/broken.dmg")
        updater.awaitFirstBytes()
        updater.cancel()

        // Unwound once its partial is gone; the failure would land right after.
        withTimeout(10_000) {
            while (downloads.list().orEmpty().any { it.endsWith(".part") }) delay(20)
        }
        delay(500)

        assertEquals(InstallState.Idle, updater.state.value)
    }

    @Test
    fun `progress that arrives after Cancel does not bring the bar back`() = runBlocking {
        val updater = updater()
        updater.downloadAndOpen("1.0.8", "$base/slow.dmg")
        assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
        updater.cancel()

        // The chunk the stalled attempt had already read when Cancel landed.
        updater.report("1.0.8", copied = 150_000, total = 300_000)

        assertEquals(InstallState.Idle, updater.state.value)
    }

    @Test
    fun `progress for another version is not shown as this one's`() = runBlocking {
        val updater = updater()
        updater.downloadAndOpen("1.0.8", "$base/slow.dmg")
        // Settled on the first KB's report; the rest is 3 s away.
        updater.awaitFirstBytes()

        updater.report("1.0.7", copied = 150_000, total = 300_000)

        val shown = updater.state.value as InstallState.Downloading
        assertEquals("1.0.8", shown.version)
        assertTrue(shown.fraction!! < 0.01f, "1.0.7's 50% must not show as 1.0.8's; got ${shown.fraction}")
        updater.cancel()
    }

    /** Moved or deleted after it opened: the strip must not keep a button that does nothing. */
    @Test
    fun `opening again a file that is gone offers the download again`() = runBlocking {
        val updater = updater()
        updater.downloadAndOpen("1.0.8", "$base/Zillit-Desktop-Mac-Silicon.dmg")
        assertEquals(InstallState.Downloaded("1.0.8"), updater.settled())
        assertTrue(File(downloads, "Zillit-Desktop-Mac-Silicon.dmg").delete())

        updater.openDownloaded()

        assertEquals(InstallState.Idle, updater.state.value)
    }

    @Test
    fun `opening again a file that can no longer be marked fails closed`() = runBlocking {
        val system = FakeSystem()
        val updater = updater(system)
        updater.downloadAndOpen("1.0.8", "$base/Zillit-Desktop-Mac-Silicon.dmg")
        assertEquals(InstallState.Downloaded("1.0.8"), updater.settled())
        system.marks = false
        val opensBefore = system.calls.count { it == "open" }

        updater.openDownloaded()

        val final = withTimeout(10_000) { updater.state.first { it is InstallState.Failed } }
        assertEquals(InstallState.Failed("1.0.8", UpdateFailure.Reason.Install), final)
        assertEquals(opensBefore, system.calls.count { it == "open" }, "never opened unmarked")
        assertFalse(File(downloads, "Zillit-Desktop-Mac-Silicon.dmg").exists())
    }

    @Test
    fun `cancelling stops the download and leaves no partial file`() = runBlocking {
        val updater = InAppUpdater(
            downloader = UpdateDownloader(File(root, "cache")),
            installer = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            handoff = handoff(),
        )

        updater.downloadAndOpen("1.0.8", "$base/slow.dmg")
        assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
        // The partial must exist first, or "Downloads is empty" below proves nothing.
        withTimeout(10_000) { while (downloads.list().orEmpty().none { it.endsWith(".part") }) delay(10) }
        updater.cancel()

        assertEquals(InstallState.Idle, updater.state.value)
        // The partial is removed as the download unwinds.
        withTimeout(10_000) {
            while (downloads.listFiles().orEmpty().isNotEmpty()) delay(50)
        }
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a page link is not offered in the app`() {
        val updater = InAppUpdater(
            downloader = UpdateDownloader(File(root, "cache")),
            installer = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            handoff = InstallerHandoff(downloads),
        )
        assertTrue(updater.canDownload("https://downloads.zillit.com/desktop-app/Zillit-Desktop-Mac-Silicon.dmg"))
        assertFalse(updater.canDownload("https://zillit.com/download"))
        assertNull(updater.state.value.version)
    }

    /**
     * The real macOS mark, read back. The fail-closed rule depends on the
     * read-back working: if it could never see the mark, every download would
     * be deleted as unmarkable. Opening is not exercised — it would open a
     * window on the machine running the tests.
     */
    @Test
    fun `on macOS the quarantine mark is really written and read back`() {
        if ("mac" !in System.getProperty("os.name").orEmpty().lowercase()) return
        val file = File(downloads.apply { mkdirs() }, "probe.dmg").apply { writeBytes(payload) }

        assertTrue(HandoffSystem.current().quarantine(file))
        val attribute = ProcessBuilder("/usr/bin/xattr", "-p", "com.apple.quarantine", file.absolutePath)
            .start().inputStream.readBytes().decodeToString()
        assertTrue(attribute.startsWith("0083;"), "got '$attribute'")
        // And the read-back can say no: xattr's error text must not pass for a mark.
        assertFalse(HandoffSystem.current().quarantine(File(downloads, "missing.dmg")), "no file, no mark")
    }
}
