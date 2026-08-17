package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/**
 * Test double for [SecureStore].
 *
 * Copies on both put and get, mirroring what a real keychain does — the caller
 * never shares an array with the store. A double that handed back the same
 * instance would hide exactly the aliasing bug the key-ownership contract
 * exists to prevent.
 */
class InMemorySecureStore(
    private var available: Boolean = true,
    private val failOn: Set<SecureKey> = emptySet(),
) : SecureStore {

    private val entries = mutableMapOf<SecureKey, ByteArray>()

    var putCount: Int = 0
        private set

    override suspend fun get(key: SecureKey): ZillitResult<ByteArray?> = when {
        key in failOn -> ZillitResult.Failure(ZillitError.Storage("simulated read failure"))
        else -> ZillitResult.Success(entries[key]?.copyOf())
    }

    override suspend fun put(key: SecureKey, value: ByteArray): ZillitResult<Unit> = when {
        key in failOn -> ZillitResult.Failure(ZillitError.Storage("simulated write failure"))
        else -> {
            entries[key] = value.copyOf()
            putCount++
            ZillitResult.Success(Unit)
        }
    }

    override suspend fun delete(key: SecureKey): ZillitResult<Unit> {
        entries.remove(key)
        return ZillitResult.Success(Unit)
    }

    override suspend fun clear(): ZillitResult<Unit> {
        entries.clear()
        return ZillitResult.Success(Unit)
    }

    override suspend fun isAvailable(): Boolean = available

    /** Plants a value directly, for tests that need a pre-existing entry. */
    fun seed(key: SecureKey, value: ByteArray) {
        entries[key] = value.copyOf()
    }

    fun contains(key: SecureKey): Boolean = entries.containsKey(key)
}
