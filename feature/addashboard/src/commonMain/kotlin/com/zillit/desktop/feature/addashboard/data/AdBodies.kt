@file:Suppress("TooManyFunctions") // One body builder per shape the service takes.

package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AgencyDraft
import com.zillit.desktop.feature.addashboard.domain.AgreementAllowanceRow
import com.zillit.desktop.feature.addashboard.domain.AgreementRateRow
import com.zillit.desktop.feature.addashboard.domain.ArtisteDraft
import com.zillit.desktop.feature.addashboard.domain.BankDetails
import com.zillit.desktop.feature.addashboard.domain.DayEntryPatch
import com.zillit.desktop.feature.addashboard.domain.DayPatch
import com.zillit.desktop.feature.addashboard.domain.DayPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.DayTypeOption
import com.zillit.desktop.feature.addashboard.domain.ImportRow
import com.zillit.desktop.feature.addashboard.domain.MealBreak
import com.zillit.desktop.feature.addashboard.domain.NonUnionRates
import com.zillit.desktop.feature.addashboard.domain.PayRule
import com.zillit.desktop.feature.addashboard.domain.PayRuleField
import com.zillit.desktop.feature.addashboard.domain.PayRuleTemplates
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.ReportCell
import com.zillit.desktop.feature.addashboard.domain.ReportColumn
import com.zillit.desktop.feature.addashboard.domain.ReportDraft
import com.zillit.desktop.feature.addashboard.domain.ReportFills
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportPageRow
import com.zillit.desktop.feature.addashboard.domain.ReportRow
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.domain.UnionPayBreakdown
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every request body this module sends.
 *
 * Kept apart from the repository so each shape can be read — and tested —
 * without a client: several of them are where a wrong key is accepted with a
 * 200 and quietly stored as nothing.
 */

/** The artiste form's body; bank details ride nested, as the server expects. */
internal fun ArtisteDraft.body(): JsonObject = buildJsonObject {
    put("is_external", JsonPrimitive(isExternal))
    if (!isExternal) put("user_id", userId.orNull())
    put(
        "personal_details",
        buildJsonObject {
            put("first_name", firstName.orNull())
            put("last_name", lastName.orNull())
            put("email", email.orNull())
            put("phone", phone.orNull())
            put("date_of_birth", dateOfBirth.orNull())
            put("address", address.orNull())
        },
    )
    put("category", JsonPrimitive(category.wire))
    put("engagement_type", JsonPrimitive(engagement.wire))
    put("union_details", buildJsonObject { put("union", union.orNull()) })
    put("agency_name", agencyName.orNull())
    put("agency_id", agencyId.orNull())
    put("is_minor", JsonPrimitive(isMinor))
    put("ref_number", refNumber.orNull())
    put("status", JsonPrimitive(status.wire))
    if (!bank.isEmpty) put("bank", bank.body())
    if (documents.isNotEmpty()) {
        put(
            "additional_documents",
            buildJsonArray {
                documents.forEach { document ->
                    add(
                        buildJsonObject {
                            // Matched back by row id, which is how an edit
                            // replaces a document rather than adding a second.
                            put("_id", document.id.orNull())
                            put("label", JsonPrimitive(document.label))
                            put("description", document.description.orNull())
                            put("file_key", document.fileKey.orNull())
                            put("file_name", document.fileName.orNull())
                        },
                    )
                }
            },
        )
    }
}

internal fun BankDetails.body(): JsonObject = buildJsonObject {
    put("account_name", accountName.orNull())
    put("account_number", accountNumber.orNull())
    put("sort_code", sortCode.orNull())
    put("iban_number", ibanNumber.orNull())
    put("swift_code", swiftCode.orNull())
    put("bank_name", bankName.orNull())
}

internal fun AgencyDraft.body(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("email", email.orNull())
    put("contact_person", contactPerson.orNull())
    put("phone", phone.orNull())
    put("address", address.orNull())
    if (!bank.isEmpty) put("bank", bank.body())
}

/**
 * One artiste-day patch, carrying only what changed.
 *
 * A clear is explicit: sending `null` for a field is how a wrap time is
 * removed, and leaving the key out is how it is left alone — two different
 * things that look the same from a nullable field alone.
 */
