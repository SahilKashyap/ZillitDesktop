package com.zillit.desktop.feature.email.rules

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules list as the web's page runs it: load, add, toggle, reorder, delete, and the Drive picker. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailRulesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRules(initial: List<EmailRule>) : EmailRulesRepository {
        val rules = initial.toMutableList()
        val reorders = mutableListOf<List<String>>()
        override suspend fun rules() = ZillitResult.Success(rules.toList())
        override suspend fun create(rule: EmailRule): ZillitResult<EmailRule> {
            val saved = rule.copy(id = "new${rules.size}")
            rules += saved
            return ZillitResult.Success(saved)
        }
        override suspend fun update(rule: EmailRule): ZillitResult<EmailRule> {
            rules.replaceAll { if (it.id == rule.id) rule else it }
            return ZillitResult.Success(rule)
        }
        override suspend fun delete(ruleId: String): ZillitResult<Unit> {
            rules.removeAll { it.id == ruleId }
            return ZillitResult.Success(Unit)
        }
        override suspend fun reorder(ruleIds: List<String>) = ZillitResult.Success(Unit).also { reorders += ruleIds }
        override suspend fun executions(ruleId: String, limit: Int, skip: Int) =
            ZillitResult.Success(RuleExecutionPage(listOf(RuleExecution(id = "e$skip", ruleId = ruleId)), total = 2))
    }

    private fun rule(id: String, name: String = id) = EmailRule(
        id = id, name = name,
        conditions = listOf(RuleCondition(ConditionField.Always, ConditionOperator.IsTrue)),
        actions = listOf(RuleAction.MarkRead),
    )

    @Test
    fun `loads, toggles, reorders and deletes through the repository`() = runTest(dispatcher) {
        val repo = FakeRules(listOf(rule("a"), rule("b"), rule("c")))
        val vm = EmailRulesViewModel(repo)
        vm.onEvent(EmailRulesEvent.Load); runCurrent()
        assertEquals(listOf("a", "b", "c"), vm.state.value.rules.map { it.id })

        vm.onEvent(EmailRulesEvent.SetEnabled("b", false)); runCurrent()
        assertTrue(repo.rules.first { it.id == "b" }.enabled.not())

        vm.onEvent(EmailRulesEvent.Move("c", up = true)); runCurrent()
        assertEquals(listOf("a", "c", "b"), vm.state.value.rules.map { it.id })
        assertEquals(listOf(listOf("a", "c", "b")), repo.reorders)
        assertEquals(listOf(0, 1, 2), vm.state.value.rules.map { it.priority })

        vm.onEvent(EmailRulesEvent.Delete("a")); runCurrent()
        assertEquals(listOf("c", "b"), vm.state.value.rules.map { it.id })
    }

    @Test
    fun `a new rule is validated before it is sent, then appended with the server's id`() = runTest(dispatcher) {
        val repo = FakeRules(emptyList())
        val vm = EmailRulesViewModel(repo)
        vm.onEvent(EmailRulesEvent.Load); runCurrent()
        vm.onEvent(EmailRulesEvent.New); runCurrent()
        vm.onEvent(EmailRulesEvent.Save); runCurrent()
        assertEquals("Give the rule a name.", vm.state.value.error)
        assertNotNull(vm.state.value.editor, "an invalid draft stays open")

        vm.onEvent(EmailRulesEvent.Draft(rule("", name = "Receipts"))); runCurrent()
        vm.onEvent(EmailRulesEvent.Save); runCurrent()
        assertNull(vm.state.value.editor)
        assertEquals(listOf("new0"), vm.state.value.rules.map { it.id })
        assertEquals("Rule added", vm.state.value.info)
    }

    @Test
    fun `the Drive picker browses, walks up, and writes the chosen folder into the action`() = runTest(dispatcher) {
        val tree = mapOf<String?, List<DriveFolderOption>>(
            null to listOf(DriveFolderOption("r1", "Finance")),
            "r1" to listOf(DriveFolderOption("r2", "2026")),
            "r2" to emptyList(),
        )
        val source = DriveFolderSource { parent -> ZillitResult.Success(tree[parent].orEmpty()) }
        val vm = EmailRulesViewModel(FakeRules(emptyList()), driveFolders = source)
        vm.onEvent(EmailRulesEvent.New); runCurrent()
        val draft = EmailRule(name = "x", actions = listOf(RuleAction.SaveAttachmentsToDrive()))
        vm.onEvent(EmailRulesEvent.Draft(draft)); runCurrent()
        vm.onEvent(EmailRulesEvent.BrowseDrive(0, null)); runCurrent()
        assertEquals(listOf("Finance"), vm.state.value.editor!!.driveChildren.map { it.name })
        vm.onEvent(EmailRulesEvent.BrowseDrive(0, DriveFolderOption("r1", "Finance"))); runCurrent()
        vm.onEvent(EmailRulesEvent.BrowseDrive(0, DriveFolderOption("r2", "2026"))); runCurrent()
        assertEquals(listOf("Finance", "2026"), vm.state.value.editor!!.driveTrail.map { it.name })
        vm.onEvent(EmailRulesEvent.DriveUp); runCurrent()
        assertEquals(listOf("Finance"), vm.state.value.editor!!.driveTrail.map { it.name })
        vm.onEvent(EmailRulesEvent.PickDriveFolder(DriveFolderOption("r2", "2026"))); runCurrent()
        val action = vm.state.value.editor!!.draft.actions.single() as RuleAction.SaveAttachmentsToDrive
        assertEquals("r2" to "2026", action.driveFolderId to action.driveFolderName)
        assertNull(vm.state.value.editor!!.pickingForAction)
    }

    @Test
    fun `history pages until the total is reached`() = runTest(dispatcher) {
        val vm = EmailRulesViewModel(FakeRules(listOf(rule("a"))))
        vm.onEvent(EmailRulesEvent.Load); runCurrent()
        vm.onEvent(EmailRulesEvent.OpenHistory("a")); runCurrent()
        assertEquals(1, vm.state.value.history!!.executions.size)
        assertTrue(vm.state.value.history!!.hasMore)
        vm.onEvent(EmailRulesEvent.MoreHistory); runCurrent()
        assertEquals(listOf("e0", "e1"), vm.state.value.history!!.executions.map { it.id })
        assertTrue(!vm.state.value.history!!.hasMore)
    }
}
