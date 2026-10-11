package com.zillit.desktop.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app-wide list the Uploads tray reads, and the controls it hands back. */
class MediaSendTrackerTest {

    private fun send(id: String) = TrackedSend(id, "p1", "Feature Film", "Aisha", "$id.mov")

    @Test
    fun `a send moves through its phases and finishes`() {
        val tracker = MediaSendTracker()
        tracker.begin(send("a"), cancel = {})
        tracker.progress("a", UploadProgress.compressing(30))
        assertEquals(UploadProgress.compressing(30), tracker.sends.value.single().progress)
        tracker.finished("a")
        assertEquals(SendStatus.Done, tracker.sends.value.single().status)
        tracker.clearFinished()
        assertTrue(tracker.sends.value.isEmpty())
    }

    @Test
    fun `cancel stops the work and takes the row off`() {
        val tracker = MediaSendTracker()
        var cancelled = false
        tracker.begin(send("a"), cancel = { cancelled = true })
        tracker.cancel("a")
        assertTrue(cancelled)
        assertTrue(tracker.sends.value.isEmpty())
    }

    @Test
    fun `a failed send retries through its sender and runs again`() {
        val tracker = MediaSendTracker()
        var retried = 0
        tracker.begin(send("a"), cancel = {}, retry = { retried++ })
        tracker.failed("a")
        assertTrue(tracker.canRetry("a"))
        tracker.retry("a")
        assertEquals(1, retried)
        tracker.progress("a", 10)
        assertEquals(SendStatus.Running, tracker.sends.value.single().status)
    }

    @Test
    fun `newest first, and starting again does not duplicate the row`() {
        val tracker = MediaSendTracker()
        tracker.begin(send("a"), cancel = {})
        tracker.begin(send("b"), cancel = {})
        tracker.begin(send("a"), cancel = {})
        assertEquals(listOf("a", "b"), tracker.sends.value.map { it.id })
        tracker.dismiss("b")
        assertFalse(tracker.canRetry("b"))
    }
}