internal fun DayEntryPatch.body(): JsonObject = buildJsonObject {
    when {
        clearCallTime -> put("call_time", JsonNull)
        callTime != null -> put("call_time", JsonPrimitive(callTime))
    }
    when {
        clearWrapTime -> put("wrap_time", JsonNull)
        wrapTime != null -> put("wrap_time", JsonPrimitive(wrapTime))
    }
    when {
        clearAttendance -> put("attendance_status", JsonNull)
        attendance != null -> put("attendance_status", JsonPrimitive(attendance.wire))
    }
    dayType?.let { put("day_type", JsonPrimitive(it)) }
    meals?.let { put("meals", it.artisteMealsJson(shootDate)) }
    allowances?.let { put("allowances", it.rateLinesJson()) }
    additionalFees?.let { put("additional_fees", it.rateLinesJson()) }
    notes?.let { put("notes", JsonPrimitive(it)) }
}

/** The day header's own meals, anchored on the day being edited. */
internal fun List<MealBreak>.dayMealsJson(shootDate: Long): JsonArray = buildJsonArray {
    forEachIndexed { index, meal ->
        add(
            buildJsonObject {
                put("order", JsonPrimitive(meal.order.takeIf { it > 0 } ?: (index + 1)))
                put("type", meal.type.orNull())
                put("start_time", AdDates.hhmmToUtcEpoch(shootDate, meal.startTime).orNull())
                put("end_time", AdDates.hhmmToUtcEpoch(shootDate, meal.endTime).orNull())
            },
        )
    }
}

/**
 * A per-artiste meal, which spells the same two fields differently.
 *
 * `meal_start_time` / `meal_end_time` on the artiste-day, `start_time` /
 * `end_time` on the day header — and only the artiste-day carries a penalty.
 * The times are already wall-clock epochs by the time they get here.
 */
private fun List<MealBreak>.artisteMealsJson(shootDate: Long?): JsonArray = buildJsonArray {
    forEachIndexed { index, meal ->
        add(
            buildJsonObject {
                put("order", JsonPrimitive(meal.order.takeIf { it > 0 } ?: (index + 1)))
                put("type", meal.type.orNull())
                put("meal_start_time", AdDates.hhmmToUtcEpoch(shootDate, meal.startTime).orNull())
                put("meal_end_time", AdDates.hhmmToUtcEpoch(shootDate, meal.endTime).orNull())
                meal.penalty?.let { put("meal_penalty", JsonPrimitive(it)) }
            },
        )
    }
}

internal fun List<RateLine>.rateLinesJson(): JsonArray = buildJsonArray {
    forEach { line ->
        add(
            buildJsonObject {
                put("identifier", line.identifier.orNull())
                put("name", JsonPrimitive(line.label))
                put("label", JsonPrimitive(line.label))
                put("rate_type", JsonPrimitive(line.rateType))
                put("rate_amount", JsonPrimitive(line.rateAmount))
                put("amount", JsonPrimitive(line.rateAmount))
                put("nominal_code", line.nominalCode.orNull())
            },
        )
    }
}

internal fun List<DayPurchaseOrder>.purchaseOrdersJson(): JsonArray = buildJsonArray {
    forEach { order ->
        add(
            buildJsonObject {
                put("po_id", order.poId.orNull())
                put("agency", JsonPrimitive(order.agency))
                put("agency_id", order.agencyId.orNull())
                put("currency", JsonPrimitive(order.currency))
                put("amount", JsonPrimitive(order.amount))
            },
        )
    }
}

internal fun List<ImportRow>.importRowsJson(): JsonArray = buildJsonArray {
    forEach { row ->
        add(
            buildJsonObject {
                put("first_name", JsonPrimitive(row.firstName))
                put("last_name", JsonPrimitive(row.lastName))
                put("email", JsonPrimitive(row.email))
                put("phone", row.phone.orNull())
                put("agency_name", row.agencyName.orNull())
                put("agency_id", row.agencyId.orNull())
                put("date_of_birth", row.dateOfBirth.orNull())
                put("address", row.address.orNull())
                put("category", JsonPrimitive(row.category.wire))
            },
        )
    }
}

/**
 * Only non-empty values are written onto the day.
 *
 * A report must never wipe a day field it happens not to hold: the day's
 * crew call is what timecards read as Unit Call.
 */
