package com.zillit.desktop.feature.esignature

import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EsignBadgeLeaf
import com.zillit.desktop.feature.esignature.domain.EsignBadges
import com.zillit.desktop.feature.esignature.domain.EsignUnread
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.ManageOuterTab
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

class EsignUnreadTest {

    private val unread = EsignUnread(
        listOf(
            EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "sent", "env-1", 1),
            EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "completed", "env-1", 1),
            EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "draft", "env-2", 2),
            EsignBadgeLeaf(EsignBadges.UNIT_SIGN, "action_required", "env-3", 1),
        ),
    )

    @Test
    fun `surfaces and buckets sum their own rows`() {
        assertEquals(4, unread.manage)
        assertEquals(1, unread.sign)
        assertEquals(3, unread.manageBucket("sent", "draft"))
        assertEquals(1, unread.manageBucket("completed"))
    }

    @Test
    fun `an envelope on a tab counts only that bucket's rows, never the stage it left`() {
        assertEquals(1, unread.manageEnvelope("env-1", "sent"))
        assertEquals(1, unread.manageEnvelope("env-1", "completed"))
        assertEquals(0, unread.manageEnvelope("env-1", "rejected"))
        assertEquals(1, unread.signEnvelope("env-3"))
        assertEquals(2, unread.envelope("env-1"))
    }

    @Test
    fun `a row naming no envelope counts nowhere, since no read could clear it (ZL-21329)`() {
        val withStray = EsignUnread(
            listOf(
                EsignBadgeLeaf(EsignBadges.UNIT_SIGN, "action_required", "", 3),
                EsignBadgeLeaf(EsignBadges.UNIT_SIGN, "action_required", "env-3", 1),
            ),
        )

        assertEquals(1, withStray.sign)
    }
}

/** An envelope's badge clears when its details open, and only then. */
@OptIn(ExperimentalCoroutinesApi::class)
class EsignReadTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeBadges : EsignBadges {
        val ledger = MutableStateFlow(
            listOf(EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "completed", "env-1", 1)),
        )
        val reads = mutableListOf<Pair<String, String>>()
        override val leaves: Flow<List<EsignBadgeLeaf>> = ledger
        override fun readEnvelope(unit: String, envelopeId: String) {
            reads += unit to envelopeId
        }
    }

    @Test
    fun `entering the Completed tab reads nothing, opening the envelope reads it`() = runTest(dispatcher) {
        val repo = FakeEsignRepo().also { it.sentBuckets = mapOf("completed" to listOf(Envelope(id = "env-1"))) }
        val badges = FakeBadges()
        val model = testModel(repo, badges = badges)
        model.start()
        runCurrent()

        model.onEvent(EsignEvent.SwitchOuterTab(ManageOuterTab.Completed))
        runCurrent()
        badges.ledger.value = badges.ledger.value + EsignBadgeLeaf(EsignBadges.UNIT_MANAGE, "completed", "env-1", 1)
        runCurrent()
        assertEquals(emptyList(), badges.reads, "a tab is never read whole — the row pill must survive")

        model.onEvent(EsignEvent.OpenDetail("env-1"))
        runCurrent()
        assertEquals(listOf(EsignBadges.UNIT_MANAGE to "env-1"), badges.reads)
    }
}
