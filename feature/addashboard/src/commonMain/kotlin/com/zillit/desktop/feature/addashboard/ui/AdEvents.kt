package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgencyGroup
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.DistributionEntry
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.ExportFormat
import com.zillit.desktop.feature.addashboard.domain.PayRule
import com.zillit.desktop.feature.addashboard.domain.PayRuleGroup
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.SigningRow
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementRef

/**
 * Everything the dashboard can be asked to do.
 *
 * Grouped per area, each group its own sealed interface, so a page only ever
 * reaches for its own events — and so a new page's events are one file's
 * change rather than a hundred-branch `when`.
 */
sealed interface AdEvent {

    // -- the frame ---------------------------------------------------------

    data class Open(val destination: AdDestination) : AdEvent

    /** The sidebar switcher — Reports, or everything to do with running the unit. */
    data class OpenWorkspace(val workspace: AdWorkspace) : AdEvent
    data object Refresh : AdEvent

    /**
     * Whether the dashboard's window is in front.
     *
     * The page on screen is read as rows land only while it is: the view model
     * outlives its window, and reading behind a closed one cleared rows nobody
     * saw.
     */
    data class WindowFocus(val focused: Boolean) : AdEvent
    data object ClearNotice : AdEvent
    data object DismissError : AdEvent

    /** The day every shoot-day page is showing. */
    data class ChangeDay(val shootDate: Long) : AdEvent
}

// -- the register ------------------------------------------------------------

sealed interface RegisterEvent : AdEvent {
    data class Search(val text: String) : RegisterEvent
    data class FilterCategory(val category: ArtisteCategory?) : RegisterEvent
    data class FilterStatus(val status: ArtisteStatus?) : RegisterEvent
    data class FilterEngagement(val engagement: EngagementType?) : RegisterEvent
    data class UnionOnly(val on: Boolean) : RegisterEvent

    /** A row looked at — its unread updates are read. */
    data class SeeArtiste(val id: String) : RegisterEvent

    /** Opens one artiste's own page. */
    data class OpenArtiste(val id: String) : RegisterEvent
    data object CloseArtiste : RegisterEvent

    data class AskDelete(val artiste: Artiste) : RegisterEvent
    data object ConfirmDelete : RegisterEvent
    data object CancelDelete : RegisterEvent

    data class Verify(val id: String) : RegisterEvent
    data object AskBlock : RegisterEvent
    data class BlockReason(val text: String) : RegisterEvent
    data object ConfirmBlock : RegisterEvent
    data object CancelBlock : RegisterEvent
    data class Unblock(val id: String) : RegisterEvent

    /** Mints and copies `<origin>/extras-portal/<token>`; the app never e-mails it. */
    data object CopyPortalLink : RegisterEvent
}

// -- the artiste form -------------------------------------------------------

sealed interface FormEvent : AdEvent {
    /** Opens the full form: blank, or on an artiste that already exists. */
    data class Open(val artisteId: String?) : FormEvent
    data object Close : FormEvent
    data class External(val on: Boolean) : FormEvent
    data class CrewSearch(val text: String) : FormEvent
    data class PickCrew(val userId: String) : FormEvent
    data class FirstName(val text: String) : FormEvent
    data class LastName(val text: String) : FormEvent
    data class Email(val text: String) : FormEvent
    data class Phone(val text: String) : FormEvent
    data class DateOfBirth(val text: String) : FormEvent
    data class Address(val text: String) : FormEvent
    data class Category(val category: ArtisteCategory) : FormEvent
    data class Engagement(val engagement: EngagementType) : FormEvent
    data class Union(val name: String) : FormEvent
    data class PickAgency(val agencyId: String, val name: String) : FormEvent
    data class Minor(val on: Boolean) : FormEvent
    data class RefNumber(val text: String) : FormEvent
    data class Bank(val field: BankField, val text: String) : FormEvent
    data class DocumentLabel(val index: Int, val text: String) : FormEvent
    data class DocumentDescription(val index: Int, val text: String) : FormEvent
    data object AddDocument : FormEvent
    data class RemoveDocument(val index: Int) : FormEvent

    /** `Save draft` stores `draft`; `Save & add to list` stores `pending`. */
    data class Save(val asDraft: Boolean) : FormEvent

