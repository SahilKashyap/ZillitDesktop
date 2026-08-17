package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Writing an attachment to disk.
 *
 * The name is attacker-controlled, so the assertions that matter are about
 * where the bytes end up — not that they were written, but that they were
 * written *inside* the folder we chose.
 */
class AttachmentStoreTest {

    private val root: File = Files.createTempDirectory("zillit-attachments").toFile()
    private val downloads = File(root, "Downloads")
    private val store = DownloadsAttachmentStore(downloads)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun save(name: String, body: String = "hello") =
        store.save(name, body.encodeToByteArray())

    @Test
    fun `an ordinary attachment is written where it says it was`() = runTest {
        val result = save("Call sheet.pdf")

        val path = assertIs<ZillitResult.Success<String>>(result).data
        assertEquals("hello", File(path).readText())
        assertEquals(downloads.canonicalFile, File(path).parentFile.canonicalFile)
    }

    @Test
    fun `a traversing name cannot write outside the downloads folder`() = runTest {
        // The whole point. If this ever writes to `root`, an attachment could
        // land anywhere the user can write.
        val result = save("../../escaped.txt")

        val path = assertIs<ZillitResult.Success<String>>(result).data
        assertEquals(downloads.canonicalFile, File(path).parentFile.canonicalFile)
        assertTrue(File(root, "escaped.txt").exists().not(), "it escaped one level")
        assertTrue(File(root.parentFile, "escaped.txt").exists().not(), "it escaped two")
    }

    @Test
    fun `an absolute name cannot write to an absolute path`() = runTest {
        val result = save("/tmp/zillit-should-not-exist.txt")

        val path = assertIs<ZillitResult.Success<String>>(result).data
        assertEquals(downloads.canonicalFile, File(path).parentFile.canonicalFile)
        assertTrue(File("/tmp/zillit-should-not-exist.txt").exists().not())
    }

    @Test
    fun `downloading the same file twice does not overwrite the first`() = runTest {
        // People re-download by accident, and silently replacing a file they
        // had already opened and edited is not recoverable.
        val first = assertIs<ZillitResult.Success<String>>(save("report.pdf", "original")).data
        val second = assertIs<ZillitResult.Success<String>>(save("report.pdf", "second")).data

        assertEquals("original", File(first).readText())
        assertEquals("second", File(second).readText())
        assertEquals("report (2).pdf", File(second).name)
    }

    @Test
    fun `the extension is preserved when a name is made unique`() = runTest {
        save("script.final.fdx")
        val second = assertIs<ZillitResult.Success<String>>(save("script.final.fdx")).data

        assertEquals("script.final (2).fdx", File(second).name)
    }

    @Test
    fun `a name with no extension is still made unique`() = runTest {
        save("README")
        val second = assertIs<ZillitResult.Success<String>>(save("README")).data

        assertEquals("README (2)", File(second).name)
    }

    @Test
    fun `the downloads folder is created if it does not exist`() = runTest {
        assertTrue(!downloads.exists(), "precondition: nothing has been saved yet")

        save("first.txt")

        assertTrue(downloads.isDirectory)
    }

    @Test
    fun `empty bytes still produce a file rather than an error`() = runTest {
        // A zero-byte attachment is legal, if odd. The decoder rejects an empty
        // *payload* upstream; this is about the store not inventing rules.
        val path = assertIs<ZillitResult.Success<String>>(store.save("empty.txt", ByteArray(0))).data

        assertEquals(0, File(path).length())
    }

    @Test
    fun `binary content survives the round trip`() = runTest {
        val bytes = ByteArray(256) { it.toByte() }

        val path = assertIs<ZillitResult.Success<String>>(store.save("blob.bin", bytes)).data

        assertTrue(bytes.contentEquals(File(path).readBytes()))
    }
}
