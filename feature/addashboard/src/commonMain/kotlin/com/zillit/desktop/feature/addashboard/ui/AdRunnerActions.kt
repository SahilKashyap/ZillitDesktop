package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdPhases
import com.zillit.desktop.feature.addashboard.domain.DayEntryPatch
import com.zillit.desktop.feature.addashboard.domain.DayPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.MealBreak
import com.zillit.desktop.feature.addashboard.domain.SubmitDayRequest
import com.zillit.desktop.feature.addashboard.domain.SubmitRules

/**
 * Run Current Day, and a day opened from the schedule.
 *
 * The same four steps either way — Attendance, Mid-Day, Wrap, Submit — but
 * only the Run Current Day tab **gates** them: Mid-Day waits until everybody
 * is present, absent or blocked, and Submit until every present artiste has a
 * time out. A day opened from the schedule has every step open and an extra
 * Additionals section, because an AD going back over Tuesday is not running
 * Tuesday in order.
 */
@Suppress("TooManyFunctions") // One handler per control on the runner.
internal class RunnerActions(private val vm: AdViewModel) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table; one line per event.
    fun on(event: RunnerEvent) {
        when (event) {
            is RunnerEvent.Step -> step(event.step)
            is RunnerEvent.OpenDay -> openDay(event)
            RunnerEvent.CloseDay -> closeDay()
            is RunnerEvent.AddMeal -> patchMeals { it + MealBreak(order = it.size + 1, type = event.type) }
            is RunnerEvent.MealStart -> patchMeal(event.index) { copy(startTime = event.time) }
            is RunnerEvent.MealEnd -> patchMeal(event.index) { copy(endTime = event.time) }
            is RunnerEvent.MealType -> patchMeal(event.index) { copy(type = event.type) }
            is RunnerEvent.RemoveMeal -> patchMeals { meals ->
                meals.filterIndexed { index, _ -> index != event.index }
            }

            RunnerEvent.SaveMeals -> saveDayMeals()
            is RunnerEvent.OpenArtisteMeals -> openArtisteMeals(event.id)
            RunnerEvent.CloseArtisteMeals -> vm.update { copy(runner = runner.copy(openArtisteMeals = null)) }
            is RunnerEvent.ArtisteMealStart -> patchArtisteMeal(event.id, event.index) {
                copy(startTime = event.time)
            }

            is RunnerEvent.ArtisteMealEnd -> patchArtisteMeal(event.id, event.index) {
                copy(endTime = event.time)
            }

            is RunnerEvent.AddArtisteMeal -> patchArtisteMeals(event.id) { meals ->
                meals + MealBreak(order = meals.size + 1)
            }

            is RunnerEvent.RemoveArtisteMeal -> patchArtisteMeals(event.id) { meals ->
                meals.filterIndexed { index, _ -> index != event.index }
            }

            is RunnerEvent.SaveArtisteMeals -> saveArtisteMeals(event.id)
        }
    }

    /**
     * A step change is refused while the runner is gated and the step is not
     * yet unlocked — the same order the web locks them in.
     */
    private fun step(step: RunnerStep) {
        if (vm.current.runner.gated && !unlocked(step)) {
            vm.fail(
                when (step) {
                    RunnerStep.MidDay, RunnerStep.Wrap -> str(S.desktop_ad_mark_everyone_first)
                    else -> str(S.desktop_ad_wrap_everyone_first)
                },
            )
            return
        }
        vm.update { copy(runner = runner.copy(step = step)) }
    }

    /** Which steps a gated runner has opened, from the rows themselves. */
    private fun unlocked(step: RunnerStep): Boolean = when (step) {
        RunnerStep.Attendance -> true
        RunnerStep.MidDay, RunnerStep.Wrap -> AdPhases.attendanceComplete(vm.current.dayList)
        RunnerStep.Submit -> AdPhases.attendanceComplete(vm.current.dayList) &&
            AdPhases.everyoneWrapped(vm.current.dayList)
    }

    /**
     * A day opened from the schedule.
     *
     * Ungated, with the Additionals section, and read-only when the day is in
     * the future — an upcoming day may be looked at but not run.
     */
    private fun openDay(event: RunnerEvent.OpenDay) {
        val date = event.day.shootDate ?: return
        if (!vm.current.viewer.canPost) {
            vm.askForPostingRights()
            return
        }
        vm.update {
            copy(
                destination = AdDestination.LiveDay,
                runner = RunnerState(gated = false, openedDate = date, step = RunnerStep.Attendance),
            )
        }
        vm.onEvent(AdEvent.ChangeDay(date))
    }

    private fun closeDay() {
        vm.update {
            copy(destination = AdDestination.Schedule, runner = RunnerState())
        }
        vm.onEvent(AdEvent.ChangeDay(vm.current.today))
    }

    // -- the day's meals -----------------------------------------------------

    /**
     * Day meals are a draft until **Save meals**.
     *
     * The draft is also what keeps them on screen across a step change: a
     * silent save never refetches the header, so without this overlay a
     * remounted editor would re-seed from a stale header.
     */
    private fun patchMeals(change: (List<MealBreak>) -> List<MealBreak>) = vm.update {
        val base = runner.mealDraft ?: day?.meals.orEmpty()
        copy(runner = runner.copy(mealDraft = change(base)))
    }

    private fun patchMeal(index: Int, change: MealBreak.() -> MealBreak) = patchMeals { meals ->
        meals.mapIndexed { position, meal -> if (position == index) meal.change() else meal }
    }

    /**
     * Saves the day's meals, which the server copies onto every artiste-day
     * that is not already submitted.
     *
     * Saved silently — no refetch — so the editor's own fields are not reset
     * mid-edit by a re-sync.
     */
    private fun saveDayMeals() {
        val meals = vm.current.runner.mealDraft ?: return
        vm.update { copy(runner = runner.copy(mealsSaving = true)) }
        vm.editDay(reload = false) {
            val result = vm.repo.saveDayMeals(vm.current.shootDate, meals)
            vm.update {
                copy(
                    runner = runner.copy(mealsSaving = false),
                    // The saved meals stay as the overlay: the header is not
                    // re-read, so dropping the draft here would show the old one.
                    notice = if (result is ZillitResult.Success) str(S.desktop_ad_meals_saved) else notice,
                )
            }
            result
        }
    }

    // -- one artiste's meals -------------------------------------------------

    private fun openArtisteMeals(id: String) {
        val row = vm.current.dayList.firstOrNull { it.id == id } ?: return
        vm.update {
            copy(
                runner = runner.copy(
                    openArtisteMeals = id,
                    artisteMeals = runner.artisteMeals + (id to (runner.artisteMeals[id] ?: row.meals)),
                ),
            )
        }
    }

    private fun patchArtisteMeals(id: String, change: (List<MealBreak>) -> List<MealBreak>) = vm.update {
        val base = runner.artisteMeals[id] ?: dayList.firstOrNull { it.id == id }?.meals.orEmpty()
        copy(runner = runner.copy(artisteMeals = runner.artisteMeals + (id to change(base))))
    }

    private fun patchArtisteMeal(id: String, index: Int, change: MealBreak.() -> MealBreak) =
        patchArtisteMeals(id) { meals ->
            meals.mapIndexed { position, meal -> if (position == index) meal.change() else meal }
        }

    private fun saveArtisteMeals(id: String) {
        val meals = vm.current.runner.artisteMeals[id] ?: return
        vm.editDay(reload = false) {
            vm.repo.updateDayEntry(
                id,
                DayEntryPatch(shootDate = vm.current.shootDate, meals = meals),
            )
        }
    }
}

