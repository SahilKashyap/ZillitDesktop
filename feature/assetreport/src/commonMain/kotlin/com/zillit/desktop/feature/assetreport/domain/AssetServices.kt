package com.zillit.desktop.feature.assetreport.domain

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The register's own service: the line feed, the per-line record, and the vendors that name them. */
interface AssetRepository {
    /** Every eligible PO line — `GET purchase-orders/line-items`. */
    suspend fun lines(): ZillitResult<List<AssetLine>>

    /**
     * The full register record for one line, or null when none exists yet.
     *
     * The only source of the note and the attachments: the feed never carries
     * them, and editing must wait for this — a blank note saved over one that
     * was merely not fetched destroys it.
     */
    suspend fun record(line: AssetLine): ZillitResult<AssetRecord?>

    /**
     * The first write: one POST carrying everything pending. The row has to
     * exist before it can be patched, and a half-row would strand the rest.
     */
    suspend fun create(
        line: AssetLine,
        category: AssetCategory,
        comments: String,
        attachments: List<AssetAttachment>,
    ): ZillitResult<AssetRecord>

    /** `PATCH /:id` — category and attachments. It silently drops `comments`. */
    suspend fun update(
        assetId: String,
        category: AssetCategory,
        attachments: List<AssetAttachment>,
    ): ZillitResult<AssetRecord>

    /** `PATCH /:id/comment` — the note's only write path; empty clears note and stamp. */
    suspend fun updateComment(assetId: String, comments: String): ZillitResult<AssetRecord>

    /** Vendor id → display name, from the account-hub host. */
    suspend fun vendors(): ZillitResult<Map<String, String>>
}

/** A department the register can be narrowed to, in the admin listing's order. */
data class AssetDepartment(val id: String, val name: String)

/** Who wrote a note: the crew list's name and designation. */
data class AssetPerson(val id: String, val name: String, val designation: String = "")

/** One of the production's currencies; [rate] is `exr`, quoted against the default. */
data class AssetCurrency(
    val code: String,
    val name: String = "",
    val symbol: String = "",
    val rate: Double? = null,
)

/**
 * The production's currencies and the conversion the register shows amounts in.
 *
 * Each line is priced in its order's currency. `exr` is "rate vs the default,
 * base 1" — a foreign amount is default × exr — so a line reaches the default
 * by ÷ exr and the picked currency by × exr. A currency with no usable rate is
 * taken at face value, as the web takes it.
 */
data class AssetCurrencies(
    val options: List<AssetCurrency> = emptyList(),
    val defaultCode: String = "",
) {
    fun rateFor(code: String): Double? {
        val wanted = code.trim()
        if (wanted.isEmpty()) return null
        return options.firstOrNull { it.code.trim().equals(wanted, ignoreCase = true) }
            ?.rate
            ?.takeIf { it > 0.0 && it.isFinite() }
    }

    fun convert(amount: Double, lineCurrency: String, selected: String): Double {
        val base = defaultCode.trim().uppercase()
        val from = lineCurrency.trim().uppercase().ifBlank { base }
        val inDefault = if (from.isEmpty() || from == base) amount else rateFor(from)?.let { amount / it } ?: amount
        val to = selected.trim().uppercase().ifBlank { base }
        if (to.isEmpty() || to == base) return inDefault
        return rateFor(to)?.let { inDefault * it } ?: inDefault
    }

    /** The configured symbol, else the shared table's. */
    fun symbolFor(code: String): String =
        options.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
            ?.symbol
            ?.takeIf { it.isNotBlank() }
            ?: Money.symbol(code)

    /** What the picker offers: the production's currencies, the default always among them. */
    val choices: List<AssetCurrency>
        get() {
            val base = defaultCode.trim()
            if (base.isEmpty() || options.any { it.code.equals(base, ignoreCase = true) }) return options
            return listOf(AssetCurrency(code = base)) + options
        }
}

/** Departments, currencies and people — the host's to fetch, the register's to show. */
interface AssetDirectory {
    suspend fun departments(): List<AssetDepartment> = emptyList()
    suspend fun currencies(): AssetCurrencies = AssetCurrencies()
    suspend fun people(): List<AssetPerson> = emptyList()

