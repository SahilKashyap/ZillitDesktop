package com.zillit.desktop.core.localization

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gate that keeps the first fetch from going out without a device id.
 *
 * Regression cover for a bug seen against the live dev server: the first
 * dictionary of every launch came back `406 libs_module_data_invalid`, because
 * the request beat the keychain restore that supplies the id.
 */
class LabelRefreshTriggerTest {

    /** Collects the trigger on the background scope for the test's lifetime. */
    private fun TestScope.emissionsOf(
        language: MutableStateFlow<String>,
        deviceId: MutableStateFlow<String?>,
    ): List<String> {
        val seen = mutableListOf<String>()
        backgroundScope.launchCollect(labelRefreshTrigger(language, deviceId), seen)
        runCurrent()
        return seen
    }

    @Test
    fun `nothing is fetched until a device id exists`() = runTest {
        val language = MutableStateFlow("en")
        val deviceId = MutableStateFlow<String?>(null)
        val seen = emissionsOf(language, deviceId)

        assertEquals(emptyList(), seen, "fetched before the device was known")

        deviceId.value = "device-abc"
        runCurrent()

        assertEquals(listOf("en"), seen)
    }

    @Test
    fun `an empty device id counts as absent`() = runTest {
        // The header context starts with "" rather than null, and that is the
        // exact value that produced the 406.
        val seen = emissionsOf(MutableStateFlow("en"), MutableStateFlow(""))

        assertEquals(emptyList(), seen)
    }

    @Test
    fun `a language change refetches`() = runTest {
        val language = MutableStateFlow("en")
        val seen = emissionsOf(language, MutableStateFlow("device-abc"))

        language.value = "fr"
        runCurrent()

        assertEquals(listOf("en", "fr"), seen)
    }

    @Test
    fun `relinking the machine does not refetch the same language`() = runTest {
        val deviceId = MutableStateFlow<String?>("device-abc")
        val seen = emissionsOf(MutableStateFlow("en"), deviceId)

        // A new id for the same machine. The dictionary is already loaded and
        // unchanged; re-fetching it would be work for nothing.
        deviceId.value = "device-xyz"
        runCurrent()

        assertEquals(listOf("en"), seen)
    }

    @Test
    fun `the language chosen while unlinked is the one fetched`() = runTest {
        val language = MutableStateFlow("en")
        val deviceId = MutableStateFlow<String?>(null)
        val seen = emissionsOf(language, deviceId)

        // Someone switches language on the sign-in screen, then links.
        language.value = "fr"
        runCurrent()
        deviceId.value = "device-abc"
        runCurrent()

        assertEquals(listOf("fr"), seen, "should fetch the chosen language, once")
    }

    @Test
    fun `losing and regaining the device does not refetch`() = runTest {
        val deviceId = MutableStateFlow<String?>("device-abc")
        val seen = emissionsOf(MutableStateFlow("en"), deviceId)

        deviceId.value = null
        runCurrent()
        deviceId.value = "device-abc"
        runCurrent()

        assertEquals(listOf("en"), seen)
    }
}

private fun CoroutineScope.launchCollect(flow: Flow<String>, into: MutableList<String>) {
    launch { flow.collect { into += it } }
}
