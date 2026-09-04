package com.zillit.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shortcut path each widget answers to.
 *
 * The failure this guards against is quiet: two widgets sharing a flag, a URI
 * host or a marker file would send every shortcut to whichever one the
 * iteration happened to reach first, and nothing would say so.
 */
class WidgetLaunchTest {

    @Test
    fun `every widget has its own flag, url and marker`() {
        val widgets = ZillitWidget.entries

        assertEquals(widgets.size, widgets.map { it.flag }.distinct().size, "flags collide")
        assertEquals(widgets.size, widgets.map { it.uriHost }.distinct().size, "urls collide")
        assertEquals(widgets.size, widgets.map { it.marker }.distinct().size, "markers collide")
        assertEquals(widgets.size, widgets.map { it.keys.open.name }.distinct().size, "preferences collide")
    }

    @Test
    fun `the command line names one widget, or none`() {
        assertEquals(ZillitWidget.Drive, WidgetLaunch.requestedBy(arrayOf("--drive-widget")))
        assertEquals(ZillitWidget.Chat, WidgetLaunch.requestedBy(arrayOf("--chat-widget")))
        assertEquals(ZillitWidget.Crew, WidgetLaunch.requestedBy(arrayOf("--crew-widget")))
        // The background flag is not a widget: starting hidden at login must
        // not also open a window, which is the whole point of starting hidden.
        assertNull(WidgetLaunch.requestedBy(arrayOf(BackgroundLaunch.FLAG)))
        assertNull(WidgetLaunch.requestedBy(emptyArray()))
    }

    /**
     * Real time, not `runTest`'s virtual clock: the watcher checks the file on
     * `Dispatchers.IO`, and a virtual timeout races past that dispatch and
     * cancels before the marker is ever read.
     */
    @Test
    fun `a second launch signals the running app, which sees it once`() = runBlocking {
        val directory = Files.createTempDirectory("widget-launch").toFile()

        WidgetLaunch.signalRunningApp(ZillitWidget.Chat, directory)
        assertTrue(File(directory, ZillitWidget.Chat.marker).exists())

        // `watch` never returns by design, so it runs as a job the first
        // sighting ends.
        val seen = CompletableDeferred<ZillitWidget>()
        val watcher = launch(Dispatchers.Default) { WidgetLaunch.watch(directory) { seen.complete(it) } }
        val opened = withTimeoutOrNull(WATCH_MILLIS) { seen.await() }
        watcher.cancel()

        assertEquals(ZillitWidget.Chat, opened, "the marker opened the wrong widget")
        // Consumed, so the widget does not reopen itself on every poll after
        // the user closes it.
        assertFalse(File(directory, ZillitWidget.Chat.marker).exists(), "the marker outlived its request")
    }

    private companion object {
        /** Long enough for several of the watcher's one-second polls. */
        const val WATCH_MILLIS = 3_000L
    }
}