    /** A draft artiste's **Add artiste**, which PATCHes them to pending. */
    data object Promote : FormEvent
}

/** Which bank field is being typed into. */
enum class BankField { AccountName, AccountNumber, SortCode, Iban, Swift, BankName }

// -- the agency-list import -------------------------------------------------

sealed interface ImportEvent : AdEvent {
    /** Opens the OS dialog, uploads the file and asks the server to read it. */
    data object PickFile : ImportEvent
    data object Clear : ImportEvent
    data class ToggleRow(val index: Int) : ImportEvent
    data object ToggleAll : ImportEvent
    data class EditRow(val index: Int, val field: ImportField, val text: String) : ImportEvent

    /** Commits to the register only. */
    data object Commit : ImportEvent

    /** Commits to the register **and** today's list. */
    data object CommitToday : ImportEvent
}

/** The three fields an import row can be fixed in place. */
enum class ImportField { FirstName, LastName, Email }

// -- the shoot day -----------------------------------------------------------

sealed interface DayEvent : AdEvent {
    data class Search(val text: String) : DayEvent

    /** Marks present, stamping *now* re-anchored on the day being run. */
    data class MarkPresent(val id: String) : DayEvent

    /** Marks present at a typed time, which is Attendance's own control. */
    data class MarkPresentAt(val id: String, val time: String) : DayEvent
    data class TimeDraft(val id: String, val time: String) : DayEvent
    data class MarkAbsent(val id: String) : DayEvent
    data class Unmark(val id: String) : DayEvent
    data object MarkAllPresent : DayEvent

    /** Marks out, stamping *now*; Today's List has no time field for it. */
    data class MarkOut(val id: String) : DayEvent
    data class MarkOutAt(val id: String, val time: String) : DayEvent
    data class ClearOut(val id: String) : DayEvent
    data object MarkAllOut : DayEvent

    data class SetDayType(val id: String, val dayType: String) : DayEvent
    data class RemoveFromDay(val id: String) : DayEvent

    data class OpenVoucher(val row: SupportingArtistDay) : DayEvent
    data object CloseVoucher : DayEvent
    data class OpenArtisteRow(val row: SupportingArtistDay) : DayEvent
    data object CloseArtisteRow : DayEvent
    data object OpenSummary : DayEvent
    data object CloseSummary : DayEvent
    data class Export(val format: ExportFormat) : DayEvent

    /** Re-sends one submitted day's approval e-mail. */
    data class SendApprovalLink(val id: String) : DayEvent
}

// -- Add Extras -------------------------------------------------------------

sealed interface AddExtrasEvent : AdEvent {
    /** 0 for today, 1 for tomorrow. */
    data class PickDay(val offset: Int) : AddExtrasEvent
    data class Search(val text: String) : AddExtrasEvent
    data class Toggle(val artisteId: String) : AddExtrasEvent
    data class CallTime(val time: String) : AddExtrasEvent
    data object StartNewArtiste : AddExtrasEvent
    data object CancelNewArtiste : AddExtrasEvent
    data class NewFirstName(val text: String) : AddExtrasEvent
    data class NewLastName(val text: String) : AddExtrasEvent
    data class NewEmail(val text: String) : AddExtrasEvent
    data class NewPhone(val text: String) : AddExtrasEvent
    data class NewCategory(val category: ArtisteCategory) : AddExtrasEvent
    data class NewAgency(val agencyId: String, val name: String) : AddExtrasEvent
    data object Submit : AddExtrasEvent
}

// -- Additionals ------------------------------------------------------------

sealed interface AdditionalsEvent : AdEvent {
    data class Search(val text: String) : AdditionalsEvent
    data class Expand(val id: String) : AdditionalsEvent

    /** A tile saves on click; the line is the deal's own. */
    data class ToggleAllowance(val id: String, val line: RateLine) : AdditionalsEvent
    data class ToggleFee(val id: String, val line: RateLine) : AdditionalsEvent
    data class NoteDraft(val id: String, val text: String) : AdditionalsEvent

    /** Notes save on blur, and only when changed. */
    data class SaveNote(val id: String) : AdditionalsEvent
    data class CustomDescription(val id: String, val text: String) : AdditionalsEvent
    data class CustomAmount(val id: String, val text: String) : AdditionalsEvent