/**
 * The sign-off.
 *
 * The order matters and is not negotiable: **every** purchase order is raised
 * before the day is submitted, and if one fails the day is not submitted at
 * all. A retry reuses the orders already raised rather than raising a second
 * set — the guard lives for this mount only, as the web's does, so leaving the
 * page between a failure and a retry can still duplicate them.
 */
@Suppress("TooManyFunctions") // One handler per control on the sign-off.
internal class SubmitActions(private val vm: AdViewModel) {

    fun on(event: SubmitEvent) {
        when (event) {
            is SubmitEvent.PostType -> vm.update { copy(submit = submit.copy(postType = event.type)) }
            SubmitEvent.OpenSignaturePad -> openPad()
            SubmitEvent.CloseSignaturePad -> vm.update {
                copy(submit = submit.copy(signaturePadOpen = false, strokes = emptyList(), typedName = ""))
            }

            is SubmitEvent.Stroke -> vm.update {
                copy(submit = submit.copy(strokes = submit.strokes + listOf(event.points)))
            }

            is SubmitEvent.TypedName -> vm.update { copy(submit = submit.copy(typedName = event.text)) }
            SubmitEvent.ClearPad -> vm.update {
                copy(submit = submit.copy(strokes = emptyList(), typedName = ""))
            }

            is SubmitEvent.RenderSignature -> renderSignature(event)
            is SubmitEvent.Signed -> vm.update {
                copy(
                    submit = submit.copy(
                        signatureName = event.name,
                        signatureBytes = event.bytes,
                        pickedSignature = null,
                        signaturePadOpen = false,
                    ),
                )
            }

            is SubmitEvent.PickSaved -> vm.update {
                copy(
                    submit = submit.copy(
                        pickedSignature = event.id,
                        signatureBytes = null,
                        signaturePadOpen = false,
                    ),
                )
            }

            SubmitEvent.ClearSignature -> vm.update {
                copy(submit = submit.copy(signatureBytes = null, pickedSignature = null, signatureName = ""))
            }

            SubmitEvent.Submit -> submit()
            is SubmitEvent.PostPendingPo -> postPendingPo(event)
        }
    }

