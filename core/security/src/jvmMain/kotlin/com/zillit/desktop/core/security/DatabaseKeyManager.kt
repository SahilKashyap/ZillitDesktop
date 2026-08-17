package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import java.security.SecureRandom

/**
 * Owns the local database encryption key.
 *
 * Generates a 256-bit key from [SecureRandom] on first launch, files it in the
 * OS keychain, and returns it on subsequent launches. The key is never derived
 * from anything the user types and never leaves the keychain in persistent
 * form — losing it means the local cache is unreadable, which is the intended
 * behaviour on sign-out and remote revoke (plan §8.5).
 *
 * ## Fails closed
 *
 * If the keychain is unavailable, this returns a failure. It does **not** fall
 * back to an unencrypted database or to a key derived from something guessable.
 * A silently-unencrypted database that looks like it worked is the worst
 * outcome available, so an unusable keychain is a hard stop the app must
 * surface.
 */
class DatabaseKeyManager(
    private val secureStore: SecureStore,
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * Returns the database key, creating it if this is the first launch.
     *
     * **Always returns a fresh array.** The consumer
     * (`EncryptedDriverFactory.create`) zeroes what it is given, so handing back
     * a cached instance would yield 32 zero bytes on the second call — which
     * surfaces as `SQLITE_NOTADB`, looking like corruption rather than a key
     * problem. See `DatabaseKeyProvider.key`.
     */
    suspend fun getOrCreate(): ZillitResult<ByteArray> {
        when (val existing = secureStore.get(SecureKey.DatabaseKey)) {
            is ZillitResult.Failure -> return existing
            is ZillitResult.Success -> existing.data?.let { stored ->
                return if (stored.size == KEY_BYTES) {
                    ZillitResult.Success(stored)
                } else {
                    // A wrong-length key cannot open the database, and silently
                    // regenerating would discard the user's cache without
                    // explanation. Report it.
                    ZillitResult.Failure(
                        ZillitError.Crypto("stored database key is ${stored.size} bytes, expected $KEY_BYTES"),
                    )
                }
            }
        }

        val generated = ByteArray(KEY_BYTES).also(random::nextBytes)
        return when (val stored = secureStore.put(SecureKey.DatabaseKey, generated)) {
            is ZillitResult.Failure -> {
                generated.fill(0)
                stored
            }

            is ZillitResult.Success -> {
                ZillitLog.i(TAG) { "Generated a new database key" }
                // A copy, because the caller zeroes what it receives and the
                // keychain already holds the canonical value.
                ZillitResult.Success(generated.copyOf()).also { generated.fill(0) }
            }
        }
    }

    /**
     * Discards the key, permanently orphaning the local database.
     *
     * The database file should be deleted alongside this — not for secrecy (it
     * is already unreadable) but so the user does not carry dead bytes forever.
     */
    suspend fun destroy(): ZillitResult<Unit> = secureStore.delete(SecureKey.DatabaseKey)

    companion object {
        const val KEY_BYTES = 32
        private const val TAG = "DatabaseKey"
    }
}
