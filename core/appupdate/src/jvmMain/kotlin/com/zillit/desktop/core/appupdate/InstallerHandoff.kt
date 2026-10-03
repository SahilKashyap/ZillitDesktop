package com.zillit.desktop.core.appupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/**
 * Downloads an installer into the person's Downloads folder and hands it to
 * them: the in-app replacement for sending them off to a browser.
 *
 * Deliberately NOT an installer. Nothing here mounts, copies or replaces
 * anything itself, and nothing here elevates: the file is saved, marked as
 * downloaded from the internet, and handed to the OS to open — which is
 * exactly what double-clicking a browser download does, with exactly the same
 * checks. macOS Gatekeeper assesses the quarantined disk image when it is
 * mounted; Windows SmartScreen vets the marked package when Explorer
 * shell-executes it, and the install that follows asks for administrator
 * through its own elevation, not ours. What this adds over the browser trip it
 * replaces is a progress bar inside the app.
 *
 * It does mean a build gets this far without its digest being checked, so the
 * OS prompt is load-bearing — see [HandoffSystem.open] on each platform. The
 * install path proper ([PlatformInstaller]) checks the digest and the
 * signature instead, and is preferred wherever Remote Config makes it
 * possible; this is the fallback for when it cannot.
 *
 * Not [UpdateDownloader]: that one deletes every other file in its folder,
 * which is right for its private cache and ruinous in ~/Downloads.
 */
