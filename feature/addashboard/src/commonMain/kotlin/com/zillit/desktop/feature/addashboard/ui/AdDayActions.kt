package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.ArtisteDraft
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.DayEntryPatch
import com.zillit.desktop.feature.addashboard.domain.ExportFormat
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay

/**
 * The day's list: who is on it, who turned up, and when they went home.
 *
 * Every mark sends its time **explicitly**, re-stamped onto the day being run
 * ([AdDates.nowAsWallClock]). Never left to the server: a server stamp is a
 * real instant, so an AD marking 07:30 in Mumbai would have it render as
 * 02:00 everywhere.
 */
@Suppress("TooManyFunctions") // One handler per action on a row.
internal class DayActions(private val vm: AdViewModel) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per event.
    fun on(event: DayEvent) {
        when (event) {
            is DayEvent.Search -> vm.update { copy(todayList = todayList.copy(search = event.text)) }
            is DayEvent.MarkPresent -> markPresent(event.id, nowOnDay())
            is DayEvent.MarkPresentAt -> markPresent(event.id, AdDates.hhmmToUtcEpoch(day(), event.time))
            is DayEvent.TimeDraft -> vm.update {
                copy(attendance = attendance.copy(timeDraft = attendance.timeDraft + (event.id to event.time)))
            }

            is DayEvent.MarkAbsent -> patch(event.id) {
                DayEntryPatch(shootDate = day(), attendance = AttendanceStatus.Absent)
            }

            // Unmarking clears the call time as well: a row that kept one
            // would still read as present, because a call time alone means
            // present everywhere in this module.
            is DayEvent.Unmark -> patch(event.id) {
                DayEntryPatch(shootDate = day(), clearAttendance = true, clearCallTime = true)
            }

            DayEvent.MarkAllPresent -> markAll(present = true)
            is DayEvent.MarkOut -> patch(event.id) {
                DayEntryPatch(shootDate = day(), wrapTime = nowOnDay())
            }

            is DayEvent.MarkOutAt -> patch(event.id) {
                DayEntryPatch(shootDate = day(), wrapTime = AdDates.hhmmToUtcEpoch(day(), event.time))
            }

            is DayEvent.ClearOut -> patch(event.id) {
                DayEntryPatch(shootDate = day(), clearWrapTime = true)
            }

            DayEvent.MarkAllOut -> markAll(present = false)
            is DayEvent.SetDayType -> patch(event.id) {
                DayEntryPatch(shootDate = day(), dayType = event.dayType)
            }

            is DayEvent.RemoveFromDay -> vm.editDay { vm.repo.removeFromDay(event.id) }
            is DayEvent.OpenVoucher -> openVoucher(event.row)
            DayEvent.CloseVoucher -> vm.update { copy(todayList = todayList.copy(openVoucher = null)) }
            is DayEvent.OpenArtisteRow -> vm.update {
                copy(todayList = todayList.copy(openArtiste = event.row))
            }

            DayEvent.CloseArtisteRow -> vm.update { copy(todayList = todayList.copy(openArtiste = null)) }
            DayEvent.OpenSummary -> vm.update { copy(todayList = todayList.copy(summaryOpen = true)) }
            DayEvent.CloseSummary -> vm.update { copy(todayList = todayList.copy(summaryOpen = false)) }
            is DayEvent.Export -> export(event.format)
            is DayEvent.SendApprovalLink -> sendLink(event.id)
        }
    }

    private fun day(): Long = vm.current.shootDate

    /** "Now" as the viewer's own clock reads it, re-stamped onto the day. */
    private fun nowOnDay(): Long? = AdDates.nowAsWallClock(vm.clock(), day())

    private fun markPresent(id: String, callTime: Long?) = patch(id) {
        DayEntryPatch(shootDate = day(), attendance = AttendanceStatus.Present, callTime = callTime)
    }

    private fun patch(id: String, build: () -> DayEntryPatch) {
        vm.update { copy(attendance = attendance.copy(busy = attendance.busy + id)) }
        vm.editDay {
            val result = vm.repo.updateDayEntry(id, build())
            vm.update { copy(attendance = attendance.copy(busy = attendance.busy - id)) }
            result
        }
    }

    /**
     * A bulk mark is one PATCH per row, and only the failures are reverted.
     *
     * The rows that went through stay marked: an all-or-nothing bulk would
     * undo work the server already accepted.
     */
    private fun markAll(present: Boolean) {
        val rows = if (present) {
            vm.current.dayList.filterNot { it.isHandled }
        } else {
            vm.current.dayList.filter { it.isPresent && !it.isWrapped }
        }
        if (rows.isEmpty()) return
        val patch = if (present) {
            DayEntryPatch(shootDate = day(), attendance = AttendanceStatus.Present, callTime = nowOnDay())
        } else {
            DayEntryPatch(shootDate = day(), wrapTime = nowOnDay())
        }
        vm.update { copy(runner = runner.copy(bulkBusy = true)) }
        vm.editDay {
            val result = vm.repo.updateDayEntries(rows.map { it.id }, patch)
            vm.update { copy(runner = runner.copy(bulkBusy = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    val failed = result.data
                    if (failed.isNotEmpty()) {
                        vm.fail(str(S.desktop_ad_some_rows_failed, failed.size))
                    }
                    ZillitResult.Success(Unit)
                }

                is ZillitResult.Failure -> ZillitResult.Failure(result.error)
            }
        }
    }

    /**
     * The voucher is the artiste-day read whole.
     *
     * Only Direct PAYE rows have one — an agency artiste is paid through their
     * agency's purchase order and never gets a voucher.
     */
    private fun openVoucher(row: SupportingArtistDay) {
        vm.update { copy(todayList = todayList.copy(openVoucher = row, voucherLoading = true)) }
        vm.run {
            when (val full = vm.repo.dayEntry(row.id)) {
                is ZillitResult.Success -> vm.update {
                    copy(todayList = todayList.copy(openVoucher = full.data, voucherLoading = false))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(todayList = todayList.copy(voucherLoading = false)) }
                    vm.report(full.error)
                }
            }
        }
    }

    /** The export is rendered server-side from the rows on screen. */
    private fun export(format: ExportFormat) {
        vm.update { copy(todayList = todayList.copy(exporting = true)) }
        vm.run {
            val rendered = vm.repo.exportDayList(
                format = format,
                rows = vm.current.todayRows,
                shootDate = day(),
                dayNumber = vm.current.dayNumber,
            )
            vm.update { copy(todayList = todayList.copy(exporting = false)) }
            when (rendered) {
                is ZillitResult.Failure -> vm.report(rendered.error)
                is ZillitResult.Success -> when (
                    val saved = vm.machine.saveToDownloads(rendered.data.fileName, rendered.data.bytes)
                ) {
                    is ZillitResult.Success -> {
                        vm.notice(str(S.desktop_card_section_saved, rendered.data.fileName))
                        vm.emit(AdEffect.Saved(saved.data))
                    }

                    is ZillitResult.Failure -> vm.report(saved.error)
                }
            }
        }
    }

    /**
     * Before submission this only explains itself.
     *
     * The links go out when the day is submitted; pressing it beforehand would
     * send nothing, so it says so rather than appearing to work.
     */
    private fun sendLink(id: String) {
        if (vm.current.day?.status?.locked != true) {
            vm.notice(str(S.desktop_ad_links_sent_on_submit))
            return
        }
        vm.update { copy(todayList = todayList.copy(sendingLink = id)) }
        vm.run {
            val result = vm.repo.sendApprovalLink(id)
            vm.update { copy(todayList = todayList.copy(sendingLink = null)) }
            when (result) {
                is ZillitResult.Success -> vm.notice(str(S.desktop_ad_approval_link_sent))
                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }
}

/**
 * Add Extras, and the quick add it shares with Today's List.
 *
 * Deliberately allows **tomorrow**: a unit books its crowd the night before,
 * which is why this page checks the target day's own lock rather than the
 * open day's.
 */
@Suppress("TooManyFunctions") // One handler per field of the quick-add form.
internal class AddExtrasActions(private val vm: AdViewModel) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per field.
    fun on(event: AddExtrasEvent) {
        when (event) {
            is AddExtrasEvent.PickDay -> pickDay(event.offset)
            is AddExtrasEvent.Search -> vm.update { copy(addExtras = addExtras.copy(search = event.text)) }
            is AddExtrasEvent.Toggle -> vm.update {
                val open = addExtras
                val chosen = if (event.artisteId in open.chosen) {
                    open.chosen - event.artisteId
                } else {
                    open.chosen + event.artisteId
                }
                copy(addExtras = open.copy(chosen = chosen))
            }

            is AddExtrasEvent.CallTime -> vm.update { copy(addExtras = addExtras.copy(callTime = event.time)) }
            AddExtrasEvent.StartNewArtiste -> vm.update {
                copy(addExtras = addExtras.copy(newArtiste = ArtisteDraft(isExternal = true)))
            }

            AddExtrasEvent.CancelNewArtiste -> vm.update { copy(addExtras = addExtras.copy(newArtiste = null)) }
            is AddExtrasEvent.NewFirstName -> patchNew { copy(firstName = event.text) }
            is AddExtrasEvent.NewLastName -> patchNew { copy(lastName = event.text) }
            is AddExtrasEvent.NewEmail -> patchNew { copy(email = event.text) }
            is AddExtrasEvent.NewPhone -> patchNew { copy(phone = event.text) }
            is AddExtrasEvent.NewCategory -> patchNew { copy(category = event.category) }
            is AddExtrasEvent.NewAgency -> patchNew {
                copy(agencyId = event.agencyId, agencyName = event.name)
            }

            AddExtrasEvent.Submit -> submit()
        }
    }

    /** The target day: today, or tomorrow. */
    private fun targetDate(): Long = AdDates.addDays(vm.current.today, vm.current.addExtras.dayOffset)

    private fun pickDay(offset: Int) {
        vm.update { copy(addExtras = addExtras.copy(dayOffset = offset)) }
        // The other day's own header decides whether it is still open, so the
        // page moves onto it rather than reading today's lock.
        vm.onEvent(AdEvent.ChangeDay(AdDates.addDays(vm.current.today, offset)))
    }

    /**
     * Books the ticked artistes, or creates the new external one and books them.
     *
     * A new external artiste **must** have an e-mail address here: the extras
     * portal is code-gated and the code can only go to the address on file, so
     * a booking without one leaves them unable to approve their own day.
     */
    private fun submit() {
        val open = vm.current.addExtras
        if (!open.ready) return
        if (!vm.current.canEditDay) {
            vm.refuse()
            return
        }
        val fresh = open.newArtiste
        if (fresh != null && !fresh.readyForPortal) {
            vm.update { copy(addExtras = addExtras.copy(error = str(S.desktop_ad_email_required_external))) }
            return
        }
        vm.update { copy(addExtras = addExtras.copy(saving = true, error = null)) }
        vm.run {
            val date = targetDate()
            val callTime = open.callTime.takeIf { it.isNotBlank() }
            val result = if (fresh != null) {
                vm.repo.createArtisteWithDay(fresh, date, callTime)
            } else {
                vm.repo.addToDay(open.chosen.toList(), date, callTime)
            }
            when (result) {
                is ZillitResult.Success -> {
                    val added = if (fresh != null) 1 else open.chosen.size
                    vm.update {
                        copy(
                            addExtras = AddExtrasState(dayOffset = open.dayOffset),
                            notice = if (added == 1) {
                                str(S.desktop_ad_added_one_artiste)
                            } else {
                                str(S.desktop_ad_added_artistes, added)
                            },
                        )
                    }
                    vm.loadDay()
                    if (fresh != null) vm.loadRegister()
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(addExtras = addExtras.copy(saving = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    private fun patchNew(change: ArtisteDraft.() -> ArtisteDraft) = vm.update {
        copy(
            addExtras = addExtras.copy(
                newArtiste = addExtras.newArtiste?.change(),
                error = null,
            ),
        )
    }
}

/**
 * Additionals: the allowances and fees an AD ticks, and the one custom payment.
 *
 * Three different save rules, deliberately: a tile saves on click, the notes
 * save on blur when they have changed, and the custom payment saves only on
 * its own Save. The drafts are never re-seeded from the server, because a
 * refetch lands about 900 ms after any write and would wipe half-typed text.
 */
@Suppress("TooManyFunctions") // One handler per control on a row.
internal class AdditionalsActions(private val vm: AdViewModel) {

    fun on(event: AdditionalsEvent) {
        when (event) {
            is AdditionalsEvent.Search -> vm.update { copy(additionals = additionals.copy(search = event.text)) }
            is AdditionalsEvent.Expand -> vm.update {
                val open = additionals
                val expanded = if (event.id in open.expanded) open.expanded - event.id else open.expanded + event.id
                copy(additionals = open.copy(expanded = expanded))
            }

            is AdditionalsEvent.ToggleAllowance -> toggleAllowance(event.id, event.line)
            is AdditionalsEvent.ToggleFee -> toggleFee(event.id, event.line)
            is AdditionalsEvent.NoteDraft -> vm.update {
                copy(
                    additionals = additionals.copy(
                        noteDrafts = additionals.noteDrafts + (event.id to event.text),
                    ),
                )
            }

            is AdditionalsEvent.SaveNote -> saveNote(event.id)
            is AdditionalsEvent.CustomDescription -> vm.update {
                copy(
                    additionals = additionals.copy(
                        customDescription = additionals.customDescription + (event.id to event.text),
                    ),
                )
            }

            is AdditionalsEvent.CustomAmount -> vm.update {
                copy(
                    additionals = additionals.copy(
                        customAmount = additionals.customAmount + (event.id to event.text),
                    ),
                )
            }

            is AdditionalsEvent.SaveCustom -> saveCustom(event.id)
            is AdditionalsEvent.CancelCustom -> vm.update {
                copy(
                    additionals = additionals.copy(
                        customDescription = additionals.customDescription - event.id,
                        customAmount = additionals.customAmount - event.id,
                    ),
                )
            }
        }
    }

    private fun row(id: String): SupportingArtistDay? = vm.current.dayList.firstOrNull { it.id == id }

    private fun toggleAllowance(id: String, line: RateLine) {
        val current = row(id) ?: return
        val next = current.allowances.toggled(line)
        save(id) { DayEntryPatch(shootDate = vm.current.shootDate, allowances = next) }
    }

    /**
     * The fee tiles and the custom payment share one array.
     *
     * Rebuilt from what the **server** holds plus the tile being toggled —
     * never from the custom draft, which would persist a half-typed payment
     * the moment somebody ticked a tile.
     */
    private fun toggleFee(id: String, line: RateLine) {
        val current = row(id) ?: return
        val saved = current.additionalFees
        val next = saved.toggled(line)
        save(id) { DayEntryPatch(shootDate = vm.current.shootDate, additionalFees = next) }
    }

    private fun saveNote(id: String) {
        val current = row(id) ?: return
        val draft = vm.current.additionals.noteDrafts[id] ?: return
        if (draft == current.notes) return
        save(id) { DayEntryPatch(shootDate = vm.current.shootDate, notes = draft) }
    }

    /**
     * The custom payment's rules, which match the backend's.
     *
     * An amount above zero upserts the `custom` line; an amount of zero with a
     * line already saved **removes** it, which is the delete gesture; an amount
     * of zero with nothing saved does nothing. The comparison is numeric, so
     * `"50"` and `"50.00"` are the same amount.
     */
    private fun saveCustom(id: String) {
        val current = row(id) ?: return
        val amount = vm.current.additionals.customAmount[id]?.toDoubleOrNull() ?: 0.0
        val description = vm.current.additionals.customDescription[id].orEmpty()
        val saved = current.customFee
        val others = current.additionalFees.filterNot { it.isCustom }
        val next = when {
            amount > 0.0 -> others + RateLine(
                identifier = RateLine.CUSTOM_IDENTIFIER,
                label = description.ifBlank { str(S.desktop_ad_custom_payment) },
                rateType = "flat",
                rateAmount = amount,
            )

            saved != null -> others
            else -> return
        }
        vm.update { copy(additionals = additionals.copy(savingCustom = additionals.savingCustom + id)) }
        vm.editDay {
            val result = vm.repo.updateDayEntry(
                id,
                DayEntryPatch(shootDate = vm.current.shootDate, additionalFees = next),
            )
            vm.update {
                // The draft is promoted only on success, so a failed save
                // keeps it for a retry rather than losing what was typed.
                val cleared = if (result is ZillitResult.Success) {
                    additionals.copy(
                        savingCustom = additionals.savingCustom - id,
                        customDescription = additionals.customDescription - id,
                        customAmount = additionals.customAmount - id,
                    )
                } else {
                    additionals.copy(savingCustom = additionals.savingCustom - id)
                }
                copy(additionals = cleared)
            }
            result
        }
    }

    private fun save(id: String, build: () -> DayEntryPatch) {
        vm.update { copy(additionals = additionals.copy(busy = additionals.busy + id)) }
        vm.editDay {
            val result = vm.repo.updateDayEntry(id, build())
            vm.update { copy(additionals = additionals.copy(busy = additionals.busy - id)) }
            result
        }
    }
}

/** A deal line ticked on, or off again, matched by its identifier. */
internal fun List<RateLine>.toggled(line: RateLine): List<RateLine> =
    if (any { it.identifier == line.identifier && it.identifier.isNotBlank() }) {
        filterNot { it.identifier == line.identifier }
    } else {
        this + line
    }
