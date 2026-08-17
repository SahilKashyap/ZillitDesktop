package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DatabaseKeyManagerTest {

    @Test
    fun `generates a key on first launch and stores it`() = runTest {
        val store = InMemorySecureStore()
        val manager = DatabaseKeyManager(store)

        val key = manager.getOrCreate().expectSuccess()

        assertEquals(DatabaseKeyManager.KEY_BYTES, key.size)
        assertTrue(store.contains(SecureKey.DatabaseKey))
        assertEquals(1, store.putCount)
    }

    @Test
    fun `returns the same key on the second launch`() = runTest {
        val store = InMemorySecureStore()

        val first = DatabaseKeyManager(store).getOrCreate().expectSuccess()
        val second = DatabaseKeyManager(store).getOrCreate().expectSuccess()

        assertContentEquals(first, second, "the key must be stable across launches or the cache is lost")
        assertEquals(1, store.putCount, "the key must not be regenerated once stored")
    }

    @Test
    fun `each call returns a fresh array`() = runTest {
        // EncryptedDriverFactory zeroes what it is given, so a cached instance
        // would yield 32 zero bytes on the second call — surfacing as
        // SQLITE_NOTADB, which reads like corruption rather than a key problem.
        val manager = DatabaseKeyManager(InMemorySecureStore())

        val first = manager.getOrCreate().expectSuccess()
        val second = manager.getOrCreate().expectSuccess()

        assertNotSame(first, second, "must not hand out the same array twice")
        assertContentEquals(first, second)

        // Simulate the consumer zeroing its copy.
        first.fill(0)
        assertFalse(manager.getOrCreate().expectSuccess().all { it == 0.toByte() },
            "zeroing one caller's array must not affect the stored key")
    }

    @Test
    fun `generated keys are not predictable`() = runTest {
        val keys = List(20) { DatabaseKeyManager(InMemorySecureStore()).getOrCreate().expectSuccess().toList() }

        assertEquals(20, keys.distinct().size, "keys must differ across installs")
        assertTrue(keys.none { key -> key.all { it == key.first() } }, "key must not be a constant run")
    }

    @Test
    fun `a keychain write failure fails closed`() = runTest {
        // The worst outcome would be proceeding with an unencrypted database.
        val store = InMemorySecureStore(failOn = setOf(SecureKey.DatabaseKey))

        val result = DatabaseKeyManager(store).getOrCreate()

        assertIs<ZillitResult.Failure>(result)
        assertIs<ZillitError.Storage>(result.error)
    }

    @Test
    fun `a wrong-length stored key is reported rather than silently regenerated`() = runTest {
        // Regenerating would discard the user's local cache with no explanation.
        val store = InMemorySecureStore().apply { seed(SecureKey.DatabaseKey, ByteArray(16)) }

        val result = DatabaseKeyManager(store).getOrCreate()

        assertIs<ZillitResult.Failure>(result)
        assertIs<ZillitError.Crypto>(result.error)
    }

    @Test
    fun `destroy removes the key`() = runTest {
        val store = InMemorySecureStore()
        val manager = DatabaseKeyManager(store)
        manager.getOrCreate().expectSuccess()

        manager.destroy()

        assertFalse(store.contains(SecureKey.DatabaseKey))
    }

    private fun <T> ZillitResult<T>.expectSuccess(): T = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> error("expected success, got ${error.technical ?: error.userMessage}")
    }
}
