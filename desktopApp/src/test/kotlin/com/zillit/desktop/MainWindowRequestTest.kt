package com.zillit.desktop

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The handshake that turns a second launch into a window.
 *
 * The case worth protecting is the one that is easy to get wrong in the other
 * direction: a second copy that signals and exits *silently* against a running
 * copy too old to be listening. That looks exactly like Zillit failing to
 * start, and it is why [MainWindowRequest.ask] waits for an answer rather than
 * assuming one.
 */
class MainWindowRequestTest {

    private val directory: File = Files.createTempDirectory("zillit-show").toFile()
    private val marker: File get() = File(directory, "show-main-window")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** Nobody watching: the caller is told, so it can fall back to the dialog. */
    @Test
    fun `an unanswered request reports failure`() {
        assertFalse(MainWindowRequest.ask(directory))
    }

    /**
     * And leaves nothing behind. A request that outlived its asking would be
     * acted on by the next copy to start watching, raising a window at a
     * moment nobody asked for one.
     */
    @Test
    fun `an unanswered request does not leave its marker behind`() {
        MainWindowRequest.ask(directory)

        assertFalse(marker.exists())
    }

    /** The running copy takes it, and the asker exits quietly. */
    @Test
    fun `a consumed request reports success`() {
        val watcher = thread {
            while (!MainWindowRequest.consume(directory)) {
                Thread.sleep(POLL)
            }
        }
        try {
            assertTrue(MainWindowRequest.ask(directory))
        } finally {
            watcher.join()
        }
    }

    /** Consuming is once per request: two polls must not raise two windows. */
    @Test
    fun `a request is consumed only once`() {
        marker.writeText("show")

        assertTrue(MainWindowRequest.consume(directory))
        assertFalse(MainWindowRequest.consume(directory))
    }

    /** Nothing asked for: the watcher must stay quiet rather than raise a window. */
    @Test
    fun `no request means nothing to consume`() {
        assertFalse(MainWindowRequest.consume(directory))
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        val nested = File(directory, "not/created/yet")

        MainWindowRequest.ask(nested)

        assertTrue(nested.isDirectory)
    }

    private companion object {
        const val POLL = 20L
    }
}