    /** The custom payment saves only here, never from a tile. */
    data class SaveCustom(val id: String) : AdditionalsEvent
    data class CancelCustom(val id: String) : AdditionalsEvent
}

// -- the day runner ---------------------------------------------------------

sealed interface RunnerEvent : AdEvent {
    data class Step(val step: RunnerStep) : RunnerEvent

    /** Opens a day from the schedule, ungated and with an Additionals section. */
    data class OpenDay(val day: AdShootDay) : RunnerEvent
    data object CloseDay : RunnerEvent

    data class AddMeal(val type: String) : RunnerEvent
    data class MealStart(val index: Int, val time: String) : RunnerEvent
    data class MealEnd(val index: Int, val time: String) : RunnerEvent
    data class MealType(val index: Int, val type: String) : RunnerEvent
    data class RemoveMeal(val index: Int) : RunnerEvent

    /** Saves the day's meals, which the server copies onto every unsubmitted row. */
    data object SaveMeals : RunnerEvent

    data class OpenArtisteMeals(val id: String) : RunnerEvent
    data object CloseArtisteMeals : RunnerEvent
    data class ArtisteMealStart(val id: String, val index: Int, val time: String) : RunnerEvent
    data class ArtisteMealEnd(val id: String, val index: Int, val time: String) : RunnerEvent
    data class AddArtisteMeal(val id: String) : RunnerEvent
    data class RemoveArtisteMeal(val id: String, val index: Int) : RunnerEvent
    data class SaveArtisteMeals(val id: String) : RunnerEvent
}

// -- Submit Day -------------------------------------------------------------

sealed interface SubmitEvent : AdEvent {
    data class PostType(val type: String) : SubmitEvent
    data object OpenSignaturePad : SubmitEvent
    data object CloseSignaturePad : SubmitEvent

    /** One finished stroke, as the points it was drawn through. */
    data class Stroke(val points: List<Pair<Float, Float>>) : SubmitEvent
    data class TypedName(val text: String) : SubmitEvent
    data object ClearPad : SubmitEvent

    /** Renders what is in the pad to a PNG and keeps it as the sign-off. */
    data class RenderSignature(val widthPx: Int, val heightPx: Int) : SubmitEvent
    data class Signed(val name: String, val bytes: ByteArray) : SubmitEvent {
        override fun equals(other: Any?): Boolean =
            other is Signed && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = name.hashCode() * 31 + bytes.contentHashCode()
    }

    /** Picks one of the AD's saved signatures instead of drawing a new one. */
    data class PickSaved(val id: String) : SubmitEvent
    data object ClearSignature : SubmitEvent

    /** Raises each vendor-linked agency's PO, then submits the day. */
    data object Submit : SubmitEvent

    /** Raises a PO for a group that was skipped, after submission. */
    data class PostPendingPo(val group: AgencyGroup) : SubmitEvent
}

// -- agencies ---------------------------------------------------------------

sealed interface AgencyEvent : AdEvent {
    data class Search(val text: String) : AgencyEvent
    data class OpenAgency(val agency: Agency) : AgencyEvent
    data object CloseAgency : AgencyEvent
    data class StartForm(val agency: Agency?) : AgencyEvent
    data object CancelForm : AgencyEvent
    data class Name(val text: String) : AgencyEvent
    data class Email(val text: String) : AgencyEvent
    data class Contact(val text: String) : AgencyEvent
    data class Phone(val text: String) : AgencyEvent
    data class Address(val text: String) : AgencyEvent
    data class Bank(val field: BankField, val text: String) : AgencyEvent

    data class Save(val andVerify: Boolean) : AgencyEvent
    data class Verify(val id: String) : AgencyEvent
    data class AskDelete(val agency: Agency) : AgencyEvent
    data object ConfirmDelete : AgencyEvent
    data object CancelDelete : AgencyEvent
    data class OpenHistory(val agency: Agency) : AgencyEvent
    data object CloseHistory : AgencyEvent
}

// -- queries ----------------------------------------------------------------

sealed interface QueryEvent : AdEvent {
    data class Search(val text: String) : QueryEvent
    data class ShowResolved(val on: Boolean) : QueryEvent

