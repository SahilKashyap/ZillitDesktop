package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The crew member's unsaved edits to their own details — `{crew_details, bank}`
 * in wire shape (`CrewDetailsEditorPanel.jsx` `seedCrewDraft`).
 *
 * Kept as JSON because it is merged straight onto the deal for the live memo
 * and sent back as it stands. The seed is an allow-list: a key not seeded is
 * never sent, and a loan-out company or UK block is seeded only where it
 * applies, or a PAYE crew member's save would blank retained company data.
 */
data class CrewDraft(val json: JsonObject) {

    val crewDetails: JsonObject get() = DocRead.obj(json, CREW) ?: JsonObject(emptyMap())
    val bank: JsonObject get() = DocRead.obj(json, BANK) ?: JsonObject(emptyMap())

    /** A copy with one `crew_details` key replaced. */
    fun withCrew(key: String, value: JsonElement): CrewDraft =
        CrewDraft(JsonObject(json + (CREW to JsonObject(crewDetails + (key to value)))))

    /** A copy with one `bank` key replaced. */
    fun withBank(key: String, value: JsonElement): CrewDraft =
        CrewDraft(JsonObject(json + (BANK to JsonObject(bank + (key to value)))))

    companion object {
        private const val CREW = "crew_details"
        private const val BANK = "bank"

        fun seed(deal: DealDoc): CrewDraft {
            val cd = deal.crew
            val emergency = DocRead.obj(cd, "emergency_details")
            val representative = DocRead.obj(cd, "representative_details")
            val crew = buildMap<String, JsonElement> {
                listOf("full_legal_name", "preferred_name", "screen_credit_designation").forEach {
                    put(it, cd.orBlank(it))
                }
                put("passport_attachment", cd.orNull("passport_attachment"))
                put("dob", cd.orNull("dob"))
                listOf("email", "mobile", "gender", "insurance_no", "tax_code", "right_to_work", "emp_status").forEach {
                    put(it, cd.orBlank(it))
                }
                put("home_address", DealAddress.of(cd?.get("home_address")).toJson())
                put(
                    "emergency_contact_name",
                    cd.present("emergency_contact_name") ?: emergency.present("name")
                        ?: cd.present("emergency_contact") ?: JsonPrimitive(""),
                )
                put(
                    "emergency_contact_number",
                    cd.present("emergency_contact_number") ?: emergency.present("phone_number") ?: JsonPrimitive(""),
                )
                put("emergency_details", contact(emergency))
                put("representative_details", contact(representative))
                if (CrewStatus.isLoanOut(DocRead.text(cd, "emp_status"))) put("loan_out_company", loanOut(cd))
                if (UkPayroll.appliesTo(deal)) put(UkPayroll.KEY, ukFromWire(DocRead.obj(cd, UkPayroll.KEY)))
            }
            return CrewDraft(
                buildJsonObject {
                    put(CREW, JsonObject(crew))
                    put(BANK, DocRead.obj(deal.json, BANK) ?: JsonObject(emptyMap()))
                },
            )
        }

        /** `{name, country_code, phone_number, email, address}` — the emergency and representative shape. */
        private fun contact(source: JsonObject?): JsonObject = buildJsonObject {
            listOf("name", "country_code", "phone_number", "email").forEach { put(it, source.orBlank(it)) }
            put("address", DealAddress.of(source?.get("address")).toJson())
        }

        private fun loanOut(cd: JsonObject?): JsonObject {
            val company = DocRead.obj(cd, "loan_out_company")
            return buildJsonObject {
                put("name", company.orBlank("name"))
                put("address", DealAddress.of(company?.get("address")).toJson())
                listOf("country_code", "phone_number", "email").forEach { put(it, company.orBlank(it)) }
            }
        }

        /** `ukFromWire`: every key, the stored ones over the empty block, pension never blank. */
        fun ukFromWire(block: JsonObject?): JsonObject {
            val merged = UK_EMPTY + block.orEmpty()
            val pension = DocRead.text(block, "pension_status") ?: UkPayroll.PENSION_DEFAULT
            return JsonObject(merged + ("pension_status" to JsonPrimitive(pension)))
        }

        private val UK_EMPTY: Map<String, JsonElement> = listOf(
            "starter_statement", "p45_previous_pay", "p45_previous_tax", "p45_leaving_date",
            "p45_previous_paye_ref", "student_loan_plan", "pg_loan", "ni_category",
        ).associateWith { JsonNull } + ("pension_status" to JsonPrimitive(UkPayroll.PENSION_DEFAULT))

        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

        /** `x ?? ""` — a present value as it is, else an empty string. */
        private fun JsonObject?.orBlank(key: String): JsonElement = present(key) ?: JsonPrimitive("")

        private fun JsonObject?.orNull(key: String): JsonElement = present(key) ?: JsonNull

        private fun JsonObject?.present(key: String): JsonElement? = this?.get(key)?.takeUnless { it is JsonNull }
    }
}