class InstallerHandoff internal constructor(
    private val directory: File,
    private val client: HttpClient,
    private val system: HandoffSystem,
    /** Tests only, for a plain-http local server. Production cannot reach this. */
    private val requireHttps: Boolean,
) {
    constructor(
        directory: File,
        client: HttpClient = UpdateDownloader.defaultClient(),
        system: HandoffSystem = HandoffSystem.current(),
    ) : this(directory, client, system, requireHttps = true)

    /**
     * True when [url] is a direct link to an installer this can hand over.
     *
     * A download page is not one. Configurations sometimes point the update
     * link at a web page rather than the file; fetching that would save an
     * HTML page with a `.dmg` name. Those keep the browser.
     */
    fun accepts(url: String?): Boolean = installerFileName(url, requireHttps) != null

    /**
     * Saves [url] into the Downloads folder under its own name — never
     * replacing or removing anything already there — and returns the file.
     *
     * Streams through a hidden `.part` file, renamed only once it is complete
     * and marked as downloaded, so a cancelled or broken download never leaves
     * something that looks finished, and nothing ever sits under the
     * installer's name unmarked — not even when Cancel lands between the last
     * byte and [handOver].
     */
    suspend fun fetch(url: String, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val name = installerFileName(url, requireHttps) ?: throw UpdateFailure(
                UpdateFailure.Reason.Network,
                "not a direct installer link",
            )
            sweepStale(name)
            // Unique to this attempt. A cancelled download can still be
            // unwinding when the person taps Download again; with a shared
            // name, its clean-up would delete the new attempt's file.
            val partial = createPartial(name)
            try {
                download(url, partial, onProgress)
                if (!system.quarantine(partial)) {
                    throw UpdateFailure(UpdateFailure.Reason.Install, "could not mark the installer as downloaded")
                }
                currentCoroutineContext().ensureActive()
                moveToFreeName(partial, name)
            } finally {
                // Cancelled, broken off, or refused: whatever arrived goes.
                // After a successful move there is nothing here to delete.
                partial.delete()
            }
        }

    /**
     * Marks [file] as downloaded from the internet, then opens it.
     *
     * The quarantine mark is the point of doing it this way round: it is what
     * makes macOS check the file before anything from it runs, and Windows put
     * SmartScreen in front of the install. A file the app wrote itself has
     * none, and opening an unmarked installer would skip the check a browser
     * download gets.
     */
    fun handOver(file: File) {
        // Fail closed. An installer that could not be marked is one the OS
        // would open without checking, so it is removed rather than shown —
        // leaving it in Downloads would only invite someone to open it.
        if (!system.quarantine(file)) {
            file.delete()
            throw UpdateFailure(UpdateFailure.Reason.Install, "could not mark the installer as downloaded")
        }
        system.open(file)
    }

    /**
     * The attempt's hidden partial file, created fresh. Failing here is the
     * Downloads folder refusing us — on macOS, the person declining the
     * folder-access prompt — which no retry fixes, so it is not a network
     * failure: the strip offers the browser instead.
     */
    private fun createPartial(name: String): File = try {
        directory.mkdirs()
        Files.createTempFile(directory.toPath(), ".$name.", ".part").toFile()
    } catch (io: IOException) {
        throw UpdateFailure(UpdateFailure.Reason.Install, "cannot write to ${directory.name}", io)
    }

    /**
     * Gives [partial] the name [name], or `name (2)`… — whichever is free at
     * that moment. The name is chosen at the end, not the start: a download
     * takes minutes, and the person may save the same installer from a
     * browser meanwhile. A name taken in between is skipped, never replaced.
     */
    private fun moveToFreeName(partial: File, name: String): File {
        repeat(MOVE_ATTEMPTS) {
            val target = freeName(name)
            try {
                claim(partial, target)
                return target
            } catch (@Suppress("SwallowedException") taken: FileAlreadyExistsException) {
                // Taken since freeName looked; pick again.
            }
        }
        throw UpdateFailure(UpdateFailure.Reason.Install, "no free name for $name")
    }

    /**
     * Names [partial]'s file [target], refusing a name that exists.
     *
     * A hard link is made or refused in one step. A move is not: on macOS and
     * Linux it checks the target, then renames — and a rename replaces
     * whatever appeared in between. The partial's own name is removed by
     * [fetch]'s clean-up; the mark lives on the file, so both names carry it.
     * Volumes without hard links (exFAT, some network shares) get the move.
     */
    private fun claim(partial: File, target: File) {
        try {
            Files.createLink(target.toPath(), partial.toPath())
        } catch (taken: FileAlreadyExistsException) {
            throw taken
        } catch (@Suppress("SwallowedException") noLinks: FileSystemException) {
            Files.move(partial.toPath(), target.toPath())
        } catch (@Suppress("SwallowedException") unsupported: UnsupportedOperationException) {
            Files.move(partial.toPath(), target.toPath())
        }
    }

    /**
     * Removes partials an earlier run left behind: the app quit or crashed
     * mid-download, so its clean-up never ran, and each attempt's name is
     * unique. Only this class's own files — the hidden `.<name>.<digits>.part`
     * that [createPartial] makes — and only once untouched for an hour, so no
     * download still in progress, in this copy of the app or another, is hit.
     */
    private fun sweepStale(name: String) {
        val ours = Regex("^\\." + Regex.escape(name) + "\\.\\d+\\.part$")
        val cutoff = System.currentTimeMillis() - STALE_PARTIAL_MILLIS
        directory.listFiles().orEmpty()
            .filter { ours.matches(it.name) && it.lastModified() < cutoff }
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .forEach { it.delete() }
    }

    private suspend fun download(url: String, into: File, onProgress: (Long, Long?) -> Unit) {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (io: IOException) {
            networkFailure("installer request failed", io)
        }
        // A CDN that cannot find the file sometimes answers 200 with an error
        // page. Saved as a .dmg, that would fail only when opened.
        val type = response.headers().firstValue("Content-Type").orElse("")
        val refusal = when {
            response.statusCode() !in HTTP_OK_RANGE -> "installer answered ${response.statusCode()}"
            type.startsWith("text/", ignoreCase = true) -> "installer link answered with $type"
            else -> null
        }
        if (refusal != null) {
            response.body().close()
            networkFailure(refusal)
        }
        val total = response.headers().firstValueAsLong("Content-Length")
            .let { if (it.isPresent && it.asLong > 0) it.asLong else null }
        try {
            response.body().use { input -> into.outputStream().use { copy(input, it, total, onProgress) } }
        } catch (io: IOException) {
            networkFailure("installer download broke off", io)
        }
    }

    private suspend fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            currentCoroutineContext().ensureActive()
            output.write(buffer, 0, read)
            copied += read
            onProgress(copied, total)
            read = input.read(buffer)
        }
    }

    private fun networkFailure(message: String, cause: Throwable? = null): Nothing =
        throw UpdateFailure(UpdateFailure.Reason.Network, message, cause)

    /**
     * `name`, or `name (2)`, `name (3)`… — whatever is free. Never an existing
     * entry, a dangling symlink included: [File.exists] follows links, and
     * would call one free.
     */
    private fun freeName(name: String): File {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        var candidate = File(directory, name)
        var n = 2
        while (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            candidate = File(directory, "$stem ($n).$extension")
            n++
        }
        return candidate
    }

    internal companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MOVE_ATTEMPTS = 5
        private const val STALE_PARTIAL_MILLIS = 60 * 60 * 1000L
        private val HTTP_OK_RANGE = 200..299

        /** What a direct installer link may end in. Anything else is a page. */
        private val INSTALLER_EXTENSIONS = setOf("dmg", "pkg", "msi", "exe")

        /** Keeps the server's name, reduced to characters safe in any file system. */
        private val UNSAFE = Regex("[^A-Za-z0-9._() -]")

        /**
         * The file name to save [url] under, or null when it is not an https
         * link whose last path segment is an installer.
         */
        fun installerFileName(url: String?, requireHttps: Boolean = true): String? {
            val raw = url?.trim().orEmpty()
            val scheme = if (requireHttps) "https://" else "http"
            if (!raw.startsWith(scheme, ignoreCase = true)) return null
            val path = runCatching { URI.create(raw).path }.getOrNull().orEmpty()
            val last = path.substringAfterLast('/')
            val extension = last.substringAfterLast('.', "").lowercase()
            if (extension !in INSTALLER_EXTENSIONS) return null
            val stem = last.substringBeforeLast('.').replace(UNSAFE, "_").trim('.', ' ')
            return if (stem.isEmpty()) null else "$stem.$extension"
        }
    }
}

