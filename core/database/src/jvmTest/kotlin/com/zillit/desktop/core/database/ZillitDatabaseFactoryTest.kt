package com.zillit.desktop.core.database

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.security.DatabaseKeyManager
import com.zillit.desktop.core.security.KeychainSecureStore
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end: an encrypted database whose key lives in the **real** OS keychain.
 *
 * This is the loop the SQLCipher and keychain work exist to close — encryption
 * is only real once there is somewhere safe to keep the key. Everything up to
 * here proved the halves in isolation.
 *
 * Uses a test-only keychain service name and a temp directory, and cleans up.
 */
class ZillitDatabaseFactoryTest {

    private val tempDir: File = Files.createTempDirectory("zillit-db-e2e").toFile()
    private val service = "Zillit Desktop — e2e test"
    private val dbPath = File(tempDir, "zillit.db").absolutePath
    private val secret = "CONFIDENTIAL-DEAL-MEMO-PAYROLL-2026"

    @AfterTest
    fun cleanUp() = runTest {
        runCatching { KeychainSecureStore(service).clear() }
        tempDir.deleteRecursively()
    }

    private fun factory(store: SecureStore = KeychainSecureStore(service)) =
        ZillitDatabaseFactory(keyManager = DatabaseKeyManager(store), databasePath = dbPath)

    @Test
    fun `survives a simulated app restart`() = runTest {
        if (skipIfNoKeychain()) return@runTest

        // First launch: key generated, filed in the keychain, database created.
        val first = factory().open().expectSuccess()
        first.appMetaQueries.upsert("deal", secret)

        // Second launch: brand-new objects throughout — nothing carried over in
        // memory. The key must come back from the OS store or the cache is lost.
        val second = factory().open().expectSuccess()

        assertEquals(secret, second.appMetaQueries.selectByKey("deal").executeAsOne())
    }

    @Test
    fun `the file on disk is encrypted`() = runTest {
        if (skipIfNoKeychain()) return@runTest

        factory().open().expectSuccess().appMetaQueries.upsert("deal", secret)

        val bytes = File(dbPath).readBytes()
        assertTrue(bytes.isNotEmpty())
        assertFalse(bytes.containsText(secret), "secret found in plaintext on disk")
        assertFalse(bytes.containsText("SQLite format 3"), "unencrypted SQLite header on disk")
    }

    @Test
    fun `losing the key makes the old data unrecoverable`() = runTest {
        if (skipIfNoKeychain()) return@runTest

        factory().open().expectSuccess().appMetaQueries.upsert("deal", secret)

        // Simulates remote device revoke — and equally a runtime change that
        // replaced the keychain entry: the key is destroyed, the file is not.
        KeychainSecureStore(service).delete(SecureKey.DatabaseKey)

        // A fresh key gets generated; the stranded file is recreated rather
        // than left to force online-only mode forever. What the security
        // property demands is that the OLD contents never come back — the
        // reopened database must be empty, not readable.
        val reopened = factory().open().expectSuccess()

        assertNull(
            reopened.appMetaQueries.selectByKey("deal").executeAsOneOrNull(),
            "old contents must not survive a lost key",
        )
    }

    @Test
    fun `destroy removes both the key and the file`() = runTest {
        if (skipIfNoKeychain()) return@runTest

        val factory = factory()
        factory.open().expectSuccess().appMetaQueries.upsert("deal", secret)
        assertTrue(File(dbPath).exists())

        factory.destroy()

        assertFalse(File(dbPath).exists(), "the database file should be gone")
        assertNull(
            KeychainSecureStore(service).get(SecureKey.DatabaseKey).expectSuccess(),
            "the key should be gone",
        )
    }

    @Test
    fun `an unavailable keychain fails closed rather than opening unencrypted`() = runTest {
        // The failure mode that matters most: never silently produce a
        // plaintext database because key storage was unavailable.
        val broken = object : SecureStore {
            override suspend fun get(key: SecureKey) =
                ZillitResult.Failure(ZillitError.Storage("no keychain"))
            override suspend fun put(key: SecureKey, value: ByteArray) =
                ZillitResult.Failure(ZillitError.Storage("no keychain"))
            override suspend fun delete(key: SecureKey) = ZillitResult.Success(Unit)
            override suspend fun clear() = ZillitResult.Success(Unit)
            override suspend fun isAvailable() = false
        }

        val result = factory(broken).open()

        assertIs<ZillitResult.Failure>(result)
        assertFalse(File(dbPath).exists(), "no database file may be created without a key")
    }

    // -- helpers ----------------------------------------------------------

    private suspend fun skipIfNoKeychain(): Boolean {
        if (KeychainSecureStore(service).isAvailable()) return false
        println("ZillitDatabaseFactory: no keychain backend — skipping")
        return true
    }

    private fun <T> ZillitResult<T>.expectSuccess(): T = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> error("expected success, got ${error.technical ?: error.userMessage}")
    }

    private fun ByteArray.containsText(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
