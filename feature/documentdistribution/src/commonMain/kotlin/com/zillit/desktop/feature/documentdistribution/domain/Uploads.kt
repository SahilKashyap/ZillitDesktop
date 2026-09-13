package com.zillit.desktop.feature.documentdistribution.domain

/**
 * The upload allow-list, mirrored from the server's `uploadFilter.js` (and
 * the web's `utils/uploads.js`). One schema drives the picker's filter, the
 * drop partitioner and the file-kind detection, so a file the dialog admits
 * is a file the server will keep.
 */
object SupportedUploads {
    private val types: List<Pair<String, List<String>>> = listOf(
        "application/pdf" to listOf("pdf"),
        "image/jpeg" to listOf("jpg", "jpeg", "jpe", "jfif"),
        "image/png" to listOf("png"),
        "image/webp" to listOf("webp"),
        "image/gif" to listOf("gif"),
        "image/bmp" to listOf("bmp"),
        "image/avif" to listOf("avif"),
        "image/heic" to listOf("heic"),
        "image/heif" to listOf("heif"),
        "image/tiff" to listOf("tif", "tiff"),
        "image/x-tiff" to listOf("tif", "tiff"),
        "image/svg+xml" to listOf("svg", "svgz"),
        "image/x-icon" to listOf("ico"),
        "image/vnd.microsoft.icon" to listOf("ico"),
        "application/msword" to listOf("doc"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to listOf("docx"),
        "application/vnd.ms-excel" to listOf("xls"),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to listOf("xlsx"),
        "text/vcard" to listOf("vcf"),
        "text/x-vcard" to listOf("vcf"),
    )

    val mimes: Set<String> = types.map { it.first }.toSet()
    val extensions: Set<String> = types.flatMap { it.second }.toSet()

    /** What the banners and empty states say the library takes. */
    const val LABEL = "PDF, image, vCard, word, excel"

    fun isSupported(name: String?, contentType: String?): Boolean {
        val ext = extensionOf(name)
        if (ext.isNotEmpty() && ext in extensions) return true
        val mime = contentType.orEmpty().lowercase().substringBefore(';').trim()
        return mime.isNotEmpty() && mime in mimes
    }

    /** Best-effort MIME from the extension, for records that carry only a name. */
    fun mimeFromName(name: String?): String {
        val ext = extensionOf(name)
        if (ext.isEmpty()) return ""
        return types.firstOrNull { ext in it.second }?.first.orEmpty()
    }

    fun extensionOf(name: String?): String = name.orEmpty().substringAfterLast('.', "").lowercase()

    /** Accepted and rejected, in the order given. */
    fun partition(files: List<LocalFile>): Pair<List<LocalFile>, List<LocalFile>> =
        files.partition { isSupported(it.name, it.contentType) }
}

/** What the viewer and the watermark UI treat a file as — the web's `detectFileKind`. */
enum class FileKind { Pdf, Image, ImageUnsupported, VCard, Word, Excel, Other }

@Suppress("CyclomaticComplexMethod") // One branch per file kind the viewer distinguishes.
fun fileKindOf(contentType: String?, name: String?): FileKind {
    val mime = contentType.orEmpty().lowercase().substringBefore(';').trim()
    val ext = SupportedUploads.extensionOf(name)
    return when {
        mime == "application/pdf" || ext == "pdf" -> FileKind.Pdf
        mime in RENDERABLE_IMAGE_MIMES || ext in RENDERABLE_IMAGE_EXTENSIONS -> FileKind.Image
        mime in UNRENDERABLE_IMAGE_MIMES || ext in UNRENDERABLE_IMAGE_EXTENSIONS -> FileKind.ImageUnsupported
        mime == "text/vcard" || mime == "text/x-vcard" || ext == "vcf" -> FileKind.VCard
        mime == "application/msword" || mime.endsWith("wordprocessingml.document") || ext == "doc" || ext == "docx" ->
            FileKind.Word
        mime == "application/vnd.ms-excel" || mime.endsWith("spreadsheetml.sheet") || ext == "xls" || ext == "xlsx" ->
            FileKind.Excel
        else -> FileKind.Other
    }
}

private val RENDERABLE_IMAGE_MIMES = setOf(
    "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/bmp",
)
private val RENDERABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp")
private val UNRENDERABLE_IMAGE_MIMES = setOf(
    "image/heic",
    "image/heif",
    "image/tiff",
    "image/x-tiff",
    "image/svg+xml",
    "image/avif",
)
private val UNRENDERABLE_IMAGE_EXTENSIONS = setOf("heic", "heif", "tif", "tiff", "svg", "svgz", "avif", "ico")

/** Hard cap on the combined size of a send's attachments; sending is blocked above it. */
const val MAX_TOTAL_ATTACHMENT_BYTES: Long = 25L * 1024 * 1024

/** "3 files (a.jpg, b.png, c.pdf +9 more)" — for skip toasts, never the whole list. */
fun summariseFileNames(names: List<String>, max: Int = 3): String {
    val count = names.size
    val noun = "$count file" + if (count == 1) "" else "s"
    if (count == 0) return noun
    val shown = names.take(max).joinToString(", ")
    val extra = if (count > max) " +${count - max} more" else ""
    return "$noun ($shown$extra)"
}

/** `call_sheet.pdf` → `call_sheet_watermarked.pdf`. */
fun watermarkedFilename(originalName: String): String {
    if (originalName.isBlank()) return "document_watermarked"
    val dot = originalName.lastIndexOf('.')
    if (dot <= 0) return "${originalName}_watermarked"
    return originalName.substring(0, dot) + "_watermarked" + originalName.substring(dot)
}

/** Strips what a file system refuses, keeping spaces and case — the web's export stem. */
fun fileNameStem(name: String, fallback: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { fallback }
