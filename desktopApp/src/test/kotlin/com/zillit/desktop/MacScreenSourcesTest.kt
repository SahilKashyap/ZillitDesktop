package com.zillit.desktop

import com.zillit.desktop.feature.calls.domain.ShareSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract with `zillit-capture`.
 *
 * Driven through a stub helper rather than the real one: the real one needs
 * Screen Recording permission and a window server, and what is being pinned
 * here is the shape of the conversation, not ScreenCaptureKit.
 */
class MacScreenSourcesTest {

    private fun helper(script: String): File {
        val file = File.createTempFile("zillit-capture-stub", ".sh")
        file.writeText("#!/bin/sh\n$script\n")
        file.setExecutable(true)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `the list arrives first and the pictures fill in behind it`() = runTest {
        val stub = helper(
            """
            echo '{"type":"sources","displays":[{"id":"screen:1:0","name":"Entire screen","width":1710,"height":1112}],"windows":[{"id":"window:187:0","app":"Google Chrome","title":"Zillit","width":1200,"height":800}]}'
            echo '{"type":"thumb","id":"screen:1:0","png":"AAAA"}'
            echo '{"type":"done"}'
            """.trimIndent(),
        )

        val emissions = MacScreenSources(stub).list().toList()

        // Two emissions, not one: the picker draws its tiles the moment the
        // list lands and fills the pictures in as each capture completes,
        // rather than showing nothing until the slowest one finishes.
        assertEquals(2, emissions.size)
        assertEquals(listOf("screen:1:0", "window:187:0"), emissions.first().map(ShareSource::id))
        assertTrue(emissions.first().all { it.previewPng.isEmpty() })
        assertEquals("AAAA", emissions.last().first { it.id == "screen:1:0" }.previewPng)
        // A picture landing for one source must not blank the others.
        assertEquals("window:187:0", emissions.last().last().id)
    }

    @Test
    fun `a screen and a window are told apart`() = runTest {
        val stub = helper(
            """
            echo '{"type":"sources","displays":[{"id":"screen:1:0","name":"Entire screen"}],"windows":[{"id":"window:9:0","app":"Finder","title":"Applications"}]}'
            echo '{"type":"done"}'
            """.trimIndent(),
        )

        val sources = MacScreenSources(stub).list().toList().last()

        val screen = sources.first { it.isScreen }
        val window = sources.first { !it.isScreen }
        assertEquals("Entire screen", screen.name)
        // The caption is what the tile says underneath: an application name
        // for a window, since two windows called "Untitled" are told apart by
        // whose they are.
        assertEquals("Entire screen", screen.caption)
        assertEquals("Applications", window.name)
        assertEquals("Finder", window.caption)
    }

    @Test
    fun `a helper that fails reports nothing to share rather than hanging`() = runTest {
        val stub = helper("echo 'zillit-capture: cannot list shareable content' >&2\nexit 2")

        val emissions = MacScreenSources(stub).list().toList()

        // Empty is the signal the caller acts on — it falls back to sharing
        // the whole screen instead of opening a picker with nothing in it.
        assertEquals(listOf(emptyList<ShareSource>()), emissions)
    }

    @Test
    fun `junk on the helper's stdout is skipped, not fatal`() = runTest {
        val stub = helper(
            """
            echo 'not json at all'
            echo '{"type":"sources","displays":[{"id":"screen:1:0","name":"Entire screen"}],"windows":[]}'
            echo '{"type":"surprise"}'
            echo '{"type":"done"}'
            """.trimIndent(),
        )

        val sources = MacScreenSources(stub).list().toList().last()

        assertEquals(listOf("screen:1:0"), sources.map(ShareSource::id))
    }
}