internal fun DayPatch.writeInto(builder: JsonObjectBuilder) {
    dayNumber?.let { builder.put("day_number", JsonPrimitive(it)) }
    callTime?.let { builder.put("call_time", JsonPrimitive(it)) }
    wrapTime?.let { builder.put("wrap_time", JsonPrimitive(it)) }
    dayType?.takeIf { it.isNotBlank() }?.let { builder.put("day_type", JsonPrimitive(it)) }
    sceneSchedule?.let { builder.put("scene_schedule", it.toStringArray()) }
    sceneCompleted?.let { builder.put("scene_completed", it.toStringArray()) }
    sceneUncompleted?.let { builder.put("scene_uncompleted", it.toStringArray()) }
}

/** The rows the export renders, as the server's template names them. */
internal fun List<SupportingArtistDay>.exportJson(): JsonArray = buildJsonArray {
    forEach { row ->
        add(
            buildJsonObject {
                put("name", JsonPrimitive(row.artisteName))
                put("ref", row.refNumber.orNull())
                put("category", JsonPrimitive(row.category.label))
                put("engagement", JsonPrimitive(row.engagement.label))
                put("agency", row.agencyName.orNull())
                put("call_time", JsonPrimitive(row.callTime))
                put("wrap_time", JsonPrimitive(row.wrapTime))
                put("day_type", row.dayType.orNull())
                put("status", JsonPrimitive(row.status))
                put("attendance", JsonPrimitive(row.attendance.wire))
                put("amount", JsonPrimitive(row.payTotal))
                put("currency", JsonPrimitive(row.currency))
                put("notes", row.notes.orNull())
            },
        )
    }
}

/** The non-union document, which one Save writes whole. */
internal fun NonUnionRates.body(): JsonObject = buildJsonObject {
    put(
        "day_rates",
        buildJsonArray {
            dayRates.forEach { (category, amount) ->
                add(
                    buildJsonObject {
                        put("category", JsonPrimitive(category))
                        put("amount", JsonPrimitive(amount.toDoubleOrNull() ?: 0.0))
                    },
                )
            }
        },
    )
    put("day_types", dayTypes.dayTypesJson())
    put("overtimes", overtimes.toOverrideArray())
    put("premiums", premiums.toOverrideArray())
    put("penalties", penalties.toOverrideArray())
    put("allowances", allowances.toAllowanceArray())
    put("additional_fees", additionalFees.toAllowanceArray())
    put(
        "distribution",
        buildJsonArray {
            distribution.forEach { entry ->
                add(
                    buildJsonObject {
                        put("kind", JsonPrimitive(entry.kind))
                        put("ref", entry.ref.orNull())
                        put("name", entry.name.orNull())
                        put("email", entry.email.orNull())
                    },
                )
            }
        },
    )
    put(
        "report_approvers",
        buildJsonArray {
            reportApprovers.forEach { entry ->
                add(
                    buildJsonObject {
                        put("kind", JsonPrimitive(entry.kind))
                        put("ref", entry.ref.orNull())
                        put("name", entry.name.orNull())
                        put("email", entry.email.orNull())
                    },
                )
            }
        },
    )
}

internal fun List<DayTypeOption>.dayTypesJson(): JsonArray = buildJsonArray {
    filter { it.dayType.isNotBlank() }.forEach { option ->
        add(
            buildJsonObject {
                put("day_type", JsonPrimitive(option.dayType.trim()))
                put("work_min", JsonPrimitive(option.workMinutes))
                put("meal_break_min", JsonPrimitive(option.mealBreakMinutes))
            },
        )
    }
}

/**
 * An allowance or fee: flat, per day, full production, uncapped.
 *
 * Those four are fixed by this editor — only the name, the amount and the
 * nominal code are the AD's to change — so they are written here rather than
 * carried through the UI as four fields nobody can touch.
 */
private fun List<RateLine>.toAllowanceArray(): JsonArray = buildJsonArray {
    forEach { line ->
        add(
            buildJsonObject {
                put("id", line.identifier.orNull())
                put("name", JsonPrimitive(line.label))
                put("rate_type", JsonPrimitive("flat"))
                put("amount", JsonPrimitive(line.rateAmount))
                put("rate_pct", JsonNull)
                put("currency", JsonNull)
                put("basis", JsonPrimitive("day"))
                put("enable", JsonPrimitive(true))
                put("required", JsonPrimitive(false))
                put("nominal_code", line.nominalCode.orNull())
                put("pay_frequency", JsonPrimitive(""))
                put("applies_to", JsonPrimitive("full_production"))
                put("cap_type", JsonPrimitive("uncapped"))
                put("cap_amount", JsonNull)
            },
        )
    }
}

