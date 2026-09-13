package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.domain.SetupUpload
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.ImportStep
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * The Budget page and its import wizard, driven through the console's view
 * model over a mock engine — the web's `BudgetsTab` and `ImportBudgetWizard`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val posted = mutableListOf<Pair<String, String>>()
    private var committed = false
    private var refuseNextCommit = true

    private val v2 = """{"id":"b2","version":"v2","label":"Revised budget","status":"DRAFT","total":1000,""" +
        """"currency":"GBP","created_at":1750000000000}"""
    private val v1 =
        """{"id":"b1","version":"v1","label":"Sound budget","status":"LIVE","total":900,"currency":"GBP"}"""
    private val v3 = """{"id":"b3","version":"v3","label":"Sound budget","status":"DRAFT","total":650}"""

    private val lines = """[
        {"id":"h1","account":"1000","name":"Above the line","line_type":"header","rollup_total":1000},
        {"id":"s1","account":"1100","name":"Story","line_type":"section","head_id":"h1","rollup_total":1000},
        {"id":"c1","account":"1110","name":"Writers","line_type":"category","sec_id":"s1","amount":1000}
    ]"""

    // Bare, as the web reads it — `(res.data || res).parsed` — with no `value` wrapper.
    private val dryRun = """{"status":1,"data":{
        "parsed":{"currency":"GBP","sections":[{"section_id":"A","name":"Above the line"}],
          "headers":[{"code":"1000","name":"Story","amount":600,"section_id":"A"}],
          "nominals":[{"code":"1110","name":"Writers","amount":600,"parent_code":"1000"}],
          "uncodedItems":[{"name":"Contingency","amount":50}],"warnings":["Duplicate code 1110"]},
        "detectedFormat":"xlsx","sourceTemplate":"MMB",
        "attachment":{"name":"Sound_budget.xlsx","media":"budgets/sound.xlsx","bucket":"b","region":"eu"}}}"""

    private fun engine() = MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        val ok = HttpStatusCode.OK
        val (status, body) = when {
            request.method == HttpMethod.Post && path.contains("dry-run") -> {
                posted += "dry-run" to request.text()
                ok to dryRun
            }
            request.method == HttpMethod.Post && path.contains("commit") -> {
                posted += "commit" to request.text()
                if (refuseNextCommit) {
                    refuseNextCommit = false
                    HttpStatusCode.Conflict to """{"status":0,"message":"Version v3 already exists"}"""
                } else {
                    committed = true
                    ok to """{"status":1,"data":{"budget":$v3}}"""
                }
            }
            path.endsWith("/budgets/b2/lines") -> ok to """{"status":1,"data":$lines}"""
            path.contains("/lines") -> ok to """{"status":1,"data":[]}"""
            path.endsWith("/budgets") -> ok to """{"status":1,"data":[${if (committed) "$v3," else ""}$v2,$v1]}"""
            else -> ok to """{"status":1,"data":[]}"""
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun HttpRequestData.text(): String =
        (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    /** A picker that always chooses the same spreadsheet, and a store that always takes it. */
    private val files = object : AgreementFiles {
        var uploads = 0

        override suspend fun pick(
            purpose: SetupUpload,
            multiple: Boolean,
            onRefused: (String) -> Unit,
        ): List<PickedAgreementFile> = listOf(PickedAgreementFile("Sound_budget.xlsx", 2048, "picked"))

        override suspend fun upload(
            file: PickedAgreementFile,
            caption: String,
            purpose: SetupUpload,
        ): ZillitResult<AgreementDocument> {
            uploads++
            return ZillitResult.Success(AgreementDocument(name = file.name, media = "budgets/sound.xlsx"))
        }

        override val acceptsDrops: Boolean = true

        override fun adopt(
            name: String,
            bytes: ByteArray,
            purpose: SetupUpload,
            onRefused: (String) -> Unit,
        ): PickedAgreementFile? {
            val refusal = purpose.refuse(name, bytes.size.toLong())
            if (refusal != null) onRefused("$name: $refusal")
            return if (refusal == null) PickedAgreementFile(name, bytes.size.toLong(), "dropped") else null
        }
    }

    private fun viewModel(): AccountHubViewModel {
        val engine = engine()
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ BudgetMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return AccountHubViewModel(repository = repository, viewer = { accountant() }, agreementFiles = files)
    }

    private fun accountant() = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = AccountHubViewer.TOOL_IDENTIFIER,
                    enabled = true,
                    canView = true,
                    canPost = true,
                    canDownload = true,
                ),
            ),
        ),
        "u1",
        isAccountant = true,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.opened(): AccountHubViewModel {
        val model = viewModel()
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Budget))
        settle { model.state.value.budget.lines.isNotEmpty() }
        return model
    }

    private val AccountHubViewModel.budget get() = state.value.budget

    /**
     * The first card is selected, as the web's `list[0]` is — not the Live
     * one further down — and its top level opens with the chart's names.
     */
    @Test
    fun `the first version opens with its top level expanded`() = runTest(dispatcher) {
        val model = opened()
        assertEquals("b2", model.budget.selectedId)
        assertEquals(setOf("h1"), model.budget.openGroups)
        assertEquals("Writers", model.budget.lines.first { it.id == "c1" }.nameLabel)

        model.onEvent(AccountHubEvent.ToggleBudgetGroup("s1"))
        model.onEvent(AccountHubEvent.Refresh)
        settle { !model.budget.loading && !model.budget.linesLoading }
        assertEquals(setOf("h1", "s1"), model.budget.openGroups, "a refresh keeps what was opened")
        assertEquals("b2", model.budget.selectedId)

        model.onEvent(AccountHubEvent.ToggleBudgetGroup("h1"))
        assertEquals(setOf("s1"), model.budget.openGroups)
    }

    /**
     * Choosing a file only stages it; "Parse file" uploads it and previews the
     * dry run, which the server answers bare. The suggested version counts
     * every version the production has.
     */
    @Test
    fun `a file is staged, then parsed into a preview`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.OpenBudgetImport)
        settle { model.budget.import.existing != null }
        model.onEvent(AccountHubEvent.PickBudgetFile)
        settle { model.budget.import.picked != null }
        assertEquals(0, files.uploads, "nothing is uploaded until Parse file")
        assertEquals(ImportStep.Upload, model.budget.import.step)

        model.onEvent(AccountHubEvent.ParseBudgetFile)
        settle { model.budget.import.step == ImportStep.Preview }

        val import = model.budget.import
        val parsed = assertNotNull(import.parsed)
        assertEquals(1, parsed.headers.size)
        assertEquals(650.0, parsed.total, "headers plus uncoded, never the nominals")
        assertEquals(listOf("Duplicate code 1110"), parsed.warnings)
        assertEquals("xlsx", import.upload?.detectedFormat)
        assertEquals("MMB", import.upload?.sourceTemplate)
        assertEquals("v3", import.meta.version)
        assertEquals("Sound budget", import.meta.label)
        assertEquals("Imported from Sound_budget.xlsx", import.meta.description)
        assertTrue(posted.single().second.contains("budgets/sound.xlsx"), "the dry run names the stored file")

        model.onEvent(AccountHubEvent.BackToBudgetUpload)
        assertEquals(ImportStep.Upload, model.budget.import.step)
        assertNotNull(model.budget.import.picked, "going back keeps the chosen file")
    }

    /**
     * A refused commit stays on the preview with the reason; the next one lands
     * on the done step, refreshes the list at once and selects the new version.
     */
    @Test
    fun `a refused commit keeps the preview, a good one selects the new version`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.OpenBudgetImport)
        model.onEvent(AccountHubEvent.PickBudgetFile)
        settle { model.budget.import.picked != null }
        model.onEvent(AccountHubEvent.ParseBudgetFile)
        settle { model.budget.import.step == ImportStep.Preview }

        model.onEvent(AccountHubEvent.CommitBudgetImport)
        settle { model.budget.import.commitError != null }
        assertEquals(ImportStep.Preview, model.budget.import.step)
        assertTrue(assertNotNull(model.budget.import.commitError).contains("already exists"))

        model.onEvent(AccountHubEvent.CommitBudgetImport)
        settle { model.budget.selectedId == "b3" && model.budget.versions.size == 3 }
        assertEquals(ImportStep.Done, model.budget.import.step, "the wizard stays open on what was imported")
        assertEquals("b3", model.budget.import.created?.id)
        assertNull(model.budget.import.commitError)
        assertTrue(posted.last().second.contains("\"coa_mode\":\"append\""), "the chart mode is always sent")

        model.onEvent(AccountHubEvent.CloseBudgetImport)
        assertTrue(!model.budget.import.open)
    }

    /** A dropped file goes through the same rules as a picked one. */
    @Test
    fun `a dropped file is staged or refused like a picked one`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.OpenBudgetImport)
        assertTrue(model.budget.import.acceptsDrops)

        model.onEvent(AccountHubEvent.DropBudgetFile("notes.txt", ByteArray(10)))
        assertNull(model.budget.import.picked)
        assertTrue(assertNotNull(model.budget.import.parseError).startsWith("notes.txt"))

        model.onEvent(AccountHubEvent.DropBudgetFile("budget.csv", ByteArray(10)))
        assertEquals("budget.csv", model.budget.import.picked?.name)
        assertNull(model.budget.import.parseError, "a good file clears the last refusal")
    }
}

private class BudgetMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
