package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore
import com.zillit.desktop.feature.auth.data.AuthRepositoryImpl
import com.zillit.desktop.feature.auth.domain.DeviceIdentity
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Signing out has to finish even though it takes down whoever asked for it.
 *
 * The rail's Logout ran [AuthRepositoryImpl.signOut] on the signed-in shell's
 * scope, and a null session takes that shell off screen. When the frame won
 * the race, the keychain clear resumed into a cancelled coroutine and the
 * local wipe never ran: the old account's tokens and saved project list
 * survived, and the next QR sign-in listed — and opened — the previous
 * person's productions (prod, 2026-10-11).
 */
class SignOutTest {

    private val engine = MockEngine {
        respond(
            """{"status":1,"message":"ok","data":{}}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun `the local wipe still runs when signing out cancels the caller`() = runTest {
        val keychainCleared = CompletableDeferred<Unit>()
        var wiped = false
        val auth = AuthRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ Factory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            secureStore = SlowClearStore(keychainCleared),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            onSignOut = { wiped = true },
        )
        auth.rememberDevice(DeviceIdentity(deviceId = "device-1", email = "a@zillit.com", isPrimary = false))

        val shell = launch { auth.signOut() }
        // The session going null is what takes the shell off screen; the
        // keychain is still being cleared at that moment.
        auth.session.first { it == null }
        shell.cancel()
        keychainCleared.complete(Unit)
        shell.join()

        assertTrue(wiped, "the old account's tokens, list and caches are wiped")
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    /** A keychain whose clear takes a while, as deleting each entry does on macOS. */
    private class SlowClearStore(private val cleared: CompletableDeferred<Unit>) : SecureStore {
        private val entries = mutableMapOf<SecureKey, ByteArray>()
        override suspend fun get(key: SecureKey): ZillitResult<ByteArray?> = ZillitResult.Success(entries[key])
        override suspend fun put(key: SecureKey, value: ByteArray): ZillitResult<Unit> {
            entries[key] = value
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(key: SecureKey): ZillitResult<Unit> {
            entries.remove(key)
            return ZillitResult.Success(Unit)
        }

        override suspend fun clear(): ZillitResult<Unit> {
            cleared.await()
            entries.clear()
            return ZillitResult.Success(Unit)
        }

        override suspend fun isAvailable(): Boolean = true
    }
}
