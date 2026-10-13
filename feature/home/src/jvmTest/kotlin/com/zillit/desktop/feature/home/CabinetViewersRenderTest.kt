package com.zillit.desktop.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.home.cabinet.homeCabinetViewers
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.data.NoticeDecryptor
import com.zillit.desktop.feature.home.domain.ToolGroup
import com.zillit.desktop.feature.home.domain.ToolsRepository
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test

/**
 * Composes the cabinet's notice-board viewer for real: the archived posts are
 * on the screen, and the composer, the reply links and the write items of the
 * long-press menu are not.
 */
@OptIn(ExperimentalTestApi::class)
class CabinetViewersRenderTest {

    private val now = System.currentTimeMillis()

    private val chat = """{"status":1,"message":"ok","data":[
        {"_id":"n1","message":"the crew call moved to seven","name":"Sam","sender":"me","created":${now - 60_000},
         "comments":[{"_id":"c1","message":"noted from the archive","sender":"them","created":${now - 30_000},
                      "deleted":$now}]},
        {"_id":"n2","message":"parking is behind the studio","name":"Aisha","sender":"them","created":$now}]}"""

    private val plain = object : NoticeDecryptor {
        override fun decryptFromHex(cipherHex: String): ZillitResult<String> = ZillitResult.Success(cipherHex)
        override fun encryptToHex(plaintext: String): ZillitResult<String> = ZillitResult.Success(plaintext)
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private class FakeTools(private val permissions: ProjectPermissions) : ToolsRepository {
        override suspend fun loadPermissions() = ZillitResult.Success(permissions)
        override suspend fun loadGroups() = ZillitResult.Success(emptyList<ToolGroup>())
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun viewer(identifier: String, permissions: ProjectPermissions, answer: (String) -> String = { chat }) =
        run {
            val engine = MockEngine { request ->
                respond(
                    answer(request.url.encodedPath),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val api = ApiClient(
                httpClient = HttpClientFactory.create({ Factory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            )
            homeCabinetViewers(
                apiClient = api,
                config = config,
                decrypt = plain,
                homeFeed = HomeFeedRepositoryImpl(api, config, plain, isAdmin = { false }),
                tools = FakeTools(permissions),
                board = HomeBoardContext(),
                nowMillis = { now },
                isAdmin = { true },
                currentUserId = { "me" },
            ).first { it.identifier == identifier }
        }

    private val infoTool = ProjectPermissions(
        listOf(ToolAccess("info_tool", unitId = "u-info", unitName = "info_label", canView = true, canPost = true)),
        isAdmin = true,
    )

    @Test
    fun `an archived board shows its posts and none of the means to add to it`() = runComposeUiTest {
        val info = viewer("info", infoTool)
        setContent {
            ZillitTheme { Column(Modifier.fillMaxSize()) { info.Content(null, Modifier.fillMaxSize()) } }
        }

        waitUntil(timeoutMillis = WAIT) {
            onAllNodesWithTextCount("the crew call moved to seven") > 0
        }
        onNodeWithText("parking is behind the studio").assertExists()
        // A reply the server stamped deleted with its parent still reads in the archive.
        onNodeWithText("noted from the archive").assertExists()
        // Not the composer, and not the live board's reply link.
        onNodeWithContentDescription("Attach a file").assertDoesNotExist()
        onNodeWithText("Reply").assertDoesNotExist()
    }

    @Test
    fun `the long-press menu offers Copy and nothing that writes`() = runComposeUiTest {
        val info = viewer("info", infoTool)
        setContent {
            ZillitTheme { Column(Modifier.fillMaxSize()) { info.Content(null, Modifier.fillMaxSize()) } }
        }
        waitUntil(timeoutMillis = WAIT) { onAllNodesWithTextCount("the crew call moved to seven") > 0 }

        // The author, and an admin: the live board would offer Edit, Delete, Pin, Forward and Read by.
        onNodeWithText("the crew call moved to seven").performTouchInput { longClick() }

        onNodeWithText("Copy").assertExists()
        listOf("Reply", "Forward…", "Read by…", "Edit", "Delete", "Pin").forEach {
            onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun `an expanding row opens the entry it was given`() = runComposeUiTest {
        val catering = viewer("catering", ProjectPermissions(emptyList()))
        setContent {
            ZillitTheme {
                Column(Modifier.fillMaxSize()) {
                    catering.Content(CabinetEntry("b1", "Breakfast"), Modifier.fillMaxSize())
                }
            }
        }

        waitUntil(timeoutMillis = WAIT) { onAllNodesWithTextCount("parking is behind the studio") > 0 }
        onNodeWithText("Breakfast").assertExists()
    }

    @Test
    fun `the production report chat without view rights says so instead of asking the server`() = runComposeUiTest {
        val noChatRights = ProjectPermissions(
            listOf(
                ToolAccess("production_report_tool", unitId = "u-pr", unitName = "production_report_label"),
                ToolAccess("production_report_chat_tool", canView = false),
            ),
        )
        val report = viewer("production_report", noChatRights)
        setContent {
            ZillitTheme { Column(Modifier.fillMaxSize()) { report.Content(null, Modifier.fillMaxSize()) } }
        }

        waitUntil(timeoutMillis = WAIT) {
            onAllNodesWithTextCount("You do not have viewing rights for this tool.", substring = true) > 0
        }
    }

    @Test
    fun `a tool the production never issued a unit for is an empty viewer`() = runComposeUiTest {
        val info = viewer("info", ProjectPermissions(emptyList()))
        setContent {
            ZillitTheme { Column(Modifier.fillMaxSize()) { info.Content(null, Modifier.fillMaxSize()) } }
        }

        waitUntil(timeoutMillis = WAIT) { onAllNodesWithTextCount("No Data found") > 0 }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(
        text: String,
        substring: Boolean = false,
    ): Int = onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private companion object {
        const val WAIT = 10_000L
    }
}