    companion object {
        val None: AssetDirectory = object : AssetDirectory {}
    }
}

/** A file read from disk and not yet uploaded. Never compared by content. */
class PickedAssetFile(val name: String, val bytes: ByteArray) {
    val sizeBytes: Long get() = bytes.size.toLong()

    override fun toString(): String = "PickedAssetFile(name=$name, size=${bytes.size})"
}

/**
 * The file system and the bucket — neither belongs to the register's service.
 *
 * Picks are held by the view model until Save, as the web holds its `File`
 * objects: the upload *is* the save, so discarding costs nothing.
 */
interface AssetFiles {
    /**
     * The OS chooser. A file too big to be worth reading is named through
     * [onTooLarge] instead of being read.
     */
    suspend fun pick(onTooLarge: (name: String, sizeBytes: Long) -> Unit): List<PickedAssetFile>

    suspend fun upload(file: PickedAssetFile): ZillitResult<AssetAttachment>

    /** A stored file's bytes, for the thumbnail and the viewer. */
    suspend fun read(attachment: AssetAttachment): ZillitResult<ByteArray>

    /** The viewer's Download: a copy in Downloads, opened. */
    suspend fun saveCopy(fileName: String, bytes: ByteArray): ZillitResult<Unit>

    companion object {
        val None: AssetFiles = object : AssetFiles {
            private val missing = ZillitResult.Failure(
                ZillitError.Storage(userMessage = str(S.desktop_file_storage_unavailable)),
            )

            override suspend fun pick(onTooLarge: (String, Long) -> Unit): List<PickedAssetFile> = emptyList()
            override suspend fun upload(file: PickedAssetFile): ZillitResult<AssetAttachment> = missing
            override suspend fun read(attachment: AssetAttachment): ZillitResult<ByteArray> = missing
            override suspend fun saveCopy(fileName: String, bytes: ByteArray): ZillitResult<Unit> = missing
        }
    }
}

/** The server-rendered register, as the web's shared export menu offers it. */
enum class AssetExportFormat(
    val wire: String,
    private val labelKey: String,
    private val purposeKey: String,
    val badge: String,
) {
    Pdf("pdf", S.recce_export_pdf, S.desktop_hub_formatted_document_print_ready, "PDF"),
    Excel("xlsx", S.desktop_dm_export_excel, S.desktop_hub_editable_spreadsheet_with_live_data, "XLSX"),
    ;

    val label: String get() = str(labelKey)
    val purpose: String get() = str(purposeKey)
}

/** Runs the export and lands the file — bytes are the host's business. */
fun interface AssetExport {
    suspend fun export(format: AssetExportFormat, departmentIds: List<String>): ZillitResult<Unit>
}

/**
 * The web's attachment rule, `assetDocValidation`: images and PDFs, 10 MB
 * each. Checked on every pick and every drop — a dialog filter is a hint,
 * and a drop has none.
 */
object AssetFileRules {
    const val MAX_MEGABYTES = 10
    const val MAX_BYTES: Long = MAX_MEGABYTES * 1024L * 1024L

    val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
    val ALLOWED_EXTENSIONS: Set<String> = IMAGE_EXTENSIONS + "pdf"

    private val ALLOWED_TYPES = setOf(
        "application/pdf",
        "image/png", "image/jpeg", "image/gif", "image/webp",
        "image/bmp", "image/heic", "image/heif",
    )

    /** Why a file is turned away, in the web's words — or null when it is fine. */
    fun refusal(name: String, sizeBytes: Long, contentType: String = ""): String? {
        val typeOk = contentType.trim().lowercase() in ALLOWED_TYPES || extensionOf(name) in ALLOWED_EXTENSIONS
        return when {
            !typeOk -> "$name: unsupported file type. Only images and PDFs are allowed."
            sizeBytes > MAX_BYTES -> str(S.desktop_asset_too_big, name, MAX_MEGABYTES)
            else -> null
        }
    }

    /** The stored family for an upload — what the web's uploader records as `content_type`. */
    fun familyOf(name: String): String =
        if (extensionOf(name) in IMAGE_EXTENSIONS) AssetAttachment.IMAGE_FAMILY else AssetAttachment.DOCUMENT_FAMILY
}
