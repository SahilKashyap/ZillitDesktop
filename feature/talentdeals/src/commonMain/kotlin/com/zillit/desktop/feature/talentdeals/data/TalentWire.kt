package com.zillit.desktop.feature.talentdeals.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.feature.talentdeals.domain.JsonRead
import com.zillit.desktop.feature.talentdeals.domain.PaidAs
import com.zillit.desktop.feature.talentdeals.domain.StoredExpense
import com.zillit.desktop.feature.talentdeals.domain.StoredSchedule
import com.zillit.desktop.feature.talentdeals.domain.StoredTranche
import com.zillit.desktop.feature.talentdeals.domain.StoredWithholding
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentExhibitG
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import kotlinx.serialization.json.JsonObject

/**
 * The talent-contract wire, read field by field. Snake_case keys, numbers that
 * may arrive as strings, booleans that may arrive as `"true"`, a currency that
 * may be a bare code or the whole currency object.
 */
internal object TalentWire {

    /** A stored contract, or null for something with no id to place it by. */
    fun contractOf(row: JsonObject): TalentContract? {
        val id = JsonRead.text(row, "_id") ?: JsonRead.text(row, "id") ?: return null
        val w = JsonRead.obj(row, "withholding")
        val ps = JsonRead.obj(row, "payment_schedule")
        return TalentContract(
            id = id,
            reference = JsonRead.text(row, "talent_reference").orEmpty(),
            person = JsonRead.text(row, "person").orEmpty(),
            role = JsonRead.text(row, "role").orEmpty(),
            status = JsonRead.text(row, "status").orEmpty(),
            longFormContract = TalentExhibitG.attachmentOf(JsonRead.obj(row, "long_form_contract")),
            territoryCode = JsonRead.text(row, "territory_code").orEmpty(),
            unionIdentifier = JsonRead.text(row, "union_identifier").orEmpty(),
            fee = JsonRead.number(row, "fee") ?: 0.0,
            currency = JsonRead.currencyCode(row, "contract_currency").orEmpty(),
            paymentMode = JsonRead.text(row, "payment_mode").orEmpty(),
            feeBasis = JsonRead.text(row, "fee_basis").orEmpty(),
            withholding = StoredWithholding(
                applies = JsonRead.bool(w, "applies"),
                basis = WithholdingType.of(JsonRead.text(w, "basis")),
                value = JsonRead.number(w, "value") ?: 0.0,
                authorised = JsonRead.bool(w, "authorised"),
                authorisedRate = JsonRead.number(w, "authorised_rate") ?: 0.0,
                certificate = TalentExhibitG.attachmentOf(JsonRead.obj(w, "certificate_attachment")),
            ),
            schedule = StoredSchedule(
                cadence = JsonRead.text(ps, "cadence").orEmpty(),
                paidAs = PaidAs.of(JsonRead.text(ps, "paid_as")),
                tranches = JsonRead.objects(ps?.get("tranches")).map { t ->
                    StoredTranche(
                        label = JsonRead.text(t, "label").orEmpty(),
                        startDate = JsonRead.long(t, "start_date"),
                        endDate = JsonRead.long(t, "end_date"),
                        weeks = JsonRead.number(t, "weeks") ?: 0.0,
                        value = JsonRead.number(t, "value") ?: 0.0,
                    )
                },
            ),
            expenses = JsonRead.objects(row["expenses"]).map { e ->
                StoredExpense(
                    label = JsonRead.text(e, "label").orEmpty(),
                    amount = JsonRead.number(e, "amount") ?: 0.0,
                    withhold = JsonRead.bool(e, "withhold"),
                    grossUp = JsonRead.bool(e, "gross_up"),
                )
            },
            autoTimeCards = JsonRead.bool(row, "auto_time_cards"),
        )
    }

    /** A union lookup row; one with no identifier is no choice to offer. */
    fun unionOf(row: JsonObject): TalentUnion? {
        val identifier = JsonRead.text(row, "union_identifier") ?: return null
        return TalentUnion(
            id = JsonRead.text(row, "union_id") ?: identifier,
            identifier = identifier,
            name = JsonRead.text(row, "union_name").orEmpty(),
        )
    }
}

/**
 * `status: 0` over a 200 is a refusal on this service — the web throws it
 * (`accountHubClient.js` response interceptor), while `ApiClient` hands it
 * back as a success. Every call goes through this, so a refusal never reads as
 * done.
 */
internal fun ApiEnvelope.refusedOrOk(): ZillitResult<ApiEnvelope> =
    if (status == REFUSED) {
        ZillitResult.Failure(
            ZillitError.Http(
                status = HTTP_OK,
                serverMessage = message,
                messageElements = messageElements.orEmpty(),
            ),
        )
    } else {
        ZillitResult.Success(this)
    }

private const val REFUSED = 0
private const val HTTP_OK = 200
