package com.zillit.desktop.core.common

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Starts logging.
 *
 * **Must be called before anything else.** Napier discards every log until a
 * backend is registered, so without this `ZillitLog` is silently a no-op — which
 * is exactly what it was until this file existed.
 *
 * Two sinks:
 *  - **console**, for running from a terminal or IDE
 *  - **file**, at `~/.zillit/logs/zillit.log`, so a user can send a log after
 *    something went wrong on their machine. Desktop has no logcat; without a
 *    file there is nothing to ask for.
 *
 * Everything still goes through [ZillitLog], which redacts before it reaches
 * either sink (plan §8.4).
 */
object ZillitLogging {

    /** Where the log file lives. Printed at startup so it is easy to find. */
    val logFile: File get() = File(System.getProperty("user.home"), ".zillit/logs/zillit.log")

    fun initialise(verbose: Boolean) {
        Napier.takeLogarithm() // Idempotent: re-initialising must not double every line.
        Napier.base(ConsoleAntilog(minimumLevel(verbose)))
        Napier.base(FileAntilog(logFile, minimumLevel(verbose)))

        ZillitLog.i(TAG) { "Logging to ${logFile.path} (verbose=$verbose)" }
    }

    /**
     * Production keeps warnings and above.
     *
     * Not a size concern — it is that verbose logs on a user's machine are a
     * standing collection of who did what and when, and the less of that sitting
     * on disk the better.
     */
    private fun minimumLevel(verbose: Boolean) = if (verbose) LogLevel.DEBUG else LogLevel.WARNING

    private const val TAG = "Logging"
}

private val TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun format(priority: LogLevel, tag: String?, message: String): String =
    "${TIMESTAMP.format(Instant.now())} ${priority.name.first()} ${tag ?: "-"}: $message"

private class ConsoleAntilog(private val minimum: LogLevel) : Antilog() {
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        if (priority < minimum) return
        val line = format(priority, tag, message.orEmpty())
        // Errors and warnings to stderr so a terminal shows them in red and
        // shell redirection can separate them.
        if (priority >= LogLevel.WARNING) System.err.println(line) else println(line)
        throwable?.printStackTrace()
    }
}

/**
 * Appends to a size-capped file with a single rotation.
 *
 * One rotation, not a full logging framework: this exists so support can say
 * "send me the log", and two files covering the recent past is enough for that.
 * Bringing in Logback for it would add a config surface nobody would tune.
 */
private class FileAntilog(private val file: File, private val minimum: LogLevel) : Antilog() {

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        if (priority < minimum) return

        runCatching {
            rotateIfNeeded()
            file.appendText(format(priority, tag, message.orEmpty()) + "\n")
            throwable?.let { file.appendText(it.stackTraceToString() + "\n") }
        }
        // A failure to write a log must never break the app. Deliberately
        // silent: reporting it would itself try to log.
    }

    private fun rotateIfNeeded() {
        if (!file.isFile || file.length() < MAX_BYTES) return
        val previous = File(file.parentFile, "${file.name}.1")
        previous.delete()
        file.renameTo(previous)
    }

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}
