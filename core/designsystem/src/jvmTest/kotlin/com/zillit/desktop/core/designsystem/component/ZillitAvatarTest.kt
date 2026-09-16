package com.zillit.desktop.core.designsystem.component

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The one rule: initials are the fallback for a missing picture, never what
 * a screen shows because it forgot to ask for one.
 */
@OptIn(ExperimentalTestApi::class)
class ZillitAvatarTest {

    private val picture = ImageBitmap(4, 4)

    @Test
    fun `a user id fetches the picture through the loader in scope`() {
        val asked = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    ProvideAvatarLoader({ id -> asked += id; picture }) {
                        ZillitAvatar(name = "Aisha Khan", userId = "u1")
                    }
                }
            }
            waitForIdle()
            waitUntil { asked.isNotEmpty() }
            waitForIdle()

            assertEquals(listOf("u1"), asked)
            onNodeWithText("AK").assertDoesNotExist()
        }
    }

    @Test
    fun `initials show for someone without a picture`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    ProvideAvatarLoader({ null }) {
                        ZillitAvatar(name = "Aisha Khan", userId = "u1")
                    }
                }
            }
            waitForIdle()

            onNodeWithText("AK").assertIsDisplayed()
        }
    }

    @Test
    fun `initials show with no loader in scope`() {
        // Previews and render tests compose without a host.
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { ZillitAvatar(name = "Aisha Khan", userId = "u1") }
            }
            waitForIdle()

            onNodeWithText("AK").assertIsDisplayed()
        }
    }

    @Test
    fun `a handed-in image wins over the loader`() {
        var asked = false
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    ProvideAvatarLoader({ asked = true; null }) {
                        ZillitAvatar(name = "Aisha Khan", userId = "u1", image = picture)
                    }
                }
            }
            waitForIdle()

            assertEquals(false, asked)
            onNodeWithText("AK").assertDoesNotExist()
        }
    }

    @Test
    fun `the caching loader fetches one person once and shares the answer`() = runBlocking {
        var fetches = 0
        val loader = CachingAvatarLoader { fetches++; picture }

        val first = loader.load("u1")
        val second = loader.load("u1")

        assertSame(first, second)
        assertEquals(1, fetches)
        loader.close()
    }

    @Test
    fun `the caching loader does not remember a missing picture`() = runBlocking {
        // A failed fetch and "never uploaded one" look alike; remembering
        // the first would leave a face missing for the session.
        var fetches = 0
        val loader = CachingAvatarLoader { fetches++; null }

        loader.load("u1")
        loader.load("u1")

        assertEquals(2, fetches)
        loader.close()
    }

    @Test
    fun `rows asking at the same time share one in-flight fetch`() = runBlocking {
        val gate = CompletableDeferred<ImageBitmap?>()
        var fetches = 0
        val loader = CachingAvatarLoader { fetches++; gate.await() }

        val a = async { loader.load("u1") }
        val b = async { loader.load("u1") }
        gate.complete(picture)

        assertSame(a.await(), b.await())
        assertEquals(1, fetches)
        loader.close()
    }
}
