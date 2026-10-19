package com.zillit.desktop

import com.zillit.desktop.core.appupdate.UpdateStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a Remote Config publish does to the update banner.
 *
 * The valuable half of this is the second test. A publish is also what the
 * console does when somebody edits an unrelated key, and a fetch that comes
 * back [UpdateStatus.Unknown] — offline, refused, no desktop keys — must never
 * be allowed to clear a banner that was right.
 */
class UpdateOnPublishTest {

    private val available = UpdateStatus.Available("1.2.0", null)

    @Test
    fun `a publish asks again and shows the answer`() = runTest {
        val published = MutableSharedFlow<Long>()
        val status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
        var asked = 0

        backgroundScope.launch {
            checkOnConfigPublish(published, { asked++; available }, status)
        }
        runCurrent()
        published.emit(1083)
        runCurrent()

        assertEquals(1, asked)
        assertEquals(available, status.value)
    }

    @Test
    fun `an unclear answer leaves the banner what it knew`() = runTest {
        val published = MutableSharedFlow<Long>()
        val status = MutableStateFlow<UpdateStatus>(available)

        backgroundScope.launch {
            checkOnConfigPublish(published, { UpdateStatus.Unknown }, status)
        }
        runCurrent()
        published.emit(1084)
        runCurrent()

        assertEquals(available, status.value)
    }

    @Test
    fun `up to date is a clear answer and does take the banner down`() = runTest {
        val published = MutableSharedFlow<Long>()
        val status = MutableStateFlow<UpdateStatus>(available)

        backgroundScope.launch {
            checkOnConfigPublish(published, { UpdateStatus.UpToDate }, status)
        }
        runCurrent()
        published.emit(1084)
        runCurrent()

        assertEquals(UpdateStatus.UpToDate, status.value)
    }

    @Test
    fun `every publish is its own question`() = runTest {
        val published = MutableSharedFlow<Long>()
        val status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
        var asked = 0

        backgroundScope.launch {
            checkOnConfigPublish(published, { asked++; UpdateStatus.UpToDate }, status)
        }
        runCurrent()
        published.emit(1083)
        published.emit(1084)
        runCurrent()

        assertEquals(2, asked)
    }

    /** Nothing published, nothing asked: the banner's own poll owns that. */
    @Test
    fun `no publish is no question`() = runTest {
        val status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
        var asked = 0

        backgroundScope.launch {
            checkOnConfigPublish(MutableSharedFlow(), { asked++; available }, status)
        }
        runCurrent()

        assertEquals(0, asked)
        assertEquals(UpdateStatus.Unknown, status.value)
    }
}