    /**
     * Renders what is in the pad to a PNG.
     *
     * Refuses rather than sending a non-image: the day's signature is stored
     * and printed on every voucher, so bytes that are not a PNG would show as
     * a broken image on a record nobody can re-sign once the day is locked.
     */
    private fun renderSignature(event: SubmitEvent.RenderSignature) {
        val pad = vm.current.submit
        if (!pad.padHasInk) return
        vm.update { copy(submit = submit.copy(rendering = true)) }
        vm.run {
            val bytes = vm.machine.renderSignature(
                strokes = pad.strokes,
                typedName = pad.typedName,
                widthPx = event.widthPx,
                heightPx = event.heightPx,
            )
            if (bytes == null) {
                vm.update { copy(submit = submit.copy(rendering = false)) }
                vm.fail(str(S.desktop_ad_signature_not_drawn))
                return@run
            }
            vm.update {
                copy(
                    submit = submit.copy(
                        rendering = false,
                        signaturePadOpen = false,
                        signatureBytes = bytes,
                        signatureName = pad.typedName,
                        pickedSignature = null,
                        strokes = emptyList(),
                        typedName = "",
                    ),
                )
            }
        }
    }

    /** Opens the pad, and brings the AD's saved signatures with it. */
    private fun openPad() {
        vm.update { copy(submit = submit.copy(signaturePadOpen = true, strokes = emptyList(), typedName = "")) }
        vm.run {
            (vm.repo.savedSignatures() as? ZillitResult.Success)?.let { rows ->
                vm.update { copy(submit = submit.copy(savedSignatures = rows.data)) }
            }
        }
    }

    private fun dayLabel(): String =
        vm.current.dayNumber?.let { str(S.desktop_ad_day_number, it) } ?: str(S.today)

    private fun dateLabel(): String = AdDates.formatDayFull(vm.current.shootDate)

    /**
     * Raises the day's purchase orders, then submits it.
     *
     * Only agencies with a **linked vendor** get one: an agency whose artistes
     * carry a name but no `agency_id` would produce an order with no vendor at
     * all, so the group is skipped and offered **Post PO** once it has been
     * linked. The day still submits.
     */
    @Suppress("ReturnCount") // Each gate refuses for its own reason.
    private fun submit() {
        val state = vm.current
        if (state.submit.submitting) return
        if (state.day?.status?.locked == true) return
        if (!SubmitRules.ready(state.dayList, state.canEditDay, state.submit.hasSignature)) {
            whyNotReady()
            return
        }
        val groups = state.agencyGroups.filter { it.hasVendor }
        val reference = state.poReference
        vm.update { copy(submit = submit.copy(submitting = true)) }
        vm.run {
            val raised = state.submit.createdPos.toMutableMap()
            groups.forEachIndexed { index, group ->
                if (raised.containsKey(group.key)) return@forEachIndexed
                val created = vm.repo.createPurchaseOrder(
                    group = group,
                    reference = reference,
                    suffix = index,
                    dayLabel = dayLabel(),
                    dateLabel = dateLabel(),
                    urgent = state.submit.postType == URGENT,
                )
                when (created) {
                    is ZillitResult.Success -> raised[group.key] = created.data
                    is ZillitResult.Failure -> {
                        // The day is not submitted: a submitted day with a
                        // missing agency order is worse than an unsubmitted one.
                        vm.update { copy(submit = submit.copy(submitting = false, createdPos = raised)) }
                        vm.report(created.error)
                        return@run
                    }
                }
            }
            sendSubmit(raised)
        }
    }

