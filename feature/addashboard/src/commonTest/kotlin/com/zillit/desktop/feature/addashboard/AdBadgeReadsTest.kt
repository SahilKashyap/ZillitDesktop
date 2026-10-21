package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteQuery
import com.zillit.desktop.feature.addashboard.ui.AdBadges
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdViewModel
import com.zillit.desktop.feature.addashboard.ui.QueryEvent
import com.zillit.desktop.feature.addashboard.ui.RegisterEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which AD rows a screen reads, and when: the register and the queries per
 * row, every other page whole, and nothing behind a window nobody is looking
 * at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdBadgeReadsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A ledger the test fills by hand, and the reads the dashboard sent it. */
    private class FakeBadges : TabBadgeSource {
        val unit = MutableStateFlow<Map<String, Int>>(emptyMap())
        val tree = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
        val reads = mutableListOf<String>()
        override val counts: Flow<Map<String, Int>> = unit
        override val entityCounts: Flow<Map<String, Map<String, Int>>> = tree
        override fun read(key: String) {
            reads += key
        }
        override fun readEntity(key: String, entityId: String, kind: String?) {
            reads += "$key/$entityId"
        }
    }

    private fun repo() = FakeAdRepository().apply {
        dayHeader = AdShootDay(id = "d1")
        artisteRows = listOf(Artiste(id = "a1", name = "Ada"))
        queryRows = listOf(ArtisteQuery(id = "q1", artisteName = "Ada"))
    }

    private fun model(badges: FakeBadges, repo: FakeAdRepository = repo()) = AdViewModel(
        repository = repo,
        badges = badges,
        now = { NOW },
        viewer = { AdViewer(userId = "u1", canPost = true, ready = true) },
    ).also { it.start() }

    /**
     * The register badges per artiste.
     *
     * Opening the tab used to clear every dot at once, which left the
     * sidebar's rollup counting nothing — so the row is the read, not the tab.
     */
    @Test
    fun `the register is read per artiste, never whole`() = runTest(dispatcher) {
        val badges = FakeBadges()
        badges.unit.value = mapOf(AdBadges.REGISTER to 2)
        badges.tree.value = mapOf(AdBadges.REGISTER to mapOf("a1" to 1, "a2" to 1))
        val vm = model(badges)
        runCurrent()

        vm.onEvent(AdEvent.Open(AdDestination.Register))
        runCurrent()
        assertTrue(badges.reads.isEmpty(), "the tab itself reads nothing: ${badges.reads}")
        assertEquals(1, vm.state.value.artisteUnread("a2"))

        vm.onEvent(RegisterEvent.SeeArtiste("a1"))
        vm.onEvent(RegisterEvent.OpenArtiste("a2"))
        runCurrent()
        assertEquals(
            listOf("${AdBadges.REGISTER}/a1", "${AdBadges.REGISTER}/a2"),
            badges.reads,
        )
    }

    /** The queries badge per thread too, for the same reason. */
    @Test
    fun `a query is read when its thread is opened`() = runTest(dispatcher) {
        val badges = FakeBadges()
        badges.unit.value = mapOf(AdBadges.QUERY to 1)
        badges.tree.value = mapOf(AdBadges.QUERY to mapOf("q1" to 1))
        val vm = model(badges)
        runCurrent()

        vm.onEvent(AdEvent.Open(AdDestination.Queries))
        runCurrent()
        assertTrue(badges.reads.isEmpty(), "the tab itself reads nothing: ${badges.reads}")

        vm.onEvent(QueryEvent.OpenThread("q1"))
        runCurrent()
        assertEquals(listOf("${AdBadges.QUERY}/q1"), badges.reads)
    }

    @Test
    fun `an artiste with nothing unread sends no read`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        runCurrent()
        vm.onEvent(RegisterEvent.SeeArtiste("a1"))
        runCurrent()
        assertTrue(badges.reads.isEmpty())
    }

    /**
     * Each badged page owns its unit, and the two per-row ones are left out of
     * the page read — the web's `AD_UNIT_BY_NAV` against its `AD_ITEM_UNITS`.
     */
    @Test
    fun `each unit belongs to the page that shows it`() {
        assertEquals(listOf(AdBadges.TODAY_LIST), AdDestination.TodaysList.badgeKeys)
        assertEquals(listOf(AdBadges.ADD_EXTRAS), AdDestination.AddExtras.badgeKeys)
        assertEquals(listOf(AdBadges.REPORTS), AdDestination.WrapReport.badgeKeys)
        assertEquals(listOf(AdBadges.SHOOT_SCHEDULE), AdDestination.Schedule.badgeKeys)
        // Exhibit G has no unit of its own on any client.
        assertTrue(AdDestination.ExhibitG.badgeKeys.isEmpty())

        // Read whole on entry…
        assertEquals(listOf(AdBadges.TODAY_LIST), AdDestination.TodaysList.pageReads)
        // …except the two that read per row.
        assertTrue(AdDestination.Register.pageReads.isEmpty())
        assertTrue(AdDestination.Queries.pageReads.isEmpty())
    }

    /** The level marker the backend stores a tab-level read under. */
    @Test
    fun `a tab read carries the marker the backend files it under`() {
        assertEquals("level_3" to "today", AdDestination.TodaysList.readLevel)
        assertEquals("level_3" to "tomorrow", AdDestination.AddExtras.readLevel)
        assertEquals("level_2" to "report", AdDestination.WrapReport.readLevel)
        // Unit-scoped: the schedule sends no marker.
        assertEquals(null, AdDestination.Schedule.readLevel)
    }

    @Test
    fun `rows landing behind the window wait for somebody to look`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        runCurrent()

        vm.onEvent(AdEvent.Open(AdDestination.TodaysList))
        runCurrent()
        badges.reads.clear()

        badges.unit.value = mapOf(AdBadges.TODAY_LIST to 1, "rates_config_label" to 1)
        runCurrent()
        assertTrue(badges.reads.isEmpty())

        // In front: the page on screen, and the notice-only units no page owns.
        vm.onEvent(AdEvent.WindowFocus(true))
        runCurrent()
        assertEquals(listOf(AdBadges.TODAY_LIST, "rates_config_label"), badges.reads)
    }

    /** The dashboard opens on Wrap Report, as the web's bare path has since 2026-07-08. */
    @Test
    fun `the landing page is the wrap report`() = runTest(dispatcher) {
        val vm = model(FakeBadges())
        runCurrent()

        assertEquals(AdDestination.WrapReport, vm.state.value.destination)
        assertFalse(AdDestination.WrapReport.postingOnly)
    }

    private companion object {
        /** 2026-03-06T10:15Z. */
        const val NOW = 1_772_792_100_000L
    }
}
