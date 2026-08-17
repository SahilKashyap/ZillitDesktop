package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.currentPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the **real** OS credential store.
 *
 * Uses a test-only service name so it can never touch the entries the running
 * app owns, and cleans up after itself.
 *
 * On a machine with no usable backend — a headless Linux CI runner with no
 * Secret Service, most likely — these skip rather than fail, and
 * `the backend reports itself unavailable` records that explicitly. That is a
 * deliberate trade: failing the build on every headless runner would train
 * people to ignore it, but a silent skip with no signal would hide a real
 * regression. The Linux packaging story (plan §1) has to answer what happens
 * when there is no keyring daemon.
 */
class KeychainSecureStoreTest {

    private val service = "Zillit Desktop — test ${currentPlatform().os}"
    private val store = KeychainSecureStore(service = service)

    @AfterTest
    fun cleanUp() = runTest {
        runCatching { store.clear() }
    }

    @Test
    fun `round-trips a secret through the OS store`() = runTest {
        if (skipIfUnavailable()) return@runTest

        val secret = ByteArray(32) { it.toByte() }

        assertIsSuccess(store.put(SecureKey.DatabaseKey, secret))
        val read = store.get(SecureKey.DatabaseKey).expectSuccess()

        assertNotNull(read, "the secret was not stored")
        assertContentEquals(secret, read)
    }

    @Test
    fun `binary values survive intact`() = runTest {
        if (skipIfUnavailable()) return@runTest

        // The backing API is String-based, so arbitrary bytes go through
        // Base64. A key with embedded nulls and high bytes is exactly what a
        // naive UTF-8 round-trip would corrupt.
        val secret = ByteArray(32) { (it * 7 % 256 - 128).toByte() }.also {
            it[0] = 0
            it[1] = -1
        }

        assertIsSuccess(store.put(SecureKey.DeviceKey, secret))

        assertContentEquals(secret, store.get(SecureKey.DeviceKey).expectSuccess())
    }

    @Test
    fun `a missing entry reads as null rather than failing`() = runTest {
        if (skipIfUnavailable()) return@runTest

        // First launch takes this path for every key; it is not an error.
        assertNull(store.get(SecureKey.RefreshToken).expectSuccess())
    }

    @Test
    fun `writing twice replaces rather than duplicating`() = runTest {
        if (skipIfUnavailable()) return@runTest

        store.put(SecureKey.AccessToken, "first".encodeToByteArray())
        store.put(SecureKey.AccessToken, "second".encodeToByteArray())

        assertEquals("second", store.get(SecureKey.AccessToken).expectSuccess()?.decodeToString())
    }

    @Test
    fun `delete removes the entry and is idempotent`() = runTest {
        if (skipIfUnavailable()) return@runTest

        store.put(SecureKey.AccessToken, "token".encodeToByteArray())
        assertIsSuccess(store.delete(SecureKey.AccessToken))
        assertNull(store.get(SecureKey.AccessToken).expectSuccess())

        // Deleting again must not fail — sign-out runs this unconditionally.
        assertIsSuccess(store.delete(SecureKey.AccessToken))
    }

    @Test
    fun `clear removes every entry`() = runTest {
        if (skipIfUnavailable()) return@runTest

        SecureKey.entries.forEach { store.put(it, "value-${it.account}".encodeToByteArray()) }

        assertIsSuccess(store.clear())

        SecureKey.entries.forEach { key ->
            assertNull(store.get(key).expectSuccess(), "${key.account} survived clear()")
        }
    }

    @Test
    fun `the database key survives across store instances`() = runTest {
        if (skipIfUnavailable()) return@runTest

        // The launch-to-launch case: a new process must find the key the
        // previous one filed, or the local database is unreadable.
        val manager = DatabaseKeyManager(KeychainSecureStore(service = service))
        val first = manager.getOrCreate().expectSuccess()

        val second = DatabaseKeyManager(KeychainSecureStore(service = service)).getOrCreate().expectSuccess()

        assertContentEquals(first, second)
    }

    @Test
    fun `reports availability for this platform`() = runTest {
        val available = store.isAvailable()
        val os = currentPlatform().os

        println("Keychain: backend available=$available on $os")

        if (os == OperatingSystem.MacOs || os == OperatingSystem.Windows) {
            // These ship a credential store with the OS; absence means the
            // binding is broken, not that the machine lacks one.
            assertTrue(available, "$os must always have a usable credential store")
        }
    }

    // -- helpers ----------------------------------------------------------

    private suspend fun skipIfUnavailable(): Boolean {
        if (store.isAvailable()) return false
        println("Keychain: no backend on ${currentPlatform().os} — skipping (see class docs)")
        return true
    }

    private fun assertIsSuccess(result: ZillitResult<*>) {
        if (result is ZillitResult.Failure) {
            error("expected success, got ${result.error.technical ?: result.error.userMessage}")
        }
    }

    private fun <T> ZillitResult<T>.expectSuccess(): T = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> error("expected success, got ${error.technical ?: error.userMessage}")
    }
}