/**
 * A pay rule back into `rateRowOverrideSchema`.
 *
 * The template's id rides in `source.id`, and its single form field becomes
 * the one trigger — which is what makes a saved rule reopen as the same rule.
 */
private fun List<PayRule>.toOverrideArray(): JsonArray = buildJsonArray {
    forEach { rule ->
        add(
            buildJsonObject {
                put("row_id", JsonPrimitive(rule.id))
                put(
                    "source",
                    buildJsonObject {
                        put("id", JsonPrimitive(rule.templateId))
                        put("label", JsonPrimitive(rule.label))
                        put("raw_label", JsonNull)
                        put("note", rule.note.orNull())
                        put("applies_to", JsonNull)
                        put("rate_type", JsonPrimitive(rule.rateKind.wire))
                        put("rate_amount", rule.amount.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                        put("basis", JsonPrimitive(rule.basis.wire))
                        put("is_enhancement", JsonPrimitive(false))
                        put("min", JsonNull)
                        put("max", rule.cap.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                        put("triggers", buildJsonArray { add(rule.triggerBody()) })
                    },
                )
                put("nominal_code", rule.nominalCode.orNull())
                put("pay_frequency", JsonPrimitive(""))
            },
        )
    }
}

/** The one trigger a rule's template writes, plus the optional clamps. */
@Suppress("CyclomaticComplexMethod") // One branch per rule template; the catalogue has twelve.
private fun PayRule.triggerBody(): JsonObject = buildJsonObject {
    val minutes = formValue.toDoubleOrNull()?.let { (it * MINUTES_PER_HOUR).toInt() }
    when (templateId) {
        "ot_6th" -> {
            put("day_number", JsonPrimitive(SIXTH_DAY))
            put("consecutive", JsonPrimitive(true))
            put("after", JsonPrimitive(minutes ?: 0))
        }

        "ot_7th" -> {
            put("day_number", JsonPrimitive(SEVENTH_DAY))
            put("consecutive", JsonPrimitive(true))
            put("after", JsonPrimitive(minutes ?: 0))
        }

        "ot" -> put("after", JsonPrimitive(minutes ?: 0))
        "sixth_day" -> {
            put("day_number", JsonPrimitive(SIXTH_DAY))
            put("consecutive", JsonPrimitive(true))
        }

        "seventh_day" -> {
            put("day_number", JsonPrimitive(SEVENTH_DAY))
            put("consecutive", JsonPrimitive(true))
        }

        "bank_holiday" -> put(
            "day_kind",
            buildJsonArray { dayKinds.forEach { add(JsonPrimitive(it.wire)) } },
        )

        "pre_dawn", "night_work_early" -> {
            put("clock", JsonPrimitive(true))
            put("before", JsonPrimitive(PayRuleTemplates.parseClock(formValue)))
        }

        "night_work" -> {
            put("clock", JsonPrimitive(true))
            put("after", JsonPrimitive(PayRuleTemplates.parseClock(formValue)))
        }

        "broken_turnaround" -> put("less", JsonPrimitive(minutes ?: 0))
        "meal_penalty" -> {
            put("meal", JsonPrimitive(true))
            put("after", JsonPrimitive(minutes ?: 0))
        }

        "meal_curtailed" -> put("meal_curtailed", JsonPrimitive(true))
        else -> if (template.field == PayRuleField.Hours) put("after", JsonPrimitive(minutes ?: 0))
    }
    increments.toIntOrNull()?.let { put("increment", JsonPrimitive(it)) }
    bdrMin.toDoubleOrNull()?.let { put("bdr_min", JsonPrimitive(it)) }
    bdrMax.toDoubleOrNull()?.let { put("bdr_max", JsonPrimitive(it)) }
}

/**
 * One union agreement's document.
 *
 * The agreement's own rules are **snapshotted** into it: the AD service cannot
 * read the deal-memo agreement, so the server builds each artiste's deal from
 * what this save stores rather than from the agreement itself.
 */
internal fun UnionPayBreakdown.body(): JsonObject = buildJsonObject {
    put("agreement_identifier", JsonPrimitive(agreementId))
    put("agreement_label", JsonPrimitive(label.ifBlank { agreementId }))
    put("currency", (document?.currency ?: currency).orNull())
    put(
        "day_rates",
        buildJsonArray {
            dayRates.forEach { (category, amount) ->
                add(
                    buildJsonObject {
                        put("category", JsonPrimitive(category))
                        put("amount", JsonPrimitive(amount.toDoubleOrNull() ?: 0.0))
                    },
                )
            }
        },
    )
    put("overtimes", document?.overtimes.orEmpty().toSnapshotArray())
    put("premiums", document?.premiums.orEmpty().toSnapshotArray())
    put("penalties", document?.penalties.orEmpty().toSnapshotArray())
    put("turnaround", document?.turnaround.orEmpty().toSnapshotArray())
    put("day_types", document?.dayTypes.orEmpty().dayTypesJson())
    put("fringes", document?.fringes.orEmpty().toFloorArray(emptyMap()))
    put(
        "holiday_pay",
        document?.holidayPayAmount?.let { amount ->
            buildJsonObject {
                put("type", JsonPrimitive(document.holidayPayKind.ifBlank { "flat" }))
                put("amount", JsonPrimitive(amount))
                put("treatment", JsonPrimitive("incl"))
            }
        } ?: JsonObject(emptyMap()),
    )
    // Editable, floored at the agreement's amount.
    put("allowances", document?.allowances.orEmpty().toFloorArray(allowanceAmounts))
    put("additional_fees", document?.extraFees.orEmpty().toFloorArray(feeAmounts))
}

/** An agreement rate row carried through verbatim, as an override. */
private fun List<AgreementRateRow>.toSnapshotArray(): JsonArray = buildJsonArray {
    forEachIndexed { index, row ->
        add(
            buildJsonObject {
                put("row_id", JsonPrimitive(row.id.ifBlank { "r$index" }))
                put(
                    "source",
                    buildJsonObject {
                        put("id", JsonPrimitive(row.id.ifBlank { "r$index" }))
                        put("label", JsonPrimitive(row.label))
                        put("rate_type", JsonPrimitive(row.rateKind.wire))
                        put("rate_amount", row.rateAmount?.let(::JsonPrimitive) ?: JsonNull)
                        put("basis", JsonPrimitive(row.basis.wire))
                        put("note", row.note.orNull())
                        put("nominal_code", row.nominalCode.orNull())
                    },
                )
                put("nominal_code", row.nominalCode.orNull())
                put("pay_frequency", JsonPrimitive(""))
            },
        )
    }
}

/**
 * An agreement allowance, with the production's chosen amount floored at the
 * agreement's.
 *
 * The floor is the whole point: a production may pay **above** an agreement's
 * minimum and never below it, so the chosen amount is clamped here as well as
 * in the field.
 */
private fun List<AgreementAllowanceRow>.toFloorArray(chosen: Map<String, String>): JsonArray =
    buildJsonArray {
        forEach { row ->
            val floor = row.floor ?: 0.0
            val picked = chosen[row.id]?.toDoubleOrNull()
            val amount = maxOf(picked ?: floor, floor)
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(row.id))
                    put("name", JsonPrimitive(row.label))
                    put("rate_type", JsonPrimitive(if (row.rateKind.wire == "percentage") "pct" else "flat"))
                    put("amount", JsonPrimitive(amount))
                    put("rate_pct", JsonNull)
                    put("floor", JsonPrimitive(floor))
                    put("basis", row.basis.orNull())
                    put("cap_amount", row.cap?.let(::JsonPrimitive) ?: JsonNull)
                    put("enable", JsonPrimitive(true))
                },
            )
        }
    }

