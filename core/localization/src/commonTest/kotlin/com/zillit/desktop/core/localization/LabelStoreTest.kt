package com.zillit.desktop.core.localization

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store's refresh behaviour, with no cache.
 *
 * `cache = null` is a documented state (the database may not open), and it is
 * also the honest way to test the network half in common code — the disk half
 * is [com.zillit.desktop.core.database.LabelCache]'s own test, against real
 * SQLite rather than a fake.
 */
class LabelStoreTest {

    /** Records what was asked for, and answers from a script. */
    private class FakeSource(
        private val answers: MutableMap<Pair<LabelKind, String>, ZillitResult<Map<String, String>>>,
    ) : LabelSource {
        val asked = mutableListOf<Pair<LabelKind, String>>()

        override suspend fun fetch(kind: LabelKind, language: String): ZillitResult<Map<String, String>> {
            asked += kind to language
            return answers[kind to language] ?: ZillitResult.Success(emptyMap())
        }
    }

    private fun ok(vararg pairs: Pair<String, String>) = ZillitResult.Success(pairs.toMap())
    private val offline = ZillitResult.Failure(ZillitError.NoConnection("no route to host"))

    @AfterTest
    fun tearDown() {
        // The global is process-wide; a test that installs must not leak into
        // the next one.
        Labels.reset()
    }

    @Test
    fun `fetches all three dictionaries for the language`() = runTest {
        val source = FakeSource(mutableMapOf())
        LabelStore(source, cache = null).refresh("fr")

        assertEquals(
            listOf(LabelKind.Labels, LabelKind.Messages, LabelKind.Identifiers).map { it to "fr" },
            source.asked,
        )
    }

    @Test
    fun `a blank language falls back to en rather than asking for nothing`() = runTest {
        val source = FakeSource(mutableMapOf())
        LabelStore(source, cache = null).refresh("")

        assertTrue(source.asked.all { it.second == "en" })
    }

    @Test
    fun `one dictionary failing does not cost the others`() = runTest {
        // The failure that makes a half-translated screen instead of a fully
        // stale one, if the three were fetched as a unit.
        val source = FakeSource(
            mutableMapOf(
                (LabelKind.Labels to "en") to ok("call_sheet_label" to "Call Sheet"),
                (LabelKind.Messages to "en") to offline,
                (LabelKind.Identifiers to "en") to ok("weather_tool" to "Forecast"),
            ),
        )
        val store = LabelStore(source, cache = null)

        store.refresh("en")

        assertEquals("Call Sheet", store.translate("call_sheet_label"))
        assertEquals("Forecast", store.translate("weather_tool"))
    }

    @Test
    fun `an empty response does not blank what is already loaded`() = runTest {
        val answers = mutableMapOf<Pair<LabelKind, String>, ZillitResult<Map<String, String>>>(
            (LabelKind.Labels to "en") to ok("call_sheet_label" to "Call Sheet"),
        )
        val store = LabelStore(FakeSource(answers), cache = null)
        store.refresh("en")

        // A dictionary that comes back empty is a bad response far more often
        // than a real emptying — honouring it would blank the whole UI.
        answers[LabelKind.Labels to "en"] = ZillitResult.Success(emptyMap())
        store.refresh("en")

        assertEquals("Call Sheet", store.translate("call_sheet_label"))
    }

    @Test
    fun `a failed refresh leaves the previous translations standing`() = runTest {
        val answers = mutableMapOf<Pair<LabelKind, String>, ZillitResult<Map<String, String>>>(
            (LabelKind.Labels to "en") to ok("call_sheet_label" to "Call Sheet"),
        )
        val store = LabelStore(FakeSource(answers), cache = null)
        store.refresh("en")

        answers[LabelKind.Labels to "en"] = offline
        store.refresh("en")

        assertEquals("Call Sheet", store.translate("call_sheet_label"))
    }

    @Test
    fun `switching language replaces the words`() = runTest {
        val source = FakeSource(
            mutableMapOf(
                (LabelKind.Labels to "en") to ok("call_sheet_label" to "Call Sheet"),
                (LabelKind.Labels to "fr") to ok("call_sheet_label" to "Feuille de service"),
            ),
        )
        val store = LabelStore(source, cache = null)

        store.refresh("en")
        assertEquals("Call Sheet", store.translate("call_sheet_label"))

        store.refresh("fr")
        assertEquals("Feuille de service", store.translate("call_sheet_label"))
    }

    @Test
    fun `unresolved keys are humanised before the first fetch returns`() = runTest {
        val store = LabelStore(FakeSource(mutableMapOf()), cache = null)

        // Nothing loaded, nothing installed — and still no raw key on screen.
        assertEquals("Call Sheet", store.translate("call_sheet_label"))
    }

    @Test
    fun `installing publishes the store to the global reader`() = runTest {
        val source = FakeSource(
            mutableMapOf((LabelKind.Labels to "fr") to ok("call_sheet_label" to "Feuille de service")),
        )
        val store = LabelStore(source, cache = null)
        Labels.install(store.dictionary)

        // Installed but not yet loaded: the extension answers from the fallback
        // rather than reading through an empty slot.
        assertEquals("Call Sheet", "call_sheet_label".localised())

        // The install captured the store's flow, not a snapshot of it, so the
        // extension sees the fetch without being re-installed.
        store.refresh("fr")
        assertEquals("Feuille de service", "call_sheet_label".localised())
    }

    @Test
    fun `the global falls back to humanising when nothing is installed`() {
        Labels.reset()

        assertEquals("Call Sheet", "call_sheet_label".localised())
        assertEquals("Transportation", "transportation_tool".localisedMessage())
    }
}
