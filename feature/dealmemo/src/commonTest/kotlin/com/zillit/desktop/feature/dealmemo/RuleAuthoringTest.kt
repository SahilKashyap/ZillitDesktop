@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleAuthoring
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleList
import com.zillit.desktop.feature.dealmemo.domain.rules.RuleLists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deal's own pay rules (`Step5Rates.jsx` `commitRuleRows` and its edit-load settling). */
class RuleAuthoringTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** Two rules share the id `ot`, so the second is `ot#1`. */
    private val paybreakdown = json(
        """{"overtimes":[
             {"id":"ot","label":"OT","rate_type":"multiplier","rate_amount":1.5,"basis":"hour","triggers":[{"after":600}]},
             {"id":"ot","label":"OT late","rate_type":"multiplier","rate_amount":2,"basis":"hour","triggers":[{"after":720}]}],
           "premiums":[{"id":"sixth","label":"6th day","rate_type":"multiplier","rate_amount":1.5,"basis":"day","triggers":[{"day_number":6,"consecutive":true}]}],
           "penalties":[]}""",
    )

    private val deal = DealForm(json("""{"union":"non_union","dealType":"daily"}"""))

    private fun opened(form: DealForm = deal) = RuleAuthoring.editorLists(form, null, paybreakdown)

    private fun overtimes(lists: RuleLists) = lists.lists.getValue(RuleList.Overtimes)

    @Test
    fun `saving an untouched grid authors nothing`() {
        val saved = RuleAuthoring.committed(deal, null, paybreakdown, opened())

        assertEquals(JsonObject(emptyMap()), saved.obj("ruleRowEdits"))
        assertTrue(saved.list("ruleRowRemovals").isEmpty())
        assertTrue(saved.list("ruleCustomRows").isEmpty())
        assertFalse(saved.flag("rulesCustomized"))
    }

    @Test
    fun `a changed repeat is an edit under its occurrence key, a dropped rule a removal, a new rule a custom row`() {
        val rows = overtimes(opened())
        val committed = RuleLists(
            mapOf(
                RuleList.Overtimes to listOf(
                    rows[0],
                    JsonObject(rows[1] + ("rate_amount" to JsonPrimitive(2.5))),
                    json("""{"id":"travel","label":"Travel","rate_type":"flat","rate_amount":20,"basis":"event","triggers":[{"after":0}]}"""),
                    json("""{"id":"custom-kept","label":"Kept","rate_type":"flat","rate_amount":5,"basis":"event","triggers":[{"after":0}]}"""),
                ),
                RuleList.Premiums to emptyList(),
            ),
        )
        val withOverrides = deal.with("ruleOverrides", json("""{"sixth":{"rate_amount":3},"ot":{"min":1}}"""))

        val saved = RuleAuthoring.committed(withOverrides, null, paybreakdown, committed)

        val edits = assertNotNull(saved.obj("ruleRowEdits"))
        assertEquals(setOf("ot#1"), edits.keys, "only the second `ot` changed")
        assertEquals("2.5", edits.getValue("ot#1").jsonObject["rate_amount"]?.jsonPrimitive?.content)
        assertEquals(listOf("sixth"), saved.list("ruleRowRemovals").map { it.jsonPrimitive.content })
        val customs = saved.objects("ruleCustomRows")
        assertEquals(listOf("Overtime", "Overtime"), customs.map { it["group"]?.jsonPrimitive?.content })
        assertEquals(
            listOf("custom-travel", "custom-kept"),
            customs.map { it["row"]?.jsonObject?.get("id")?.jsonPrimitive?.content },
            "a new rule is marked custom once",
        )
        assertEquals(json("""{"ot":{"min":1}}"""), saved.obj("ruleOverrides"), "a removed rule's override goes with it")
        assertTrue(saved.flag("rulesCustomized"))
    }

    @Test
    fun `the grid opens on the rules with legacy numeric overrides laid in`() {
        val form = deal.with("ruleOverrides", json("""{"sixth":{"rate_amount":"1.75","min":""}}"""))
        val sixth = opened(form).lists.getValue(RuleList.Premiums).single()
        assertEquals("1.75", sixth["rate_amount"]?.jsonPrimitive?.content)
        assertNull(sixth["min"], "a blank override field is not laid in")
    }

    @Test
    fun `a fresh load settles its switch from what really differs`() {
        val loaded = deal.with("rulesCustomized", JsonNull)

        assertEquals(false, RuleAuthoring.materialised(loaded.with("ruleOverrides", json("""{"ot":{"rate_amount":1.5}}""")), null, paybreakdown))
        assertEquals(true, RuleAuthoring.materialised(loaded.with("ruleOverrides", json("""{"ot":{"rate_amount":1.75}}""")), null, paybreakdown))
        assertEquals(true, RuleAuthoring.materialised(loaded.with("ruleRowEdits", json("""{"ot":{"id":"ot"}}""")), null, paybreakdown))
        assertNull(RuleAuthoring.materialised(deal.with("rulesCustomized", false), null, paybreakdown), "a saved switch is left alone")
        assertNull(RuleAuthoring.materialised(loaded, null, json("""{"overtimes":[],"premiums":[],"penalties":[]}""")), "nothing to settle against yet")
    }

    @Test
    fun `a reload's edits that match their rule are pruned`() {
        val rows = overtimes(opened())
        val reloaded = deal.with(
            "ruleRowEdits",
            JsonObject(mapOf("ot" to rows[0], "ot#1" to JsonObject(rows[1] + ("label" to JsonPrimitive("OT late (agreed)"))))),
        )

        val kept = assertNotNull(RuleAuthoring.pruned(reloaded, null, paybreakdown))
        assertEquals(setOf("ot#1"), kept.keys)
        assertNull(RuleAuthoring.pruned(deal.with("ruleRowEdits", kept), null, paybreakdown), "nothing left to drop")
        assertNull(RuleAuthoring.pruned(deal, null, paybreakdown))
    }

    @Test
    fun `reset is offered once a rule field moved from what was saved`() {
        val saved = mapOf("rulesCustomized" to JsonPrimitive(false), "ruleRowEdits" to JsonObject(emptyMap()))
        val form = deal.with(saved)
        assertFalse(RuleAuthoring.changedSinceSave(form, saved))
        assertTrue(RuleAuthoring.changedSinceSave(form.with("rulesCustomized", true), saved))
    }
}
