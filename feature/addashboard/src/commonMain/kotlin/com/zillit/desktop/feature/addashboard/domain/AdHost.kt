package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The byte-level I/O this module cannot do through the envelope client.
 *
 * Three things leave the envelope: an agency spreadsheet and a signature image
 * go to the production's storage, a report's PDF and a list export come back as
 * files rather than as `{ status, message, data }`. Each piece is the
 * platform's — storage PUTs are signed with the workspace's own keys and the
 * raw routes need the encrypted headers — so the host supplies it and the
 * default refuses with a reason a person can read.
 */
interface AdTransfer {

    /** PUTs bytes into the production's storage; answers where they landed. */
    suspend fun putObject(
        key: String,
        contentType: String,
        bytes: ByteArray,
    ): ZillitResult<StoredAttachment> = unsupported(str(S.desktop_ad_uploads_unavailable))

    /** Reads an attachment the service named, through the app's signed fetch. */
    suspend fun fetchObject(attachment: StoredAttachment): ZillitResult<ByteArray> =
        unsupported(str(S.desktop_ad_file_unavailable))

    /** A signed GET on this service whose answer is a file — a report's PDF. */
    suspend fun getBytes(url: String): ZillitResult<ByteArray> =
        unsupported(str(S.desktop_ad_action_unavailable_desktop))

    /** A signed POST whose answer is a file — the day-list export. */
    suspend fun postBytes(url: String, body: JsonObject): ZillitResult<ByteArray> =
        unsupported(str(S.desktop_ad_action_unavailable_desktop))

    /** A signed multipart POST, for a service that takes the bytes itself. */
    suspend fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): ZillitResult<JsonElement> = unsupported(str(S.desktop_ad_uploads_unavailable))

    companion object {
        /** No I/O at all — tests, and a host that has not wired storage. */
        val None: AdTransfer = object : AdTransfer {}

        private fun <T> unsupported(reason: String): ZillitResult<T> = ZillitResult.Failure(
            ZillitError.Storage(technical = "AdTransfer not wired", userMessage = reason),
        )
    }
}

/** Where an uploaded file landed, as the service wants it referenced. */
data class StoredAttachment(
    val key: String,
    val bucket: String = "",
    val region: String = "",
    val fileName: String = "",
    val contentType: String = "",
    val size: Long = 0L,
)

/**
 * What the view model asks the machine and the open production for.
 *
 * Every member has a harmless default, so a test needs none of it.
 */
interface AdHost {

    /** A spreadsheet or a document chosen in the OS dialog; null when cancelled. */
    suspend fun pickFile(extensions: List<String>): PickedFile? = null

    /** Writes into the user's Downloads folder; answers the path written. */
    suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> =
        ZillitResult.Failure(ZillitError.Storage("no download folder", str(S.desktop_ad_downloads_unavailable)))

    /** Hands a saved file to the OS. */
    fun openFile(path: String) {}

    fun copyToClipboard(text: String) {}

    /**
     * The production's own members, from the project context the app already
     * holds — never a request of its own.
     *
     * Needed in four places: the internal-artiste picker, an internal
     * artiste's live name and avatar, the distribution list, and an Exhibit G
     * Cast cell.
     */
    fun crew(): List<AdCrewMember> = emptyList()

    /**
     * The production's currencies, and which one is its default.
     *
     * Suspending because it is a read of the production's setup rather than
     * something the context already holds; the dashboard asks once on open and
     * keeps the answer in its state.
     */
    suspend fun currencies(): AdCurrencies = AdCurrencies()

    /**
     * The signed-in user's own department, which a raised purchase order is
     * filed under — the department PO flow's `department_id`.
     */
    suspend fun departmentId(): String? = null

    /**
     * Whether this viewer is an accountant.
     *
     * The Agency tab's edit, delete and verify follow the Vendors rules rather
     * than the AD posting right, and this is the half of them that is not the
     * row's creator.
     */
    fun isAccountant(): Boolean = false

    /** Where an artiste's portal link points — `<origin>/extras-portal/<token>`. */
    fun portalOrigin(): String = ""

    /**
     * A signature as a PNG: the strokes somebody drew, or a typed name.
     *
     * The platform's, because encoding an image is: the module collects the
     * strokes and the host turns them into bytes. Null means it could not be
     * drawn, and the sign-off then refuses rather than sending something that
     * is not an image — a day's signature is stored and printed on vouchers,
     * so a corrupt one is worse than none.
     */
    suspend fun renderSignature(
        strokes: List<List<Pair<Float, Float>>>,
        typedName: String,
        widthPx: Int,
        heightPx: Int,
    ): ByteArray? = null

    companion object {
        val None: AdHost = object : AdHost {}
    }
}

/** A file the host handed over. */
class PickedFile(val name: String, val contentType: String, val bytes: ByteArray) {
    /** Never prints the bytes. */
    override fun toString(): String = "PickedFile(name=$name, type=$contentType, size=${bytes.size})"
}

/** One member of the open production, as this module needs them. */
data class AdCrewMember(
    val userId: String,
    val fullName: String,
    val email: String = "",
    val department: String = "",
    val departmentIdentifier: String = "",
    val designation: String = "",
    val designationIdentifier: String = "",
    val avatarUrl: String = "",
) {
    /**
     * Whether this person may be booked as an internal artiste.
     *
     * Exactly three groups, sitting at two levels of the org tree: the
     * Stand-Ins and Stunts **departments** wholesale, plus the **Supporting
     * Cast designation** inside Cast & Talent. Cast & Talent as a whole is
     * excluded on purpose — Main, Extra, Daily, Recurring and Weekly Cast, and
     * rows with no designation, are all left out. Anyone outside the three is
     * onboarded with the form's external toggle, so this never blocks a
     * booking.
     *
     * The designation is matched as a **substring across both** its
     * identifier and its name, because the spelling varies by source
     * (`supporting_cast` against `supporting_cast_label`).
     */
    val artisteEligible: Boolean
        get() = departmentIdentifier in ARTISTE_DEPARTMENTS ||
            SUPPORTING_CAST.containsMatchIn("$designationIdentifier $designation".lowercase())

    /**
     * Whether this person may be named in an Exhibit G Cast cell.
     *
     * The performer department in the departments master, which is **not** the
     * casting office — that is crew.
     */
    val castEligible: Boolean get() = departmentIdentifier == CAST_DEPARTMENT

    companion object {
        const val CAST_DEPARTMENT = "department_cast_talent"
        val ARTISTE_DEPARTMENTS = setOf("department_stand_ins", "department_stunts_department")
        private val SUPPORTING_CAST = Regex("supporting_cast")
    }
}

/** The project's currencies, as the money formatter reads them. */
data class AdCurrencies(
    val defaultCode: String = "",
    /** Code → symbol, from the canonical currency master. */
    val symbols: Map<String, String> = emptyMap(),
) {
    /**
     * The symbol for a record's own currency.
     *
     * A blank code falls back to the project default; if even that has not
     * resolved the symbol is empty — just the number, never a wrong-currency
     * sign.
     */
    fun symbolFor(code: String?): String {
        val resolved = code?.takeIf { it.isNotBlank() } ?: defaultCode
        if (resolved.isBlank()) return ""
        return symbols[resolved] ?: resolved
    }

    val defaultSymbol: String get() = symbolFor(defaultCode)
}
