package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * One Zillit per user, whichever copy they launched.
 *
 * ## Why the bundle cannot decide this
 *
 * macOS keeps you to one instance of one `.app`, and that is not the property
 * that matters here: everything Zillit owns lives at a fixed path in the user's
 * home — the database, preferences, the workspace session, the call engine's
 * cache. Two *different* bundles therefore share all of it. An install in
 * `/Applications` and a copy still sitting in `~/Downloads` are, to macOS, two
 * unrelated apps it will gladly run side by side.
 *
 * That is not hypothetical: it is what produced the `SIGTRAP` inside
 * `NativeDB.prepare` on 2026-08-12, two copies writing one SQLite file.
 *
 * So the lock is taken on the **data directory**, not on the bundle. That is
 * the thing being shared, and it is the only definition that catches the case
 * the operating system misses.
 *
 * ## Why a lock file rather than a port or a marker file
 *
 * An OS file lock dies with the process that holds it — including a process
 * that crashed, was force-quit, or lost power. A marker file written at startup
 * and deleted at exit does not: one crash and every later launch is refused,
 * with no way out but deleting a file the user cannot be expected to know
 * about. A socket would work, but it asks for a firewall prompt on first run,
 * which is a poor first impression for a guard nobody should ever notice.
 */
object SingleInstance {

    private var lock: FileLock? = null

    /**
     * Held for the life of the process, so it is never collected.
     *
     * A `FileLock` whose channel is garbage collected releases — and a lock that
     * quietly lets go after a minute is worse than none, because the failure
     * then depends on GC timing.
     */
    private var channel: RandomAccessFile? = null

    /**
     * Claims this user's Zillit, or reports that another copy already has it.
     *
     * Failing **open** on an unexpected I/O error is deliberate: a read-only home
     * directory or an exotic filesystem should not stop the app starting. The
     * guard exists to prevent an ordinary accident, and the database settings
     * behind it are what make the accident survivable when it does happen.
     */
    fun claim(directory: File = defaultDirectory()): Boolean {
        val file = File(directory.apply { mkdirs() }, LOCK_FILE)
        return try {
            val handle = RandomAccessFile(file, "rw")
            val acquired = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Same JVM already holds it. Only reachable if this is called
                // twice, which is a programming error rather than a second copy.
                null
            }

            if (acquired == null) {
                handle.close()
                report("another copy of Zillit is already running; not starting a second")
                false
            } else {
                channel = handle
                lock = acquired
                true
            }
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            report("could not take the single-instance lock (${throwable::class.simpleName}); starting anyway")
            true
        }
    }

    /** Frees the claim. The OS does this on exit too; this makes it prompt. */
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    /**
     * Says it twice, because at this point only one of the two works.
     *
     * The guard runs before `AppGraph.build()` installs file logging — it must,
     * since the whole point is to stop the second copy touching anything. So
     * `ZillitLog` here writes to a logger that does not exist yet and the
     * message is lost, which is exactly what happened the first time this was
     * tested: a second copy exited correctly and left no trace of why.
     *
     * stderr always works and is what a packaged app's console shows; the
     * `ZillitLog` call still earns its place for the release path, which runs
     * at shutdown when logging *is* up.
     */
    private fun report(message: String) {
        System.err.println("[$TAG] $message")
        runCatching { ZillitLog.i(TAG) { message } }
    }

    private fun defaultDirectory() = File(System.getProperty("user.home"), ".zillit")

    private const val LOCK_FILE = "zillit.lock"
    private const val TAG = "SingleInstance"
}