    /** Opening a thread reads that one query's badge, not the whole unit. */
    data class OpenThread(val id: String) : QueryEvent
    data object CloseThread : QueryEvent
    data class Reply(val text: String) : QueryEvent
    data object SendReply : QueryEvent
    data class Resolve(val id: String) : QueryEvent
}

// -- Rates & Config ---------------------------------------------------------

sealed interface RatesEvent : AdEvent {
    data class DayRate(val categoryLabel: String, val amount: String) : RatesEvent
    data class AddDayType(val name: String) : RatesEvent
    data class DayTypeEdit(val index: Int, val field: DayTypeField, val text: String) : RatesEvent
    data class RemoveDayType(val index: Int) : RatesEvent

    data class AddRule(val group: PayRuleGroup) : RatesEvent
    data class EditRule(val id: String?) : RatesEvent
    data class PatchRule(val group: PayRuleGroup, val rule: PayRule) : RatesEvent
    data class RemoveRule(val group: PayRuleGroup, val id: String) : RatesEvent

    data object AddAllowance : RatesEvent
    data class AllowanceField(val index: Int, val field: MoneyField, val text: String) : RatesEvent
    data class RemoveAllowance(val index: Int) : RatesEvent
    data object AddFee : RatesEvent
    data class FeeField(val index: Int, val field: MoneyField, val text: String) : RatesEvent
    data class RemoveFee(val index: Int) : RatesEvent

    data object OpenDistributionPicker : RatesEvent
    data object CloseDistributionPicker : RatesEvent
    data class ToggleDistribution(val entry: DistributionEntry) : RatesEvent

    /** One Save writes the non-union document and every union document. */
    data object Save : RatesEvent

    data class StartVendorForm(val agency: Agency?) : RatesEvent
    data object CancelVendorForm : RatesEvent
    data class VendorName(val text: String) : RatesEvent
    data class VendorEmail(val text: String) : RatesEvent
    data class VendorContact(val text: String) : RatesEvent
    data class VendorPhone(val text: String) : RatesEvent
    data class VendorAddress(val text: String) : RatesEvent
    data class VendorBank(val field: BankField, val text: String) : RatesEvent

    /** Registers the agency — the only path that creates one, as on the web. */
    data object SaveVendor : RatesEvent
    data class AskDeleteVendor(val agency: Agency) : RatesEvent
    data object ConfirmDeleteVendor : RatesEvent
    data object CancelDeleteVendor : RatesEvent

    data class OpenUnionEditor(val agreementId: String?) : RatesEvent
    data object CloseUnionEditor : RatesEvent
    data class PickTerritory(val territory: String) : RatesEvent
    data class PickAgreement(val agreement: UnionAgreementRef) : RatesEvent
    data class UnionDayRate(val agreementId: String, val categoryLabel: String, val amount: String) : RatesEvent
    data class UnionAllowance(val agreementId: String, val rowId: String, val amount: String) : RatesEvent
    data class UnionFee(val agreementId: String, val rowId: String, val amount: String) : RatesEvent

    /** Re-pulls the live agreement and marks the page dirty for a fresh snapshot. */
    data class RefreshAgreement(val agreementId: String) : RatesEvent
    data class AskDeleteUnion(val agreement: UnionAgreementRef) : RatesEvent
    data object ConfirmDeleteUnion : RatesEvent
    data object CancelDeleteUnion : RatesEvent
}

/** Which field of a day type is being typed into. */
enum class DayTypeField { Name, WorkMinutes, MealMinutes }

/** Which field of an allowance or fee row is being typed into. */
enum class MoneyField { Name, Amount, NominalCode }

// -- reports ----------------------------------------------------------------

sealed interface ReportEvent : AdEvent {
    data class FilterKind(val kind: ReportKind?) : ReportEvent
    data class FilterStatus(val status: ReportStatus?) : ReportEvent
    data class Search(val text: String) : ReportEvent

    /** A day link from the schedule: one report opens, several let the AD pick. */
    data class OpenDayLink(val shootDate: Long) : ReportEvent
    data object ClearDayLink : ReportEvent

    data object OpenTemplatePicker : ReportEvent
    data object CloseTemplatePicker : ReportEvent

    /** Starts an unsaved draft from a template; the first save creates it. */
    data class StartFromTemplate(val template: ReportTemplate, val shootDate: Long?) : ReportEvent

