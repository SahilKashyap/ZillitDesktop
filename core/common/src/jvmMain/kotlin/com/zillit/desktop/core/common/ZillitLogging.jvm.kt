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
    val logFile: File get() = File(ZillitVariant.dataDir, "logs/zillit.log")

    fun initialise(verbose: Boolean) {
        Napier.takeLogarithm() // Idempotent: re-initialising must not double every line.
        val floor = Floor(verbose)
        Napier.base(ConsoleAntilog(floor))
        Napier.base(FileAntilog(logFile, floor))

        ZillitLog.i(TAG) { "Logging to ${logFile.path} (verbose=$verbose)" }
    }

    /**
     * How much survives, by who said it.
     *
     * Production keeps warnings and above, and that is not a size concern: a
     * verbose log on someone's machine is a standing collection of who did what
     * and when — `CallCoordinator`'s roster names who was on a call, so does
     * `MediasoupSession`'s peer list, and `Auth` records every production
     * opened. None of that belongs on disk for the life of two 5 MB files.
     *
     * What does belong is the handful of subsystems whose INFO is not a record
     * of the user at all but the state of *this install* — which build, which
     * environment, whether a database key was replaced, whether the token
     * session established. Those are the lines a sent-in log is read for, and a
     * field log without them is close to unreadable: a real one arrived with
     * three local databases wiped and no way to tell whether the key had been
     * regenerated, because that one line was INFO (2026-10-12, 2026-10-14).
     *
     * So the floor is per-tag. [FIELD_TAGS] keep INFO in production; everything
     * else stays at warnings, where the noisy and the personal both are.
     */
    internal class Floor(private val verbose: Boolean) {
        fun keeps(priority: LogLevel, tag: String?): Boolean = priority >= minimumFor(tag)

        private fun minimumFor(tag: String?) = when {
            verbose -> LogLevel.DEBUG
            tag in FIELD_TAGS -> LogLevel.INFO
            else -> LogLevel.WARNING
        }
    }

    /**
     * Tags whose INFO is kept in production: the identity of the build and the
     * state of its session, never what was done on it.
     *
     * Deliberately a short list, and the bar for joining it is "a log sent in
     * from the field cannot be read without this". Anything that names a
     * person, a production or a call does not qualify, whatever its level.
     */
    private val FIELD_TAGS = setOf(
        "Logging",
        "Startup",
        "Shutdown",
        "ConfigLoader",
        "DatabaseKey",
        "TokenSession",
        "AppUpdate",
        // The gates between picking a production and its board drawing
        // ([OpenTrace]). Here for the same reason "Startup" is: the question
        // it answers — which gate is the wait — is only ever asked about
        // somebody else's machine, where a log is all there is. A dozen lines
        // per open, and the production appears as a tail, the rule
        // "TokenSession" already logs ids under.
        "Open",
    )

    private const val TAG = "Logging"
}

private val TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun format(priority: LogLevel, tag: String?, message: String): String =
    "${TIMESTAMP.format(Instant.now())} ${priority.name.first()} ${tag ?: "-"}: $message"

private class ConsoleAntilog(private val floor: ZillitLogging.Floor) : Antilog() {
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        if (!floor.keeps(priority, tag)) return
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
private class FileAntilog(private val file: File, private val floor: ZillitLogging.Floor) : Antilog() {

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        if (!floor.keeps(priority, tag)) return

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