/**
 * A report's body, which every create, save and publish carries.
 *
 * `notify: false` is the autosave's: it tells the server to skip the bell and
 * the history entry, and the caller also skips the refetch nudge — otherwise
 * the editor's own listener would reload the report over edits typed after the
 * debounced write.
 */
internal fun ReportDraft.body(kind: ReportKind, notify: Boolean, asCopy: Boolean): JsonObject =
    buildJsonObject {
        // A client-minted id rides the first create so a retry adopts the same
        // report rather than making a second.
        id?.takeIf { it.isNotBlank() }?.let { put("_id", JsonPrimitive(it)) }
        put("name", JsonPrimitive(name))
        put("report_type", JsonPrimitive(kind.wire))
        shootDate?.let { put("shoot_date", JsonPrimitive(it)) }
        templateId?.takeIf { it.isNotBlank() }?.let { put("template_id", JsonPrimitive(it)) }
        put("pageRows", pageRows.pageRowsJson())
        put("fills", fills.toFillsArray())
        put("meta", buildJsonObject { put("header", header.toJsonObject()) })
        put("notify", JsonPrimitive(notify))
        if (asCopy) put("as_copy", JsonPrimitive(true))
        if (!dayPatch.isEmpty) {
            put("day_patch", buildJsonObject { dayPatch.writeInto(this) })
        }
    }

