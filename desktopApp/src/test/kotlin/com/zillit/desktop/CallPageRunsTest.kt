package com.zillit.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `callengine/call.js` for real, against a stub DOM.
 *
 * Everything else about the call page is checked by reading it — the wire
 * shapes in EngineBridgeTest, the warning contract in
 * ShareWarningContractTest — and that was not enough. A change to the local
 * preview once turned `render()` into `mountTracks()` -> `syncLocalPreview()`
 * -> `clearLocal()` -> `render()`: valid JavaScript, parses clean, and blows
 * the stack on any call whose camera is off. It reached a release, where it
 * looked like calls dropping moments after they were answered.
 *
 * So this executes the page and drives the entry points Kotlin actually calls.
 * It does not assert on what was drawn — there is no layout here — only that
 * driving the page does not throw, which is the class of fault that survives
 * every other check in this repo.
 *
 * Skipped where node is unavailable rather than failing: a machine without it
 * can still build and ship, exactly as one without swiftc can.
 */
class CallPageRunsTest {

    private val node: File? = listOf("/usr/local/bin/node", "/opt/homebrew/bin/node", "/usr/bin/node")
        .map(::File)
        .firstOrNull { it.canExecute() }

    private fun run(page: String, harness: String = "call-page-harness.js"): Pair<Int, String> {
        val runner = requireNotNull(node)
        val process = ProcessBuilder(
            runner.absolutePath,
            File("src/test/resources/$harness").absolutePath,
            File("src/main/resources/callengine/$page").absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    @Test
    fun `the call page survives being driven`() {
        if (node == null) return
        val (exit, output) = run("call.js")

        // The failure this exists for prints "RangeError: Maximum call stack
        // size exceeded" and exits 1.
        assertEquals(0, exit, "call.js threw when driven:\n$output")
        assertTrue(output.contains("without throwing"), output)
    }

    /**
     * Line 3's PRE-WARM bookkeeping, which is all "must not" and all silent.
     *
     * Warm the same ring twice and the connect in flight is torn down, so the
     * answer is slow anyway. Drop the room after the accept and the server
     * reads it as the callee leaving, ending the call they just answered. Fail
     * to adopt it and the second connect collapses the call server-side. None
     * of that is reachable from Kotlin — the logic lives in the page — and none
     * of it shows in a syntax check, so the harness asserts each rule.
     */
    @Test
    fun `the pre-warm rules hold`() {
        if (node == null) return
        val (exit, output) = run("livekit.js", harness = "livekit-page-harness.js")

        assertEquals(0, exit, "livekit.js broke a pre-warm rule:\n$output")
        assertTrue(output.contains("without throwing"), output)
    }
}
