package com.zillit.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The share-failure warnings the pages send, against the ones Kotlin listens
 * for.
 *
 * A unit test cannot run call.js, but it can read it. This exists because the
 * two sides drifted silently once already: Kotlin listened for
 * "screen-share" and "start-screen-share", the pages emitted neither, and the
 * result was a screen share that failed without a word to the user on the
 * default line. Nothing but a check like this notices that — both sides
 * compile, both sides look right, and the string never matches.
 */
class ShareWarningContractTest {

    private fun page(name: String): String =
        File("src/main/resources/callengine/$name").readText()

    @Test
    fun `the Agora page reports its share failures under a step Kotlin acts on`() {
        val source = page("call.js")

        // warn() must send the step as its own field, not only inside prose.
        assertTrue(
            source.contains("type: 'warning', where: context"),
            "call.js warn() no longer sends a `where`; the share banner cannot fire without it",
        )
        // And the step it reports for a failed share must be one we match on.
        assertTrue(
            source.contains("warn('startScreenShare'"),
            "call.js no longer reports a failed share as 'startScreenShare'",
        )
    }

    @Test
    fun `the mediasoup page reports its share failures under a step Kotlin acts on`() {
        assertTrue(
            page("mediasoup.js").contains("warn('produce-screen'"),
            "mediasoup.js no longer reports a failed share as 'produce-screen'",
        )
    }

    @Test
    fun `a failed share restores the camera it unpublished`() {
        val source = page("call.js")
        val start = source.substringAfter("async startScreenShare(sourceId)")
            .substringBefore("async stopScreenShare()")
        val recovery = start.substringAfter("} catch (e) {")

        // The camera is unpublished before the publish that can fail. Without
        // these two, a rejected publish costs the camera for the rest of the
        // call and leaves the capture running behind a UI that says it stopped.
        assertTrue(recovery.contains("screenTrack.close()"), "a failed share leaks its capture")
        assertTrue(recovery.contains("client.publish(camTrack)"), "a failed share loses the camera")
    }
}