/** What handing a file over needs from the operating system. A seam for tests. */
interface HandoffSystem {

    /**
     * Marks [file] so the OS treats it as downloaded from the internet.
     *
     * @return true only once the mark is confirmed to be on the file.
     */
    fun quarantine(file: File): Boolean

    /**
     * Hands [file] to the person — opened where the OS checks it as it opens
     * it, revealed otherwise. Never a path that skips those checks.
     */
    fun open(file: File)

    companion object {
        fun current(): HandoffSystem = when {
            isMac() -> MacHandoff
            isWindows() -> WindowsHandoff
            else -> RevealOnlyHandoff
        }

        private fun osName() = System.getProperty("os.name").orEmpty().lowercase()
        private fun isMac() = "mac" in osName()
        private fun isWindows() = "windows" in osName()
    }
}

/**
 * Quarantined with the flags Safari uses (`0083`), then opened.
 *
 * Opening a disk image mounts it — it runs nothing — and because it carries
 * the quarantine mark, Gatekeeper assesses it and, later, the app dragged out
 * of it. The mark deliberately omits the "already approved" bit (`0x40`),
 * which would skip that assessment.
 */
private object MacHandoff : HandoffSystem {
    override fun quarantine(file: File): Boolean {
        val stamp = java.lang.Long.toHexString(System.currentTimeMillis() / MILLIS)
        run("/usr/bin/xattr", "-w", QUARANTINE, "0083;$stamp;Zillit;", file.absolutePath)
        // Read back rather than trusting the exit code: the check that matters
        // is that the mark is there, not that the tool said it put it there.
        return run("/usr/bin/xattr", "-p", QUARANTINE, file.absolutePath).startsWith("0083;")
    }

    override fun open(file: File) {
        run("/usr/bin/open", file.absolutePath)
    }
}

/**
 * Marked as from the Internet zone, then started.
 *
 * On Windows "opening" an .msi starts the install, and that is now the point:
 * a download that only appears selected in Explorer leaves the person to find
 * it and double-click, and they do not — "downloaded" reads as "installed"
 * and they carry on with the old build.
 *
 * Started through Explorer rather than by running `msiexec` from here, because
 * going through Explorer is a ShellExecute: it honours the Zone.Identifier
 * mark [quarantine] just wrote, so SmartScreen vets the package and an
 * unknown publisher still has to be confirmed. Nothing on this path has
 * checked a digest — that is the install path's job, through
 * [PlatformInstaller] — which makes that prompt the only thing between a
 * swapped file on the CDN and an install. It must not be routed around.
 *
 * Explorer returns at once and reports nothing, so a package it could not
 * start looks exactly like one it did: the file stays in Downloads and the
 * strip keeps the button that opens it again.
 */
private object WindowsHandoff : HandoffSystem {
    override fun quarantine(file: File): Boolean = runCatching {
        val mark = File(file.absolutePath + ":Zone.Identifier")
        mark.writeText("[ZoneTransfer]\r\nZoneId=3\r\n")
        "ZoneId=3" in mark.readText()
    }.getOrDefault(false)

    override fun open(file: File) {
        // The path on its own, as its own argument. Java quotes an argument
        // with a space in it, which is what Explorer wants for a path it is
        // to shell-execute — and was wrong for the "/select," this used to
        // pass alongside it, which Explorer does not recognise quoted and
        // answers by opening Documents.
        run("explorer.exe", file.absolutePath)
    }
}

/** Anywhere else: show the folder and leave the file alone. */
private object RevealOnlyHandoff : HandoffSystem {
    // Nothing to mark, and nothing is run: the folder is shown, not the file.
    override fun quarantine(file: File): Boolean = true

    override fun open(file: File) {
        runCatching { java.awt.Desktop.getDesktop().open(file.parentFile) }
    }
}

private const val MILLIS = 1000L
private const val TIMEOUT_SECONDS = 20L
private const val QUARANTINE = "com.apple.quarantine"

/** Runs a short command, waits, and returns what it printed; "" if it could not run. */
private fun run(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString().trim()
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    output
}.getOrDefault("")