/** The fill map back to the portable array the wire carries. */
internal fun Map<String, String>.toFillsArray(): JsonArray = buildJsonArray {
    forEach { (key, value) ->
        val parsed = ReportFills.parse(key) ?: return@forEach
        add(
            buildJsonObject {
                put("cell_id", JsonPrimitive(parsed.first))
                put("row_id", JsonPrimitive(parsed.second))
                put("col", JsonPrimitive(parsed.third))
                put("value", JsonPrimitive(value))
            },
        )
    }
}

internal fun List<ReportPageRow>.pageRowsJson(): JsonArray = buildJsonArray {
    forEach { page ->
        add(
            buildJsonObject {
                put("id", JsonPrimitive(page.id))
                put("break", JsonPrimitive(page.isBreak))
                put("cells", buildJsonArray { page.cells.forEach { add(it.body()) } })
            },
        )
    }
}

private fun ReportCell.body(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("type", JsonPrimitive(type.wire))
    put("title", JsonPrimitive(title))
    put("hideTitle", JsonPrimitive(hideTitle))
    put("source", source.orNull())
    put("columns", buildJsonArray { columns.forEach { add(it.body()) } })
    put("rows", buildJsonArray { rows.forEach { add(it.body()) } })
    if (frozen) put("frozen", JsonPrimitive(true))
    if (mealGrid) put("mealGrid", JsonPrimitive(true))
    if (wrap) put("wrap", JsonPrimitive(true))
    if (rowOrder.isNotEmpty()) {
        put("rowOrder", buildJsonArray { rowOrder.forEach { add(JsonPrimitive(it)) } })
    }
}

private fun ReportColumn.body(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("label", JsonPrimitive(label))
    put("type", JsonPrimitive(type.wire))
    width?.let { put("w", JsonPrimitive(it)) }
}

private fun ReportRow.body(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("cells", buildJsonArray { cells.forEach { add(JsonPrimitive(it)) } })
    put("vtype", JsonPrimitive(valueType.wire))
    put("source", source.orNull())
    headerKey?.let { put("hkey", JsonPrimitive(it)) }
    field?.let { put("field", JsonPrimitive(it)) }
}

internal fun Map<String, String>.toJsonObject(): JsonObject = buildJsonObject {
    forEach { (key, value) -> put(key, JsonPrimitive(value)) }
}

private fun List<String>.toStringArray(): JsonArray =
    buildJsonArray { forEach { add(JsonPrimitive(it)) } }

/**
 * Whether a refused publish is the signature gate rather than a fault.
 *
 * The server's one structured refusal on this route; it is shown as the list
 * of performers who still owe a signature, never as a red error.
 */
internal fun ZillitError.isSignaturesPending(): Boolean =
    this is ZillitError.Http && serverMessage == SIGNATURES_PENDING

/** A blank string means "no value", which is `null` on this wire, not `""`. */
private fun String?.orNull(): kotlinx.serialization.json.JsonElement =
    this?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

private fun Long?.orNull(): kotlinx.serialization.json.JsonElement =
    this?.let(::JsonPrimitive) ?: JsonNull

private const val SIGNATURES_PENDING = "ad_report_signatures_pending"
private const val MINUTES_PER_HOUR = 60
private const val SIXTH_DAY = 6
private const val SEVENTH_DAY = 7
