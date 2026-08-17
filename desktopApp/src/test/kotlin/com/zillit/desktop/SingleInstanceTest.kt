package com.zillit.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard that keeps two copies of Zillit off one data directory.
 *
 * The interesting case is not "does it lock" — it is what happens after a
 * crash. A marker file written at startup and removed at exit passes the happy
 * path and then locks the user out permanently the first time the app dies
 * badly, which is precisely when they most need to reopen it.
 */
class SingleInstanceTest {

    private val directory: File = Files.createTempDirectory("zillit-single").toFile()

    @AfterTest
    fun cleanUp() {
        SingleInstance.release()
        directory.deleteRecursively()
    }

    @Test
    fun `the first copy claims the directory`() {
        assertTrue(SingleInstance.claim(directory))
    }

    /**
     * A second *process* is refused.
     *
     * Simulated by taking the same OS lock directly, because two JVMs are not
     * available inside one test — and an in-process second `claim` would hit
     * `OverlappingFileLockException`, a different path that would not prove the
     * cross-process behaviour.
     */
    @Test
    fun `a second copy is refused while the first holds the lock`() {
        val other = RandomAccessFile(File(directory, "zillit.lock"), "rw")
        val held = other.channel.tryLock()
        try {
            assertFalse(SingleInstance.claim(directory))
        } finally {
            held?.release()
            other.close()
        }
    }

    /**
     * A lock file left behind by a crash does not lock anyone out.
     *
     * The file survives; the *lock* does not, because the OS drops it with the
     * process. This is the whole reason for using a lock rather than the
     * file's existence.
     */
    @Test
    fun `a lock file left by a crashed run does not block the next launch`() {
        val stale = File(directory, "zillit.lock")
        stale.writeText("left behind by a process that never got to clean up")

        assertTrue(SingleInstance.claim(directory))
    }

    /** Releasing hands it back, so a relaunch after a clean quit is immediate. */
    @Test
    fun `the claim can be released and retaken`() {
        assertTrue(SingleInstance.claim(directory))
        SingleInstance.release()

        assertTrue(SingleInstance.claim(directory))
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        val nested = File(directory, "not/created/yet")

        assertTrue(SingleInstance.claim(nested))
        assertTrue(nested.isDirectory)
    }
}
