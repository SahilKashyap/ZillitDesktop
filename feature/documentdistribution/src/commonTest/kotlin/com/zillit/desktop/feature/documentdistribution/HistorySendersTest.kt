package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.data.DistributionDto
import com.zillit.desktop.feature.documentdistribution.data.readSender
import com.zillit.desktop.feature.documentdistribution.data.senderParam
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.ui.mergedSenders
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The History "Sent by" filter — the web's `historySenders` rules, key for key. */
class HistorySendersTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun dto(body: String) = json.decodeFromString(DistributionDto.serializer(), body)

    @Test
    fun `reads a nested created_by object`() {
        val row = """{"_id":"d1","created_by":{"user_id":"u1","full_name":"Vivek Mishra",""" +
            """"designation":"Line producer"}}"""
        val s = dto(row).readSender()!!
        assertEquals(DistributionSender("u1", "Vivek Mishra", "Line producer"), s)
    }

    @Test
    fun `reads a bare id with the name on a sibling key`() {
        val byCreator = dto("""{"_id":"d","created_by":"u2","created_by_name":"Sahil"}""").readSender()
        assertEquals(DistributionSender("u2", "Sahil"), byCreator)
        val bySentBy = dto("""{"_id":"d","sent_by":"u3","sent_by_name":"Pat"}""").readSender()
        assertEquals(DistributionSender("u3", "Pat"), bySentBy)
    }

    @Test
    fun `accepts sent_by and sender when created_by carries nothing`() {
        assertEquals("u4", dto("""{"_id":"d","sender":{"_id":"u4","name":"Al"}}""").readSender()!!.id)
        val nullCreator = """{"_id":"d","created_by":null,"sent_by":{"user_id":"u9","full_name":"Next"}}"""
        assertEquals("u9", dto(nullCreator).readSender()!!.id, "a null created_by falls through")
        assertNull(dto("""{"_id":"d","subject":"no sender"}""").readSender())
    }

    @Test
    fun `falls back to the email and keeps an id-only sender`() {
        assertEquals("a@b.c", dto("""{"_id":"d","created_by":{"id":"u5","email":"a@b.c"}}""").readSender()!!.name)
        assertEquals(DistributionSender("u6", ""), dto("""{"_id":"d","created_by":"u6"}""").readSender())
    }

    @Test
    fun `the domain row carries the sender id and the name falls back to it`() {
        val row = dto("""{"_id":"d1","created_by":{"user_id":"u1","full_name":"Vivek"}}""").toDomain()!!
        assertEquals("u1", row.senderId)
        assertEquals("Vivek", row.sentByName)
    }

    @Test
    fun `sent_by is comma-joined ids with blanks dropped, or absent`() {
        assertEquals("u1,u2", senderParam(listOf(" u1", "", "u2", "u1")))
        assertNull(senderParam(listOf("", " ")))
    }

    @Test
    fun `the menu merges the endpoint's senders with those the rows name`() {
        val rows = listOf(
            Distribution(id = "d1", subject = "s", senderId = "u2", sentByName = "Bo"),
            Distribution(id = "d2", subject = "s", senderId = "u1", sentByName = "Al (row)"),
        )
        val merged = mergedSenders(listOf(DistributionSender("u1", "Al")), rows)
        assertEquals(listOf("Al", "Bo"), merged.map { it.name })
        assertEquals("AB", DistributionSender("x", "Ann Bee").initials)
    }
}
