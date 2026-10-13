package com.zillit.desktop.feature.talentdeals.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An uploaded file as the Account Hub backends store it — the envelope the
 * contract carries, never the bytes: `{ media, bucket, region, name,
 * content_type, content_subtype, caption }`.
 *
 * [raw] is the stored object when this came from the server. A contract opened
 * for Edit sends its attachment back "as is", so keys this client does not
 * model survive the round trip.
 */
data class TalentAttachment(
    val media: String,
    val bucket: String = "",
    val region: String = "",
    val name: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
    val caption: String = "",
    val raw: JsonObject? = null,
) {
    val isPresent: Boolean get() = media.isNotBlank()

    /** What the file is called on screen. */
    val displayName: String get() = name.ifBlank { media.substringAfterLast('/') }.ifBlank { "Attachment" }

    /** The extension the type badge and the previewer read, lower-cased and without the dot. */
    val extension: String
        get() = contentSubtype.lowercase().ifBlank { displayName.substringAfterLast('.', "").lowercase() }

    val isPdf: Boolean get() = extension == "pdf"

    val isImage: Boolean get() = extension in IMAGE_EXTENSIONS

    /** The wire form: the stored object untouched, else the envelope built from the fields. */
    fun toJson(): JsonObject = raw ?: buildJsonObject {
        put("media", media)
        put("bucket", bucket)
        put("region", region)
        put("name", name)
        put("content_type", contentType)
        put("content_subtype", contentSubtype)
        put("caption", caption)
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        /** `image` for a picture, `document` for anything else — the web's `detectUploadType` for these slots. */
        fun typeOf(fileName: String): String =
            if (fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) "image" else "document"
    }
}

/** A contract's lifecycle word. A talent contract has no approval chain: it is saved `issued`. */
enum class TalentStatus(val wire: String) {
    Issued("issued"),
    Draft("draft"),
    Cancelled("cancelled"),
    Deleted("deleted"),
    Unknown("");

    companion object {
        fun of(wire: String?): TalentStatus =
            entries.firstOrNull { it != Unknown && it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Unknown
    }
}

/** How the fee is paid: escrowed up front, or on the first day. */
enum class PaymentMode(val wire: String) {
    Escrow("escrow"),
    FirstDay("first-day"),
    ;

    companion object {
        fun of(wire: String?): PaymentMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** What that payment covers. Only asked once a payment mode is chosen. */
enum class FeeBasis(val wire: String) {
    Salary("salary"),
    Fringes("fringes"),
    ;

    companion object {
        fun of(wire: String?): FeeBasis? = entries.firstOrNull { it.wire == wire }
    }
}

/** How every tranche in one schedule is expressed — all percentages, or all amounts. */
enum class PaidAs(val wire: String) {
    Percent("pct"),
    Flat("flat"),
    ;

    companion object {
        fun of(wire: String?): PaidAs = if (wire == Flat.wire) Flat else Percent
    }
}

/** Withholding is quoted either as a rate or as one flat figure on the fee. */
enum class WithholdingType(val wire: String) {
    Percent("pct"),
    Amount("amount"),
    ;

    companion object {
        fun of(wire: String?): WithholdingType = if (wire == Amount.wire) Amount else Percent
    }
}

data class StoredWithholding(
    val applies: Boolean = false,
    val basis: WithholdingType = WithholdingType.Percent,
    val value: Double = 0.0,
    val authorised: Boolean = false,
    val authorisedRate: Double = 0.0,
    val certificate: TalentAttachment? = null,
)

data class StoredTranche(
    val label: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val weeks: Double = 0.0,
    val value: Double = 0.0,
)

data class StoredSchedule(
    val cadence: String = "",
    val paidAs: PaidAs = PaidAs.Percent,
    val tranches: List<StoredTranche> = emptyList(),
) {
    val isWeekly: Boolean get() = cadence == WEEKLY
}

data class StoredExpense(
    val label: String = "",
    val amount: Double = 0.0,
    val withhold: Boolean = false,
    val grossUp: Boolean = false,
)

/**
 * One stored talent contract — a director, cast or producer engagement keyed
 * against the executed agreement. `GET /api/v2/deal-memo/talent-contracts`
 * answers it in the model's own snake_case names.
 *
 * Not a deal memo: no approval chain, no signing, no templates, no badges.
 */
data class TalentContract(
    val id: String,
    val reference: String,
    val person: String,
    val role: String,
    val status: String,
    val longFormContract: TalentAttachment? = null,
    val territoryCode: String = "",
    val unionIdentifier: String = "",
    val fee: Double = 0.0,
    val currency: String = "",
    val paymentMode: String = "",
    val feeBasis: String = "",
    val withholding: StoredWithholding = StoredWithholding(),
    val schedule: StoredSchedule = StoredSchedule(),
    val expenses: List<StoredExpense> = emptyList(),
    val autoTimeCards: Boolean = false,
) {
    val statusKind: TalentStatus get() = TalentStatus.of(status)

    /** What the register's search matches against, lower-cased. */
    fun searchText(): String = listOfNotNull(reference, person, role, longFormContract?.name)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .lowercase()

    /** The register's "Weekly · 3 tranches" line pieces: the cadence word and the tranche count. */
    val trancheCount: Int get() = schedule.tranches.size
}

internal const val WEEKLY = "weekly"

/** A union the picker offers: `{ union_id, union_identifier, union_name }`. */
data class TalentUnion(val id: String, val identifier: String, val name: String)

/** A change to the register the socket reported; [contractId] is null when the frame named none. */
data class TalentRefresh(val contractId: String?)