    private suspend fun sendSubmit(raised: Map<String, DayPurchaseOrder>) {
        val state = vm.current
        val request = SubmitDayRequest(
            shootDate = state.shootDate,
            typedName = state.submit.signatureName,
            signatureImage = state.submit.signatureBytes?.let { bytes ->
                com.zillit.desktop.feature.addashboard.data.signatureDataUrl(bytes)
            },
            // Always `e-sign`. `typed` can no longer be produced — it only
            // survives on records signed before the signature library — and
            // the wire value is what decides which proof the voucher prints.
            signStatus = E_SIGN,
            poReference = state.poReference,
            poPostType = state.submit.postType,
            purchaseOrders = raised.values.toList(),
            signedAt = vm.clock(),
        )
        when (val result = vm.repo.submitDay(request)) {
            is ZillitResult.Success -> {
                val skipped = state.agencyGroups.count { !it.hasVendor }
                vm.update {
                    copy(
                        submit = SubmitState(),
                        notice = if (skipped > 0) {
                            str(S.desktop_ad_day_submitted_with_skipped, state.poReference, skipped)
                        } else {
                            str(S.desktop_ad_day_submitted)
                        },
                    )
                }
                vm.loadDay()
            }

            is ZillitResult.Failure -> {
                vm.update { copy(submit = submit.copy(submitting = false, createdPos = raised)) }
                vm.report(result.error)
            }
        }
    }

    /** Says which of the three gates is shut, rather than a disabled button. */
    private fun whyNotReady() {
        val state = vm.current
        when {
            !state.canEditDay -> vm.refuse()
            state.dayList.none { it.isPresent } -> vm.fail(str(S.desktop_ad_nobody_present))
            else -> vm.fail(str(S.desktop_ad_signature_required))
        }
    }

    /**
     * Raises a skipped group's purchase order after the day was submitted.
     *
     * Offered only once the group has a vendor, which means re-linking each of
     * its artistes to a registered agency in the register first.
     */
    private fun postPendingPo(event: SubmitEvent.PostPendingPo) {
        val group = event.group
        if (!group.hasVendor) {
            vm.fail(str(S.desktop_ad_no_vendor_po_skipped))
            return
        }
        if (!vm.current.viewer.canPost) {
            vm.askForPostingRights()
            return
        }
        val saved = vm.current.day?.purchaseOrders.orEmpty()
        vm.update { copy(submit = submit.copy(postingPo = group.key)) }
        vm.run {
            val created = vm.repo.createPurchaseOrder(
                group = group,
                reference = vm.current.poReference,
                // The suffix counts what is already on the day, which is why a
                // card's printed reference can differ from the order's when an
                // earlier group was skipped.
                suffix = saved.size,
                dayLabel = dayLabel(),
                dateLabel = dateLabel(),
                urgent = vm.current.submit.postType == URGENT,
            )
            when (created) {
                is ZillitResult.Failure -> {
                    vm.update { copy(submit = submit.copy(postingPo = null)) }
                    vm.report(created.error)
                }

                is ZillitResult.Success -> {
                    val appended = vm.repo.appendDayPurchaseOrders(
                        vm.current.shootDate,
                        saved + created.data,
                    )
                    vm.update { copy(submit = submit.copy(postingPo = null)) }
                    when (appended) {
                        is ZillitResult.Success -> {
                            vm.notice(str(S.desktop_ad_po_posted, group.agency))
                            vm.loadDay()
                        }

                        is ZillitResult.Failure -> vm.report(appended.error)
                    }
                }
            }
        }
    }

    private companion object {
        const val URGENT = "urgent"
        const val E_SIGN = "e-sign"
    }
}
