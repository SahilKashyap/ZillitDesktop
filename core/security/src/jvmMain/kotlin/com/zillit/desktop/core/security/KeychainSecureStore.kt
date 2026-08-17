package com.zillit.desktop.core.security

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SecureStore] backed by the operating system's credential store.
 *
 * Uses `com.github.javakeyring:java-keyring`, which binds:
 *
 * | Platform | Backend |
 * |---|---|
 * | macOS | Keychain Services (`Security.framework`) |
 * | Windows | Credential Manager (`Advapi32` `CredRead`/`CredWrite`) |
 * | Linux / ChromeOS | Freedesktop Secret Service, or KWallet on KDE |
 *
 * Kept behind [SecureStore] so the dependency is swappable — the library is a
 * thin binding over very stable OS APIs, but its last release was 2023 (see
 * `docs/spikes/keychain.md`).
 *
 * ## A limitation worth knowing
 *
 * The underlying API is `String`-based, so secrets pass through a JVM `String`
 * on the way in and out. Strings cannot be zeroed and may linger in a heap
 * dump. Everything downstream of this class uses `ByteArray` and zeroes it, so
 * the exposure is bounded to the moment of transfer — but it is not nil, and
 * pretending otherwise would be worse than saying so. Removing it entirely
 * means binding `SecItemAdd`/`CredWriteW` directly against byte buffers.
 */
class KeychainSecureStore(
    private val service: String = SecureKey.SERVICE,
) : SecureStore {

    /**
     * Opened lazily and per call rather than held.
     *
     * `Keyring` is `AutoCloseable` and the Linux backend holds a DBus
     * connection; keeping one open for the life of the app leaks that
     * connection across sleep/resume.
     */
    private inline fun <T> withKeyring(block: (Keyring) -> T): T =
        Keyring.create().use { block(it) }

    override suspend fun get(key: SecureKey): ZillitResult<ByteArray?> = io {
        try {
            val encoded = withKeyring { it.getPassword(service, key.account) }
            ZillitResult.Success(encoded?.let(::decode))
        } catch (@Suppress("SwallowedException") absent: PasswordAccessException) {
            // The backends do not distinguish "no such entry" from "read
            // failed" — both throw. Absent is overwhelmingly the common case
            // (first launch), so it is not an error; a genuine backend failure
            // surfaces on the next put().
            ZillitResult.Success(null)
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            failure("read", key, throwable)
        }
    }

    override suspend fun put(key: SecureKey, value: ByteArray): ZillitResult<Unit> = io {
        try {
            withKeyring { it.setPassword(service, key.account, encode(value)) }
            ZillitResult.Success(Unit)
        } catch (@Suppress("SwallowedException") denied: PasswordAccessException) {
            // macOS ACLs keychain items to the application that created them.
            // After a runtime change (Zulu → JetBrains Runtime for the call
            // engine) the old entry still exists but belongs to the previous
            // binary: reads were already failing as "absent", and the write
            // dies here as an untouchable duplicate. Deleting an item is
            // permitted where reading its secret is not — so remove the
            // stranded entry and write ours once.
            try {
                withKeyring { it.deletePassword(service, key.account) }
                withKeyring { it.setPassword(service, key.account, encode(value)) }
                ZillitLog.i(TAG) { "replaced stranded keychain entry for ${key.account}" }
                ZillitResult.Success(Unit)
            } catch (@Suppress("TooGenericExceptionCaught") still: Throwable) {
                failure("write", key, still)
            }
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            failure("write", key, throwable)
        }
    }

    override suspend fun delete(key: SecureKey): ZillitResult<Unit> = io {
        try {
            withKeyring { it.deletePassword(service, key.account) }
            ZillitResult.Success(Unit)
        } catch (@Suppress("SwallowedException") absent: PasswordAccessException) {
            // Deleting something that is not there is the desired end state.
            ZillitResult.Success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            failure("delete", key, throwable)
        }
    }

    /**
     * Best-effort removal of every entry.
     *
     * Continues past individual failures on purpose: this runs on sign-out and
     * on remote revoke, and stopping at the first error would leave the rest
     * behind. Deleting the database key first means that even a partial wipe
     * renders the local database unreadable.
     */
    override suspend fun clear(): ZillitResult<Unit> = io {
        val ordered = listOf(SecureKey.DatabaseKey) + (SecureKey.entries - SecureKey.DatabaseKey)
        val failures = ordered.mapNotNull { key ->
            runCatching { withKeyring { it.deletePassword(service, key.account) } }
                .fold(
                    onSuccess = { null },
                    onFailure = { throwable ->
                        // Absent is the desired end state, not a failure.
                        if (throwable is PasswordAccessException) {
                            null
                        } else {
                            ZillitLog.w(TAG) { "could not clear ${key.account}: ${throwable::class.simpleName}" }
                            key.account
                        }
                    },
                )
        }
        if (failures.isEmpty()) {
            ZillitResult.Success(Unit)
        } else {
            ZillitResult.Failure(ZillitError.Storage("could not clear: ${failures.joinToString()}"))
        }
    }

    override suspend fun isAvailable(): Boolean = io {
        runCatching { withKeyring { it.keyringStorageType != null } }.getOrDefault(false)
    }

    private fun failure(action: String, key: SecureKey, throwable: Throwable): ZillitResult.Failure {
        // The account name is safe to log; the value never is.
        ZillitLog.e(TAG, throwable) { "keychain $action failed for ${key.account}" }
        return ZillitResult.Failure(
            ZillitError.Storage("keychain $action failed: ${throwable::class.simpleName}"),
        )
    }

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private suspend inline fun <T> io(crossinline block: () -> T): T =
        withContext(Dispatchers.IO) { block() }

    private companion object {
        const val TAG = "Keychain"
    }
}
