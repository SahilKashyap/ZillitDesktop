package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.forms.CustomFieldGroup
import com.zillit.desktop.core.forms.CustomFieldValue
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.purchaseorder.data.PoTemplateDto
import com.zillit.desktop.feature.purchaseorder.data.body
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoFormMode
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoReasonAction
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import com.zillit.desktop.feature.purchaseorder.ui.toForm
import com.zillit.desktop.feature.purchaseorder.ui.toRequest
import com.zillit.desktop.feature.purchaseorder.ui.toTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A template keeps everything the web's `handleSaveTemplate` keeps — company,
 * episode, delivery block and date, effective date, VAT treatment, custom fields
 * and attachments, as well as the lines — and its editor offers Save As and
 * Create & Submit PO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoTemplateFieldsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val address = PoAddress(name = "Stage manager", phoneCode = "+44", phone = "1753651700", line1 = "Pinewood")

    private val full = PoTemplate(
        id = "t-1",
        name = "Weekly camera package",
        vendorId = "v-1",
        vendorName = "Panavision",
        departmentId = "dept-cam",
        nominalCode = "2400",
        description = "Camera package",
        currency = "GBP",
        notes = "Deliver to stage",
        lines = listOf(
            PoLine("l1", "Body", 1.0, 100.0, "2400", 20.0, taxType = "vat", expenditureType = "Purchase"),
            PoLine("l2", "Body (half)", 1.0, 50.0, "2400", 20.0, amount = 50.0, splitParentId = "l1"),
        ),
        companyId = "co-1",
        episode = "Ep 4",
        deliveryAddressId = "addr-1",
        deliveryAddress = address,
        deliveryDate = 1_760_000_000_000,
        effectiveDate = 1_761_000_000_000,
        vatTreatment = "standard_20",
        customFields = listOf(
            CustomFieldGroup("Details", listOf(CustomFieldValue("Cost centre", "CC9", "Cost centre"))),
        ),
        attachments = listOf(
            PoAttachment(id = "a1", media = "po/quote.pdf", name = "quote.pdf", bucket = "b", region = "r"),
        ),
    )

    // -- the wire ------------------------------------------------------------------

    @Test
    fun `the save body carries every field the web's does`() {
        val body = full.body()

        assertEquals("Weekly camera package", body["template_name"]!!.jsonPrimitive.content)
        assertEquals("co-1", body["company_id"]!!.jsonPrimitive.content)
        assertEquals("Ep 4", body["episode"]!!.jsonPrimitive.content)
        assertEquals("addr-1", body["delivery_address_id"]!!.jsonPrimitive.content)
        assertEquals(1_760_000_000_000, body["delivery_date"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_761_000_000_000, body["effective_date"]!!.jsonPrimitive.content.toLong())
        assertEquals("standard_20", body["vat_treatment"]!!.jsonPrimitive.content)
        // The address keeps the camelCase keys the service stores verbatim.
        val sent = body["delivery_address"]!!.jsonObject
        assertEquals("+44", sent["phoneCode"]!!.jsonPrimitive.content)
        assertEquals("Pinewood", sent["line1"]!!.jsonPrimitive.content)
        assertEquals("CC9", body["custom_fields"]!!.jsonArray.single().jsonObject["fields"]!!.jsonArray.single()
            .jsonObject["value"]!!.jsonPrimitive.content)
        val file = body["attachments"]!!.jsonArray.single().jsonObject
        assertEquals("po/quote.pdf", file["media"]!!.jsonPrimitive.content)
        // The lines keep their ids, so a split child still names its parent.
        val lines = body["line_items"]!!.jsonArray
        assertEquals("l1", lines[1].jsonObject["split_parent_id"]!!.jsonPrimitive.content)
        assertEquals("l1", lines[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    /** A PATCH only clears what it names: a cleared company must go as null, not be left out. */
    @Test
    fun `cleared fields are sent as null so an edit can clear them`() {
        val body = full.copy(
            companyId = null, episode = null, deliveryAddressId = null, deliveryAddress = null,
            deliveryDate = null, effectiveDate = null, vatTreatment = null, attachments = emptyList(),
            customFields = emptyList(),
        ).body()

        listOf("company_id", "episode", "delivery_address_id", "delivery_date", "effective_date").forEach {
            assertEquals(JsonNull, body[it], it)
        }
        assertEquals("pending", body["vat_treatment"]!!.jsonPrimitive.content, "the web's fallback")
        assertTrue(body["delivery_address"]!!.jsonObject.isNotEmpty(), "an empty address object still goes")
        assertTrue(body["attachments"]!!.jsonArray.isEmpty())
        assertTrue(body["custom_fields"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a stored template reads back with every field, whatever shape the column came in`() {
        val dto = json.decodeFromString(
            PoTemplateDto.serializer(),
            """
            {"id":"t-1","template_name":"Weekly","vendor_id":"v-1","company_id":"co-1","episode":"Ep 4",
             "delivery_address":{"name":"Stage manager","phoneCode":"+44","postalCode":"SL0 0NH","line1":"Pinewood"},
             "delivery_address_id":"addr-1","delivery_date":1760000000000,"effective_date":"1761000000000",
             "vat_treatment":"standard_20",
             "custom_fields":[{"section":"Details","fields":[{"name":"Cost centre","value":"CC9"}]}],
             "attachments":"[{\"media\":\"po/quote.pdf\",\"name\":\"quote.pdf\"}]",
             "line_items":"[{\"id\":\"l1\",\"description\":\"Body\",\"quantity\":1,\"unit_price\":100}]"}
            """.trimIndent(),
        )
        val template = assertNotNull(dto.toDomain())

        assertEquals("co-1", template.companyId)
        assertEquals("Ep 4", template.episode)
        assertEquals("+44", template.deliveryAddress?.phoneCode)
        assertEquals("SL0 0NH", template.deliveryAddress?.postalCode)
        assertEquals("addr-1", template.deliveryAddressId)
        assertEquals(1_760_000_000_000, template.deliveryDate)
        assertEquals(1_761_000_000_000, template.effectiveDate)
        assertEquals("standard_20", template.vatTreatment)
        assertEquals("CC9", template.customFields.single().fields.single().value)
        assertEquals("po/quote.pdf", template.attachments.single().media)
        assertEquals("l1", template.lines.single().id)
    }

    @Test
    fun `an older template with a plain-text address or none still decodes`() {
        val text = json.decodeFromString(
            PoTemplateDto.serializer(),
            """{"id":"t-2","template_name":"Old","delivery_address":"Stage G, Pinewood"}""",
        ).toDomain()
        assertEquals("Stage G, Pinewood", text?.deliveryAddress?.line1)

        val blank = """{"id":"t-3","delivery_address":""}"""
        val none = json.decodeFromString(PoTemplateDto.serializer(), blank).toDomain()
        assertNull(none?.deliveryAddress)
        assertEquals(emptyList(), none?.attachments)
    }

    // -- the editor ----------------------------------------------------------------

    @Test
    fun `the editor restores the whole header and keeps the line ids`() {
        val form = full.toForm(asTemplate = true, fallbackDepartmentId = "mine", fallbackCurrency = "USD")

        assertEquals(PoFormMode.EditTemplate, form.mode)
        assertEquals("t-1", form.templateId)
        assertEquals("co-1", form.companyId)
        assertEquals("Ep 4", form.episode)
        assertEquals(1_761_000_000_000, form.effectiveDate)
        assertEquals(1_760_000_000_000, form.deliveryDate)
        assertEquals("standard_20", form.vatTreatment)
        assertEquals("addr-1", form.deliveryAddressId)
        assertEquals(address, form.deliveryAddress)
        assertEquals("CC9", form.customFields["Cost centre"])
        assertEquals(1, form.attachments.size)
        assertEquals(listOf("l1", "l2"), form.lines.map { it.id })
        assertEquals("l1", form.lines[1].splitParentId)
        assertEquals("dept-cam", form.departmentId)
    }

    /** The web's "Use" (`handleUseTemplate`) carries seven fields and the lines — not the rest. */
    @Test
    fun `use restores only what the web's use does, with fresh lines`() {
        val form = full.toForm(asTemplate = false, fallbackDepartmentId = "mine", fallbackCurrency = "USD")

        assertEquals(PoFormMode.NewOrder, form.mode)
        assertNull(form.templateId)
        assertEquals("Panavision", form.vendorName)
        assertEquals("Deliver to stage", form.notes)
        assertNull(form.companyId)
        assertEquals("", form.episode)
        assertNull(form.effectiveDate)
        assertTrue(form.deliveryAddress.isEmpty)
        assertTrue(form.attachments.isEmpty())
        assertTrue(form.lines.all { it.id == null }, "stored ids would read as lines the server already has")
    }

    /** The saved-address link survives only while the entry is still in the book, as the web's re-select does. */
    @Test
    fun `a deleted saved address falls back to the typed snapshot`() {
        val kept = full.toForm(true, "d", "GBP", savedAddressIds = setOf("addr-1"))
        assertEquals("addr-1", kept.deliveryAddressId)

        val gone = full.toForm(true, "d", "GBP", savedAddressIds = setOf("other"))
        assertNull(gone.deliveryAddressId)
        assertEquals(address, gone.deliveryAddress, "the snapshot stays")

        val unread = full.toForm(true, "d", "GBP", savedAddressIds = emptySet())
        assertEquals("addr-1", unread.deliveryAddressId, "no book read yet: keep the link, do not guess")
    }

    @Test
    fun `the form saves back as a template with every field, and Save As drops the id`() {
        val form = full.toForm(asTemplate = true, fallbackDepartmentId = "d", fallbackCurrency = "GBP")
        val layout = FormLayout(
            FormTemplate(
                listOf(
                    FormSection(
                        key = PoFormFields.DETAILS,
                        label = "Details",
                        order = 1,
                        fields = listOf(FormField(label = "Cost centre", name = "Cost centre", systemDefault = false)),
                    ),
                ),
            ),
        )

        val update = form.toTemplate("Weekly camera package", asNew = false, layout = layout)
        assertEquals("t-1", update.id)
        assertEquals("co-1", update.companyId)
        assertEquals(address, update.deliveryAddress)
        assertEquals("standard_20", update.vatTreatment)
        assertEquals("CC9", update.customFields.single().fields.single().value)
        assertEquals(form.attachments, update.attachments)

        assertEquals("", form.toTemplate("Copy", asNew = true, layout = layout).id)
    }

    // -- the actions ---------------------------------------------------------------

    private val accountant = PoViewer("acct-1", "department_accounts", "designation_production_accountant_accounts")

    private class Fake(private val templates: List<PoTemplate>, private val chart: Set<String> = emptySet()) :
        PurchaseOrderRepository by FlowFake(emptyList()) {
        val savedTemplates = mutableListOf<PoTemplate>()
        val created = mutableListOf<NewPurchaseOrder>()

        override suspend fun templates() = ZillitResult.Success(templates)
        override suspend fun chartCodes() = ZillitResult.Success(chart)
        override suspend fun saveTemplate(template: PoTemplate): ZillitResult<PoTemplate> {
            savedTemplates += template
            return ZillitResult.Success(template)
        }

        override suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit> {
            created += order
            return ZillitResult.Success(Unit)
        }
    }

    private fun TestScope.editing(repository: Fake): PurchaseOrderViewModel {
        val model = PurchaseOrderViewModel(repository, { accountant })
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.Templates))
        runCurrent()
        model.onEvent(PoEvent.EditTemplate("t-1"))
        runCurrent()
        return model
    }

    @Test
    fun `update writes over the template, Save As writes a second one under the new name`() = runTest(dispatcher) {
        val repository = Fake(listOf(full))
        val model = editing(repository)
        assertEquals(PoFormMode.EditTemplate, model.state.value.form?.mode)

        model.onEvent(PoEvent.SaveAsTemplate("Weekly camera package"))
        runCurrent()
        assertEquals("t-1", repository.savedTemplates.single().id)
        assertEquals("co-1", repository.savedTemplates.single().companyId)

        // Back in the editor, "Save As" asks for a name first.
        model.onEvent(PoEvent.EditTemplate("t-1"))
        runCurrent()
        model.onEvent(PoEvent.SaveTemplateAs)
        val prompt = model.state.value.prompt as PoPrompt.WithReason
        assertEquals(PoReasonAction.SaveTemplateAs, prompt.action)
        assertEquals("Weekly camera package", prompt.reason, "seeded with the name being edited")
        model.onEvent(PoEvent.UpdatePrompt(prompt.copy(reason = "Weekly camera package (copy)")))
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()

        val copy = repository.savedTemplates.last()
        assertEquals("", copy.id, "no id: the repository posts a new template")
        assertEquals("Weekly camera package (copy)", copy.name)
        assertEquals("addr-1", copy.deliveryAddressId)
        assertEquals(2, repository.savedTemplates.size)
    }

    @Test
    fun `Create and Submit raises an order from the template editor and leaves the template alone`() =
        runTest(dispatcher) {
            val repository = Fake(listOf(full))
            val model = editing(repository)

            model.onEvent(PoEvent.SubmitForm)
            runCurrent()

            assertTrue(repository.savedTemplates.isEmpty(), "the template is not rewritten")
            val order = repository.created.single()
            assertEquals("v-1", order.vendorId)
            assertEquals("co-1", order.companyId)
            assertEquals("ACCT_ENTERED", order.status)
            assertEquals(address, order.deliveryAddress)
        }

    @Test
    fun `Save as Draft writes the template editor as a new draft order and leaves the template alone`() =
        runTest(dispatcher) {
            val repository = Fake(listOf(full))
            val model = editing(repository)

            model.onEvent(PoEvent.SaveDraft)
            runCurrent()

            assertTrue(repository.savedTemplates.isEmpty(), "the template is not rewritten")
            val draft = repository.created.single()
            assertEquals("DRAFT", draft.status)
            assertEquals("v-1", draft.vendorId)
            assertEquals(address, draft.deliveryAddress)
            assertNull(model.state.value.form, "the editor closes, as after every other save")
        }

    // -- the nominal wrap ------------------------------------------------------------

    private val chart = setOf("2400")
    private val unknown = full.copy(
        nominalCode = "9999",
        lines = listOf(
            PoLine("l1", "Body", 1.0, 100.0, "9999", 20.0),
            PoLine("l2", "Lens", 1.0, 50.0, "2400", 20.0),
            PoLine("l3", "Tape", 1.0, 5.0, null, 20.0),
        ),
    )

    @Test
    fun `an order body wraps the header and every line the chart does not know`() {
        val form = unknown.toForm(asTemplate = true, fallbackDepartmentId = "d", fallbackCurrency = "GBP")
        val layout = FormLayout(FormTemplate())

        val wrapped = form.toRequest(status = null, layout = layout, chart = chart)
        assertEquals("[[9999]]", wrapped.nominalCode)
        assertEquals(listOf("[[9999]]", "2400", null), wrapped.lines.map { it.nominalCode })
        // The form on screen keeps the clean code, and a chart that did not load wraps nothing.
        assertEquals("9999", form.nominalCode)
        val unread = form.toRequest(status = null, layout = layout)
        assertEquals("9999", unread.nominalCode)
        assertEquals(listOf("9999", "2400", null), unread.lines.map { it.nominalCode })
    }

    @Test
    fun `a template body wraps the same way`() {
        val form = unknown.toForm(asTemplate = true, fallbackDepartmentId = "d", fallbackCurrency = "GBP")

        val wrapped = form.toTemplate("Copy", asNew = true, layout = FormLayout(FormTemplate()), chart = chart)
        assertEquals("[[9999]]", wrapped.nominalCode)
        assertEquals(listOf("[[9999]]", "2400", null), wrapped.lines.map { it.nominalCode })
    }

    @Test
    fun `submit, draft and template saves all send the wrapped codes`() = runTest(dispatcher) {
        val repository = Fake(listOf(unknown), chart)
        val model = editing(repository)

        model.onEvent(PoEvent.SubmitForm)
        runCurrent()
        model.onEvent(PoEvent.EditTemplate("t-1"))
        runCurrent()
        model.onEvent(PoEvent.SaveDraft)
        runCurrent()
        model.onEvent(PoEvent.EditTemplate("t-1"))
        runCurrent()
        model.onEvent(PoEvent.SaveAsTemplate("Weekly camera package"))
        runCurrent()

        assertEquals(listOf("ACCT_ENTERED", "DRAFT"), repository.created.map { it.status })
        repository.created.forEach { order ->
            assertEquals("[[9999]]", order.nominalCode)
            assertEquals("[[9999]]", order.lines.first().nominalCode)
            assertEquals("2400", order.lines[1].nominalCode)
        }
        val template = repository.savedTemplates.single()
        assertEquals("[[9999]]", template.nominalCode)
        assertEquals("[[9999]]", template.lines.first().nominalCode)
    }

    @Test
    fun `a chart that failed to load wraps nothing on save`() = runTest(dispatcher) {
        val repository = Fake(listOf(unknown), chart = emptySet())
        val model = editing(repository)

        model.onEvent(PoEvent.SubmitForm)
        runCurrent()

        assertEquals("9999", repository.created.single().nominalCode)
    }

    @Test
    fun `a blank name for Save As is refused in the prompt's own words`() = runTest(dispatcher) {
        val repository = Fake(listOf(full))
        val model = editing(repository)
        model.onEvent(PoEvent.SaveTemplateAs)
        val prompt = model.state.value.prompt as PoPrompt.WithReason
        model.onEvent(PoEvent.UpdatePrompt(prompt.copy(reason = "  ")))
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()

        assertTrue(repository.savedTemplates.isEmpty())
        assertTrue(model.state.value.prompt is PoPrompt.WithReason, "the prompt stays so the name can be fixed")
    }
}
