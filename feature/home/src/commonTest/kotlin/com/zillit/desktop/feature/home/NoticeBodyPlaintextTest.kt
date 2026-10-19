package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.feature.home.data.NoticeBody
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bodies that were never encrypted.
 *
 * Not every post on a board is ciphertext: the Production Report chat's record
 * answered thirteen plain ones on develop (2026-10-19), and each drew
 * "[This message could not be decrypted]" over words the phones show. A body
 * the wire's AES/CBC hex cannot have produced is its own text; a well-shaped
 * one that will not decrypt is still a broken post, and still a placeholder.
 */
class NoticeBodyPlaintextTest {

    /** The real engine's answer to a payload that is not hex, and its success otherwise. */
    private val engine = object : NoticeDecryptor {
        var asked = 0

        override fun decryptFromHex(cipherHex: String): ZillitResult<String> {
            asked++
            val hex = cipherHex.length % 2 == 0 &&
                cipherHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            return if (hex) {
                ZillitResult.Success("decrypted:$cipherHex")
            } else {
                ZillitResult.Failure(ZillitError.Crypto("payload is not valid hex"))
            }
        }

        override fun encryptToHex(plaintext: String) = ZillitResult.Success(plaintext)
    }

    private fun repositoryReading(vararg bodies: String): HomeFeedRepositoryImpl {
        val rows = bodies.mapIndexed { i, body ->
            """{"_id":"n$i","message":"$body","name":"AD","created":${1_000 + i},"updated":${1_000 + i}}"""
        }.joinToString(",")
        val mock = MockEngine {
            respond(
                """{"status":1,"data":[$rows]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HomeFeedRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ PlaintextEngineFactory(mock) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            decrypt = engine,
            isAdmin = { false },
        )
    }

    @Test
    fun `a body that cannot be ciphertext is its own text, and is never asked about`() = runTest {
        // A caption, a word that happens to be hex digits, and an id-shaped
        // string: none is a whole number of 16-byte blocks.
        val read = repositoryReading("Day 12 call sheet", "beef", "6a1545b0b6a3f82234c89cd6")

        val bodies = (read.loadNotices("u1", 9_000) as ZillitResult.Success).data.map { it.body }
        assertEquals(setOf("Day 12 call sheet", "beef", "6a1545b0b6a3f82234c89cd6"), bodies.toSet())
        assertEquals(0, engine.asked, "the engine was asked about a body that could not be ciphertext")
    }

    @Test
    fun `a payload in whole blocks still goes through the engine`() = runTest {
        val cipher = "ab".repeat(16) // 32 hex characters: one AES block.
        val read = repositoryReading(cipher)

        val body = (read.loadNotices("u1", 9_000) as ZillitResult.Success).data.single().body
        assertEquals("decrypted:$cipher", body)
        assertEquals(1, engine.asked)
    }

    @Test
    fun `the shape test, as the wire writes it`() {
        assertTrue(NoticeBody.isCipherShaped("ab".repeat(16)))
        assertTrue(NoticeBody.isCipherShaped("AB".repeat(32)), "two blocks, upper case")
        // One hex digit short of a block, a block and a half, and plain words.
        assertFalse(NoticeBody.isCipherShaped("a".repeat(31)))
        assertFalse(NoticeBody.isCipherShaped("a".repeat(48)))
        assertFalse(NoticeBody.isCipherShaped(""))
        assertFalse(NoticeBody.isCipherShaped("Lunch is at the unit base, please RSVP today"))
    }
}

private class PlaintextEngineFactory(private val mock: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = mock
}