    /** The schedule's **Generate report** on a submitted day. */
    data class GenerateForDay(val shootDate: Long, val template: ReportTemplate) : ReportEvent

    data class OpenReport(val row: ReportSummary) : ReportEvent
    data object CloseEditor : ReportEvent
    data class AskDelete(val row: ReportSummary) : ReportEvent
    data object ConfirmDelete : ReportEvent
    data object CancelDelete : ReportEvent

    data class Name(val text: String) : ReportEvent
    data class PickDate(val shootDate: Long) : ReportEvent
    data class DayNumber(val text: String) : ReportEvent

    /** One cell of the layout filled in. */
    data class Fill(val cellId: String, val rowId: String, val column: Int, val value: String) : ReportEvent

    /** A named header field, which lives on the meta rather than the fills. */
    data class HeaderField(val key: String, val value: String) : ReportEvent

    data class SelectCell(val cellId: String?) : ReportEvent
    data class AddRow(val cellId: String) : ReportEvent
    data class RemoveRow(val cellId: String, val rowId: String) : ReportEvent
    data class AddBlock(val type: com.zillit.desktop.feature.addashboard.domain.ReportBlockType) : ReportEvent
    data class RemoveBlock(val cellId: String) : ReportEvent
    data class BlockTitle(val cellId: String, val title: String) : ReportEvent
    data class ColumnLabel(val cellId: String, val columnId: String, val label: String) : ReportEvent
    data class ColumnType(
        val cellId: String,
        val columnId: String,
        val type: com.zillit.desktop.feature.addashboard.domain.ReportValueType,
    ) : ReportEvent

    data class AddColumn(val cellId: String) : ReportEvent
    data class RemoveColumn(val cellId: String, val columnId: String) : ReportEvent
    data object OpenRearrange : ReportEvent
    data object CloseRearrange : ReportEvent
    data class MoveBlock(val cellId: String, val up: Boolean) : ReportEvent

    data object Save : ReportEvent

    /** **Save as** creates a second draft for the same day. */
    data object SaveAsCopy : ReportEvent
    data object OpenPublish : ReportEvent
    data object ClosePublish : ReportEvent
    data object Publish : ReportEvent
    data object DismissSignaturesPending : ReportEvent
    data object OpenSaveAsTemplate : ReportEvent
    data object CloseSaveAsTemplate : ReportEvent
    data class TemplateName(val text: String) : ReportEvent
    data object SaveAsTemplate : ReportEvent
    data object DownloadPdf : ReportEvent

    /** Captures one cast signature on the AD's own device. */
    data class StartSigning(val row: SigningRow) : ReportEvent
    data object CancelSigning : ReportEvent
    /** One finished stroke in the cast-signing pad. */
    data class SignStroke(val points: List<Pair<Float, Float>>) : ReportEvent
    data class SignTypedName(val text: String) : ReportEvent
    data object ClearSignPad : ReportEvent

    /** Renders the cast pad and records the signature against its row. */
    data class RenderRowSignature(val widthPx: Int, val heightPx: Int) : ReportEvent
    data class SignRow(val name: String, val bytes: ByteArray) : ReportEvent {
        override fun equals(other: Any?): Boolean =
            other is SignRow && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = name.hashCode() * 31 + bytes.contentHashCode()
    }

    data object AskResetSignatures : ReportEvent
    data object ConfirmResetSignatures : ReportEvent
    data object CancelResetSignatures : ReportEvent

    data class OpenTemplate(val template: ReportTemplate) : ReportEvent
    data class DuplicateTemplate(val template: ReportTemplate) : ReportEvent
    data class DeleteTemplate(val template: ReportTemplate) : ReportEvent
    data object NewTemplate : ReportEvent
}

/** What leaves the dashboard rather than changing it. */
sealed interface AdEffect {
    data class Failed(val message: String) : AdEffect
    data class Saved(val path: String) : AdEffect
    data class Copied(val text: String) : AdEffect
}

/** Which attendance mark an event asks for. */
internal fun DayEvent.attendanceFor(): AttendanceStatus? = when (this) {
    is DayEvent.MarkPresent, is DayEvent.MarkPresentAt, DayEvent.MarkAllPresent -> AttendanceStatus.Present
    is DayEvent.MarkAbsent -> AttendanceStatus.Absent
    else -> null
}
