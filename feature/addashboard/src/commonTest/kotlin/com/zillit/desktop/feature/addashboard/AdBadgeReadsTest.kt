package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdBadges
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdViewModel
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
 * Which AD rows a screen reads, and when: the register per artiste, every
 * other page whole, and nothing behind a window nobody is looking at.
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

    private class Repo : AdRepository {
        override suspend fun artistes() = ZillitResult.Success(listOf(Artiste(id = "a1", name = "Ada")))
        override suspend fun verify(artisteId: String) = ZillitResult.Success(Unit)
        override suspend fun block(artisteId: String, reason: String?) = ZillitResult.Success(Unit)
        override suspend fun unblock(artisteId: String) = ZillitResult.Success(Unit)
        override suspend fun deleteArtiste(artisteId: String) = ZillitResult.Success(Unit)
        override suspend fun today(shootDate: Long) = ZillitResult.Success(AdShootDay(id = "d1"))
        override suspend fun shootDays() = ZillitResult.Success(emptyList<AdShootDay>())
        override suspend fun dayList(shootDate: Long) = ZillitResult.Success(emptyList<SupportingArtistDay>())
        override suspend fun addToDay(artisteIds: List<String>, shootDate: Long, callTime: String?) =
            ZillitResult.Success(Unit)
        override suspend fun updateDayEntry(
            id: String,
            callTime: String?,
            wrapTime: String?,
            attendance: AttendanceStatus?,
        ) = ZillitResult.Success(Unit)
        override suspend fun removeFromDay(id: String) = ZillitResult.Success(Unit)
        override suspend fun submitDay(shootDate: Long) = ZillitResult.Success(Unit)
    }

    private fun model(badges: FakeBadges) = AdViewModel(
        repository = Repo(),
        viewer = { AdViewer(userId = "u1", canPost = true, ready = true) },
        now = { NOW },
        badges = badges,
    ).also { it.start() }

    @Test
    fun `the register is read per artiste, never whole`() = runTest(dispatcher) {
        val badges = FakeBadges()
        badges.unit.value = mapOf(AdBadges.REGISTER to 2, AdBadges.QUERY to 1)
        badges.tree.value = mapOf(AdBadges.REGISTER to mapOf("a1" to 1, "a2" to 1))
        val vm = model(badges)
        runCurrent()

        vm.onEvent(AdEvent.Open(AdDestination.Register))
        runCurrent()
        // The queries have no list here to read one from, so they go whole.
        assertEquals(listOf(AdBadges.QUERY), badges.reads)
        assertEquals(1, vm.state.value.artisteUnread("a2"))

        vm.onEvent(AdEvent.SeeArtiste("a1"))
        // Acting on an artiste is looking at them.
        vm.onEvent(AdEvent.Verify("a2"))
        runCurrent()
        assertEquals(listOf(AdBadges.QUERY, "${AdBadges.REGISTER}/a1", "${AdBadges.REGISTER}/a2"), badges.reads)
    }

    @Test
    fun `an artiste with nothing unread sends no read`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        runCurrent()
        vm.onEvent(AdEvent.SeeArtiste("a1"))
        runCurrent()
        assertTrue(badges.reads.isEmpty())
    }

    @Test
    fun `the AD reports clear on the shoot days`() {
        // No Reports page here: the days they report on are the nearest.
        assertTrue(AdBadges.REPORTS in AdDestination.Days.badgeKeys)
        assertTrue(AdBadges.REPORTS in AdDestination.Days.pageReads)
        assertFalse(AdBadges.REGISTER in AdDestination.Register.pageReads)
    }

    @Test
    fun `rows landing behind the window wait for somebody to look`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val vm = model(badges)
        runCurrent()

        badges.unit.value = mapOf(AdBadges.TODAY_LIST to 1, "rates_config_label" to 1)
        runCurrent()
        assertTrue(badges.reads.isEmpty())

        // In front: the page on screen, and the notice-only units no page owns.
        vm.onEvent(AdEvent.WindowFocus(true))
        runCurrent()
        assertEquals(listOf(AdBadges.TODAY_LIST, "rates_config_label"), badges.reads)
    }

    private companion object {
        /** 2026-03-06T10:15Z. */
        const val NOW = 1_772_792_100_000L
    }
}
