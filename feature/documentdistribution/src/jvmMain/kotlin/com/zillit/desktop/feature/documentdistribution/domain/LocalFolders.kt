package com.zillit.desktop.feature.documentdistribution.domain

import java.io.File
import java.net.URLConnection
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Walking directories off this machine into a [LocalFolderTree].
 *
 * Nothing is read here but names and sizes: each [FolderEntry] reads its bytes
 * when its upload comes round, so a folder of hundreds of scans never sits in
 * the heap at once.
 */

/**
 * One tree for a drop or a pick: every directory in [roots] with its
 * sub-directories, plus any loose files, which keep just their name.
 *
 * Paths run from the dropped directory's PARENT, so the folder's own name is the
 * first segment — it is what gets created. Hidden files (`.DS_Store`,
 * `.git`) are not part of anyone's folder and are dropped silently, as the
 * Drive upload does; a directory holding only those counts as empty. Symbolic
 * links are not followed, which is what keeps a loop from walking for ever.
 */
fun walkLocalPaths(roots: List<File>): LocalFolderTree {
    val entries = ArrayList<FolderEntry>()
    val empty = LinkedHashSet<String>()
    roots.forEach { root ->
        when {
            root.isDirectory && !Files.isSymbolicLink(root.toPath()) -> walk(root, root.name, entries, empty)
            root.isFile && !root.name.startsWith('.') -> entries += entryFor(root, root.name)
        }
    }
    return LocalFolderTree(entries, empty)
}

private fun walk(dir: File, path: String, entries: MutableList<FolderEntry>, empty: MutableSet<String>) {
    val children = dir.listFiles().orEmpty()
        .filter { !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }
        .sortedBy { it.name.lowercase() }
    var holdsSomething = false
    children.forEach { child ->
        val childPath = "$path/${child.name}"
        when {
            child.isDirectory -> {
                holdsSomething = true
                walk(child, childPath, entries, empty)
            }
            child.isFile -> {
                holdsSomething = true
                entries += entryFor(child, childPath)
            }
        }
    }
    if (!holdsSomething) empty += path
}

private fun entryFor(file: File, relativePath: String): FolderEntry = FolderEntry(
    relativePath = relativePath,
    contentType = URLConnection.guessContentTypeFromName(file.name) ?: FALLBACK_TYPE,
    sizeBytes = file.length(),
    read = { withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } },
)

private const val FALLBACK_TYPE = "application/octet-stream"
