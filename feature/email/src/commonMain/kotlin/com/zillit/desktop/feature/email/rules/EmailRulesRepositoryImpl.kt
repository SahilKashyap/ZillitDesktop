package com.zillit.desktop.feature.email.rules

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.data.body
import com.zillit.desktop.feature.email.data.flagBody
import com.zillit.desktop.feature.email.data.query
import com.zillit.desktop.feature.email.domain.MailboxScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `${EMAIL_BASE_URL}/v2/email-rules` — the web's `emailRulesApi.js`. The
 * shared Accounts mailbox keeps its own rules: while it is active every call
 * carries `use_project_account_mailbox` — a query parameter on GET/DELETE,
 * a body key on POST/PUT — exactly as the web scopes them.
 */
class EmailRulesRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val scope: MailboxScope = MailboxScope.Personal,
) : EmailRulesRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val base get() = "${config.apiV2(ZillitService.Email)}email-rules"

    override suspend fun rules(): ZillitResult<List<EmailRule>> =
        request(HttpVerb.Get, base).map { payload ->
            payload.listRows("rules").mapNotNull { row ->
                runCatching { json.decodeFromJsonElement(EmailRuleDto.serializer(), row) }.getOrNull()?.toDomain()
            }.sortedBy { it.priority }
        }

    override suspend fun create(rule: EmailRule): ZillitResult<EmailRule> =
        request(HttpVerb.Post, base, jsonBody(rule.toWire())).flatMapRule(rule)

    override suspend fun update(rule: EmailRule): ZillitResult<EmailRule> =
        request(HttpVerb.Put, "$base/${rule.id}", jsonBody(rule.toWire())).flatMapRule(rule)

    override suspend fun delete(ruleId: String): ZillitResult<Unit> =
        request(HttpVerb.Delete, "$base/$ruleId").map { }

    override suspend fun reorder(ruleIds: List<String>): ZillitResult<Unit> =
        request(
            HttpVerb.Post,
            "$base/reorder",
            jsonBody(
                buildJsonObject {
                    put("rule_ids", buildJsonArray { ruleIds.forEach { add(JsonPrimitive(it)) } })
                },
            ),
        ).map { }

    override suspend fun executions(ruleId: String, limit: Int, skip: Int): ZillitResult<RuleExecutionPage> =
        request(HttpVerb.Get, "$base/$ruleId/executions?limit=$limit&skip=$skip").map { payload ->
            val rows = payload.listRows("executions").mapNotNull { row ->
                runCatching { json.decodeFromJsonElement(RuleExecutionDto.serializer(), row) }.getOrNull()?.toDomain()
            }
            RuleExecutionPage(rows, payload.totalOr(rows.size))
        }

    private suspend fun request(verb: HttpVerb, url: String, body: JsonElement? = null): ZillitResult<JsonElement> =
        apiClient.request(
            verb = verb,
            url = url,
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            body = when {
                body is JsonObject -> scope.body(body)
                body == null && verb != HttpVerb.Get -> scope.flagBody()
                else -> body
            },
        )

    /** The saved rule as the server echoes it; the one we sent, with its id kept, when the echo is bare. */
    private fun ZillitResult<JsonElement>.flatMapRule(sent: EmailRule): ZillitResult<EmailRule> = when (this) {
        is ZillitResult.Failure -> this
        is ZillitResult.Success -> {
            val echoed = data.record("rule")?.let { obj ->
                runCatching { json.decodeFromJsonElement(EmailRuleDto.serializer(), obj) }.getOrNull()?.toDomain()
            }
            when {
                echoed != null -> ZillitResult.Success(echoed)
                sent.id.isNotBlank() -> ZillitResult.Success(sent)
                else -> ZillitResult.Failure(ZillitError.Unknown("The server saved the rule but did not say which."))
            }
        }
    }
}
