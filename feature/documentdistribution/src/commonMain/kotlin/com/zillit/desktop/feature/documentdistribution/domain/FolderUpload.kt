package com.zillit.desktop.feature.documentdistribution.domain

/**
 * Folder upload: turning a picked or dropped directory into a plan the library
 * can execute. Ported from the web's `utils/folderUpload.js` (2026-09-29..10-07).
 *
 * The web has two browser APIs to normalise; here the host walks the directory
 * (see `LocalFolders.kt`) and hands over [FolderEntry]s whose bytes are NOT read
 * yet — a folder can hold hundreds of files, and each is read when its turn comes
 * rather than all at once into the heap.
 */

/** One file inside a folder being uploaded, with the path that led to it. */
class FolderEntry(
    /** From the picked folder's parent: `Docs/Sub/call-sheet.pdf`. A loose file is just its name. */
    val relativePath: String,
    val contentType: String,
    val sizeBytes: Long,
    /** Reads the bytes; null when the file can no longer be read. */
    val read: suspend () -> ByteArray?,
) {
    val name: String get() = relativePath.substringAfterLast('/')

    /** The folder that holds it (`Docs/Sub`), blank for a loose file. */
    val directory: String get() = relativePath.substringBeforeLast('/', "")

    override fun toString(): String = "FolderEntry($relativePath, $sizeBytes)"
}

/** Everything under a picked or dropped directory. */
class LocalFolderTree(
    val entries: List<FolderEntry>,
    /** Directories with nothing in them — a file-derived tree would lose them, and the shape is the point. */
    val emptyFolders: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = entries.isEmpty() && emptyFolders.isEmpty()
}

/** Every folder path an entry implies, parents included: `A/B/x.pdf` → `A`, `A/B`. */
private fun folderPathsOf(relativePath: String): List<String> {
    val parts = relativePath.split('/').filter { it.isNotEmpty() }.dropLast(1)
    return parts.indices.map { i -> parts.take(i + 1).joinToString("/") }
}

/** The plan: folders in the order they must be created, and the files to put in them. */
class UploadPlan(
    /** Shallowest first — a child needs its parent's server id. */
    val folders: List<String>,
    val files: List<FolderEntry>,
) {
    val fileCount: Int get() = files.size
    val folderCount: Int get() = folders.size
    val bytes: Long get() = files.sumOf { it.sizeBytes }

    /** Everything sits at the top level — a plain multi-file upload, which needs no confirmation. */
    val isFlat: Boolean get() = folders.isEmpty()

    /** Requests the whole action will send, folders included. */
    val requestCount: Int get() = folders.size + files.size
}

/**
 * Splits the entries by the library's allow-list. Unsupported files are
 * reported, never uploaded: the allow-list mirrors the server's own filter, so
 * sending one would fail anyway.
 */
fun partitionEntries(entries: List<FolderEntry>): Pair<List<FolderEntry>, List<FolderEntry>> =
    entries.partition { SupportedUploads.isSupported(it.name, it.contentType) }

/**
 * Turns entries into an ordered plan. Empty folders are folded in, with their
 * own ancestors, so they are created too.
 */
fun buildUploadPlan(entries: List<FolderEntry>, emptyFolders: Set<String> = emptySet()): UploadPlan {
    val folders = LinkedHashSet<String>()
    entries.forEach { folders += folderPathsOf(it.relativePath) }
    emptyFolders.forEach { path ->
        folders += path
        folders += folderPathsOf("$path/x")
    }
    val ordered = folders.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it.lowercase() }))
    return UploadPlan(ordered, entries)
}

/**
 * How many requests of [plan] sit BELOW [path]: its sub-folders at any depth and
 * every file in any of them. The folder itself is not counted.
 *
 * Used when a folder fails to create. Everything under it is unreachable at that
 * moment, so the whole subtree comes off the upload's total at once (backend
 * mail, point 6.3): taken off one at a time as the loop reached each skip, the
 * total stayed too high whenever those items were the LAST of the plan, and the
 * backend announced the upload on its 2-minute fallback instead of at once.
 */
fun countPlannedUnder(plan: UploadPlan, path: String): Int {
    if (path.isBlank()) return 0
    val prefix = "$path/"
    val folders = plan.folders.count { it.startsWith(prefix) }
    val files = plan.files.count { it.directory == path || it.directory.startsWith(prefix) }
    return folders + files
}

/** One line of the confirmation tree. */
data class UploadTreeRow(val depth: Int, val name: String, val isFolder: Boolean, val sizeBytes: Long = 0)

/**
 * The plan as the rows the confirmation shows: each folder, its sub-folders
 * first (alphabetically), then its files in the order they were found.
 */
fun UploadPlan.treeRows(): List<UploadTreeRow> {
    val rows = ArrayList<UploadTreeRow>()
    fun walk(parent: String, depth: Int) {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        folders
            .filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
            .forEach { path ->
                rows += UploadTreeRow(depth, path.substringAfterLast('/'), isFolder = true)
                walk(path, depth + 1)
            }
        files.filter { it.directory == parent }.forEach { rows += UploadTreeRow(depth, it.name, false, it.sizeBytes) }
    }
    walk("", 0)
    return rows
}
