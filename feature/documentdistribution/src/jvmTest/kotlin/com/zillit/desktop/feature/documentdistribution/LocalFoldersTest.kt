package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.walkLocalPaths
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Walking a real directory into a tree: names and sizes only, bytes read on demand. */
class LocalFoldersTest {

    private val roots = mutableListOf<File>()

    @AfterTest fun clean() = roots.forEach { it.deleteRecursively() }

    private fun tree(build: File.() -> Unit): File =
        Files.createTempDirectory("dd-walk").toFile().also { roots += it }.resolve("Docs").apply {
            mkdirs()
            build()
        }

    private fun File.file(path: String, text: String = "x") {
        resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    @Test
    fun `paths start at the folder's own name`() {
        val docs = tree { file("a.pdf"); file("Sub/b.pdf") }
        val walked = walkLocalPaths(listOf(docs))
        assertEquals(listOf("Docs/Sub/b.pdf", "Docs/a.pdf").sorted(), walked.entries.map { it.relativePath }.sorted())
    }

    @Test
    fun `hidden files are not part of anyone's folder`() {
        val docs = tree { file("a.pdf"); file(".DS_Store"); file(".git/config") }
        assertEquals(listOf("Docs/a.pdf"), walkLocalPaths(listOf(docs)).entries.map { it.relativePath })
    }

    @Test
    fun `a directory with nothing in it is reported empty`() {
        val docs = tree { file("a.pdf"); resolve("Nothing").mkdirs() }
        assertEquals(setOf("Docs/Nothing"), walkLocalPaths(listOf(docs)).emptyFolders)
    }

    @Test
    fun `a directory holding only hidden files counts as empty`() {
        val docs = tree { file("Only/.DS_Store") }
        val walked = walkLocalPaths(listOf(docs))
        assertEquals(setOf("Docs/Only"), walked.emptyFolders)
        assertTrue(walked.entries.isEmpty())
    }

    @Test
    fun `a folder with files in it is not empty, nor are its parents`() {
        val docs = tree { file("A/B/x.pdf") }
        assertTrue(walkLocalPaths(listOf(docs)).emptyFolders.isEmpty())
    }

    @Test
    fun `loose files dropped with a folder keep just their name`() {
        val docs = tree { file("a.pdf") }
        val loose = docs.parentFile.resolve("loose.pdf").apply { writeText("y") }
        val walked = walkLocalPaths(listOf(docs, loose))
        assertEquals(setOf("Docs/a.pdf", "loose.pdf"), walked.entries.map { it.relativePath }.toSet())
    }

    @Test
    fun `sizes are known without reading, and bytes come on demand`() = runTest {
        val docs = tree { file("a.pdf", "hello") }
        val entry = walkLocalPaths(listOf(docs)).entries.single()
        assertEquals(5, entry.sizeBytes)
        assertEquals("hello", entry.read()?.decodeToString())
    }

    @Test
    fun `a file that has gone reads as null rather than throwing`() = runTest {
        val docs = tree { file("a.pdf") }
        val entry = walkLocalPaths(listOf(docs)).entries.single()
        docs.resolve("a.pdf").delete()
        assertEquals(null, entry.read())
    }

    @Test
    fun `a content type is guessed from the name`() {
        val docs = tree { file("a.pdf"); file("pic.png") }
        val types = walkLocalPaths(listOf(docs)).entries.associate { it.name to it.contentType }
        assertEquals("application/pdf", types["a.pdf"])
        assertEquals("image/png", types["pic.png"])
    }

    @Test
    fun `a symbolic link is not followed`() {
        val docs = tree { file("a.pdf") }
        val outside = docs.parentFile.resolve("Elsewhere").apply { mkdirs(); resolve("secret.pdf").writeText("s") }
        val link = docs.resolve("link").toPath()
        val made = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        if (!made) return
        assertEquals(listOf("Docs/a.pdf"), walkLocalPaths(listOf(docs)).entries.map { it.relativePath })
    }
}
