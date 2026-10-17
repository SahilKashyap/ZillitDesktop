package com.zillit.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch
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

    /**
     * The regression this exists for.
     *
     * The only consumer used to be [MainWindowRequest.watch], started from a
     * `LaunchedEffect` inside the composition — so nothing was listening until
     * `AppGraph.build()` had finished wiring the entire application. A launch
     * during those seconds went unanswered and the person who double-clicked a
     * slow-starting app was told to switch to a window that did not exist yet.
     *
     * No `watch` here, deliberately: this is the state the app is in on the way
     * up, and the request must still be taken.
     */
    @Test
    fun `a request arriving before there is a window is still answered`() {
        MainWindowRequest.startAcknowledging(directory)

        assertTrue(MainWindowRequest.ask(directory))
    }

    /** And is not dropped on the floor: the window comes up once there is one. */
    @Test
    fun `a request taken before the window raises it once there is one`() {
        MainWindowRequest.startAcknowledging(directory)
        assertTrue(MainWindowRequest.ask(directory))

        val raised = java.util.concurrent.CountDownLatch(1)
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            .launch { MainWindowRequest.watch { raised.countDown() } }
        try {
            assertTrue(
                raised.await(RAISE_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS),
                "the request was acknowledged but never reached the window",
            )
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        val nested = File(directory, "not/created/yet")

        MainWindowRequest.ask(nested)

        assertTrue(nested.isDirectory)
    }

    private companion object {
        const val POLL = 20L
        const val RAISE_TIMEOUT = 5L
    }
}