/**
 * `applyCrewDraft`: the deal with the draft's crew details and bank laid over
 * its own, so every row, checklist and blocker follows unsaved typing.
 */
fun DealDoc.withDraft(draft: CrewDraft?): DealDoc {
    if (draft == null) return this
    val crew = JsonObject((crew ?: JsonObject(emptyMap())) + draft.crewDetails)
    val bank = JsonObject((DocRead.obj(json, "bank") ?: JsonObject(emptyMap())) + draft.bank)
    return DealDoc(JsonObject(json + ("crew_details" to crew) + ("bank" to bank)))
}

/** `isCrewDraftDirty`: a draft that no longer matches a fresh seed of the saved deal. */
fun CrewDraft?.isDirtyAgainst(deal: DealDoc): Boolean = this != null && this != CrewDraft.seed(deal)

/** `buildCrewPayload`: the whole draft's crew details, and the bank unless capture is locked. */
fun CrewDraft.payload(bankLocked: Boolean = false): JsonObject = buildJsonObject {
    put("crew_details", crewDetails)
    if (!bankLocked) put("bank", BankWire.payload(bank))
}

/** The bank sub-document on the wire (`toDealMemoPayload.js` `toBankPayload`, `isBankComplete`). */
object BankWire {

    private val SCALARS = listOf(
        "name", "account_holder_name", "account_number", "sort_code", "iban_number", "swift_code", "nominal_code",
    )

    /**
     * Seven strings, the currency only when it has a code, and the additional
     * details with a title — never the server's `id` or `bank_acc_id`.
     */
    fun payload(bank: JsonObject?): JsonObject = buildJsonObject {
        SCALARS.forEach { key -> put(key, text(bank?.get(key))) }
        val currency = DocRead.obj(bank, "currency")
        DocRead.text(currency, "code")?.let { code ->
            put(
                "currency",
                buildJsonObject {
                    put("code", code)
                    put("name", text(currency?.get("name")))
                    put("symbol", text(currency?.get("symbol")))
                },
            )
        }
        put(
            "additional_details",
            buildJsonArray {
                DocRead.objects(bank?.get("additional_details"))
                    .filter { text(it["field"]).isNotBlank() }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("field", text(row["field"]))
                                put("value", text(row["value"]))
                                put("field_type", DocRead.text(row, "field_type") ?: "text")
                            },
                        )
                    }
            },
        )
    }

    /** `isBankComplete`: any account detail or additional row — the nominal code counts, the currency does not. */
    fun isComplete(bank: JsonObject?): Boolean {
        if (bank == null) return false
        val anyScalar = SCALARS.any { text(bank[it]).isNotEmpty() }
        val anyDetail = DocRead.objects(bank["additional_details"]).any {
            text(it["field"]).isNotEmpty() || text(it["value"]).isNotEmpty()
        }
        return anyScalar || anyDetail
    }

    /** `String(x ?? '')`. */
    private fun text(element: JsonElement?): String = when (element) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString(",")
        is JsonObject -> "[object Object]"
    }
}
