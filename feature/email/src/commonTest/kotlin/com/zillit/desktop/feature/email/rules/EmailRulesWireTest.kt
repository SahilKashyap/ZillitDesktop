package com.zillit.desktop.feature.email.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/v2/email-rules` read and written the way the web and Android speak it. */
class EmailRulesWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a rule row reads its conditions and actions, ids and numbers however they arrive`() {
        val payload = json.parseToJsonElement(
            """{"data":{"rules":[{"id":"r1","name":"Invoices","enabled":true,"priority":"2","stop_on_match":true,
                "match_type":"any","conditions":[{"field":"subject","operator":"contains","value":"invoice"},
                {"field":"has_attachment","operator":"is_true"},{"field":"nonsense","operator":"is"}],
                "actions":[{"type":"save_attachments_to_drive","drive_folder_id":"f9","extensions":[".PDF","png"],"max_size_bytes":"1024"},
                {"type":"move_to_folder","folder_name":"Finance"},{"type":"forward_to","forward_to_email":"a@b.co"},{"type":"mark_read"}],
                "created":1700000000000}]}}""",
        )
        val rules = payload.listRows("rules").map {
            json.decodeFromJsonElement(EmailRuleDto.serializer(), it).toDomain()!!
        }
        val rule = rules.single()
        assertEquals("r1", rule.id)
        assertEquals(2, rule.priority)
        assertEquals(RuleMatchType.Any, rule.matchType)
        assertTrue(rule.stopOnMatch)
        assertEquals(2, rule.conditions.size, "an unknown field is dropped, not crashed on")
        assertEquals(ConditionOperator.IsTrue, rule.conditions[1].operator)
        val save = rule.actions[0] as RuleAction.SaveAttachmentsToDrive
        assertEquals(listOf("pdf", "png"), save.extensions)
        assertEquals(1024L, save.maxSizeBytes)
        assertEquals("Finance", (rule.actions[1] as RuleAction.MoveToFolder).folderName)
        assertEquals(RuleAction.MarkRead, rule.actions[3])
        assertEquals(1700000000000L, rule.createdMillis)
    }

    @Test
    fun `the write body carries values only where the field takes one, and action payloads by type`() {
        val rule = EmailRule(
            name = " Receipts ",
            matchType = RuleMatchType.All,
            stopOnMatch = true,
            conditions = listOf(
                RuleCondition(ConditionField.From, ConditionOperator.EndsWith, " @shop.com "),
                RuleCondition(ConditionField.HasAttachment, ConditionOperator.IsTrue, "ignored"),
            ),
            actions = listOf(
                RuleAction.SaveAttachmentsToDrive("f1", "Receipts", listOf("pdf"), 0),
                RuleAction.ForwardTo("ops@partner.com"),
                RuleAction.MarkRead,
            ),
        )
        val wire = rule.toWire()
        assertEquals("Receipts", wire["name"]!!.jsonPrimitive.content)
        assertEquals("all", wire["match_type"]!!.jsonPrimitive.content)
        val conditions = wire["conditions"] as JsonArray
        assertEquals("@shop.com", (conditions[0] as JsonObject)["value"]!!.jsonPrimitive.content)
        assertNull((conditions[1] as JsonObject)["value"], "is_true carries no value")
        val actions = wire["actions"] as JsonArray
        val save = actions[0] as JsonObject
        assertEquals("f1", save["drive_folder_id"]!!.jsonPrimitive.content)
        assertNull(save["max_size_bytes"], "0 means no limit and is not sent")
        assertEquals(JsonPrimitive("pdf"), (save["extensions"] as JsonArray)[0])
        assertEquals("ops@partner.com", (actions[1] as JsonObject)["forward_to_email"]!!.jsonPrimitive.content)
        assertEquals(1, (actions[2] as JsonObject).size, "mark_read is the type alone")
    }

    @Test
    fun `validation names the first thing missing, and folders exclude the system ones`() {
        assertEquals("Give the rule a name.", EmailRule().validationIssue)
        assertEquals("Every condition with a value needs one.", EmailRule(name = "x").validationIssue)
        val always = listOf(RuleCondition(ConditionField.Always, ConditionOperator.IsTrue))
        val noFolder = EmailRule(name = "x", conditions = always, actions = listOf(RuleAction.SaveAttachmentsToDrive()))
        assertEquals("Pick the Drive folder to save attachments to.", noFolder.validationIssue)
        val badMove = EmailRule(name = "x", conditions = always, actions = listOf(RuleAction.MoveToFolder("inbox")))
        assertFalse(badMove.isValid)
        val ok = EmailRule(name = "x", conditions = always, actions = listOf(RuleAction.MoveToFolder("Finance")))
        assertTrue(ok.isValid)
        val folders = selectableMoveFolders(listOf("INBOX", "Finance", "Sent", "Archive", ""))
        assertEquals(listOf("Finance", "Archive"), folders)
        assertEquals(listOf("pdf", "docx"), RuleAction.SaveAttachmentsToDrive.parseExtensions("pdf, .DOCX ,pdf,"))
    }

    @Test
    fun `a condition keeps its operator across fields when it still applies`() {
        val c = RuleCondition(ConditionField.Subject, ConditionOperator.Contains, "x")
        assertEquals(ConditionOperator.Contains, c.withField(ConditionField.Body).operator)
        val flipped = c.withField(ConditionField.HasAttachment)
        assertEquals(ConditionOperator.IsTrue, flipped.operator)
        assertEquals("", flipped.value)
        val rule = EmailRule(conditions = listOf(c), actions = listOf(RuleAction.MarkRead))
        assertEquals("subject contains \"x\" → Mark as read", rule.summary())
    }

    @Test
    fun `executions read their status and results, and a bare array is a page of its own size`() {
        val payload = json.parseToJsonElement(
            """[{"_id":"e1","rule_id":"r1","status":"partial","attempts":2,
                "results":[{"type":"move_to_folder","status":"success"},
                {"type":"forward_to","status":"failed","detail":"bounced"}]}]""",
        )
        val rows = payload.listRows("executions").map {
            json.decodeFromJsonElement(RuleExecutionDto.serializer(), it).toDomain()!!
        }
        assertEquals(ExecutionStatus.Partial, rows.single().status)
        assertEquals("bounced", rows.single().results[1].detail)
        assertEquals(1, payload.totalOr(rows.size))
        assertEquals(7, json.parseToJsonElement("""{"data":{"executions":[],"total":7}}""").totalOr(0))
    }
}
