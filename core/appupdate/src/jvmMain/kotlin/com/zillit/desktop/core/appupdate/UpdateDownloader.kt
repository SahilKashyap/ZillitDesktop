package com.zillit.desktop.core.appupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Fetches an installer to disk and proves it is the one Remote Config named.
 *
 * ## Why the JDK client and not Ktor
 *
 * An installer is a few hundred megabytes read once. The app's Ktor clients
 * carry request timeouts sized for JSON, logging, and plugins that have hung
 * large transfers before; `java.net.http` streams to a file with none of that,
 * follows the CDN's redirects, and is already in the trimmed runtime.
 *
 * ## The digest is computed while writing
 *
 * The bytes are hashed as they arrive, so the check costs no second read, and
 * the file is written to `.part` and renamed only once the digest matches. A
 * file at the final name has therefore always passed; a crash or a cancel
 * leaves a `.part` the next attempt overwrites.
 *
 * @param directory where installers are kept; created on demand.
 */
class UpdateDownloader(
    private val directory: File,
    private val client: HttpClient = defaultClient(),
) {

    /**
     * The verified installer for [ref], downloading it unless an earlier run
     * already did.
     *
     * @param onProgress bytes so far and the total, when the server says.
     * @throws UpdateFailure with [UpdateFailure.Reason.Network] or
     *   [UpdateFailure.Reason.Checksum].
     */
    suspend fun fetch(ref: InstallerRef, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val target = File(directory, fileNameFor(ref))
            if (target.isFile && sha256(target) == ref.sha256) return@withContext target

            val partial = File(directory, target.name + ".part")
            val digest = download(ref.url, partial, onProgress)
            if (digest != ref.sha256) {
                partial.delete()
                throw UpdateFailure(UpdateFailure.Reason.Checksum, "installer digest did not match")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw UpdateFailure(UpdateFailure.Reason.Network, "could not move the installer into place")
            }
            // Older installers are dead weight once a newer one is in hand.
            directory.listFiles()?.filter { it.isFile && it != target }?.forEach { it.delete() }
            target
        }

    private suspend fun download(url: String, into: File, onProgress: (Long, Long?) -> Unit): String {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (io: IOException) {
            networkFailure("installer request failed", io)
        }
        if (response.statusCode() !in HTTP_OK_RANGE) {
            response.body().close()
            networkFailure("installer answered ${response.statusCode()}")
        }
        val total = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else null }
        return try {
            response.body().use { input ->
                into.outputStream().use { output -> copy(input, output, total, onProgress) }
            }
        } catch (io: IOException) {
            into.delete()
            networkFailure("installer download broke off", io)
        }
    }

    /** Copies while hashing; returns the hex digest. Checks for cancellation between chunks. */
    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            currentCoroutineContext().ensureActive()
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            copied += read
            onProgress(copied, total)
            read = input.read(buffer)
        }
        return digest.digest().toHex()
    }

    private fun networkFailure(message: String, cause: Throwable? = null): Nothing =
        throw UpdateFailure(UpdateFailure.Reason.Network, message, cause)

    internal companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private val HTTP_OK_RANGE = 200..299
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

        /**
         * `zillit-update-3f0c9a1b2c4d.dmg` — keyed on the digest, so a new build
         * never reuses an old file, and on the URL's extension, which is how
         * the installer tells a disk image from a Windows package.
         */
        fun fileNameFor(ref: InstallerRef): String {
            val path = runCatching { URI.create(ref.url).path }.getOrNull().orEmpty()
            val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
                .takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
            val stem = "zillit-update-${ref.sha256.take(DIGEST_PREFIX)}"
            return if (extension == null) stem else "$stem.$extension"
        }

        private const val DIGEST_PREFIX = 12

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/**
 * Why an update did not get as far as a restart.
 *
 * The reason is what the banner words; the message is for the log and never
 * carries a URL (a presigned link's query string is a credential).
 */
class UpdateFailure(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** Could not fetch the file. Retrying may help. */
        Network,

        /** The file is not the one Remote Config vouched for. Retrying will not help. */
        Checksum,

        /** The file is not signed by whoever signed this build. */
        Signature,

        /** Mounting, staging or starting the installer failed. */
        Install,
    }
}
