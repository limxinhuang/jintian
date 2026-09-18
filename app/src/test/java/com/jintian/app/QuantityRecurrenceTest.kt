package com.jintian.app

import com.jintian.app.data.*
import com.jintian.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class QuantityRecurrenceTest {
    private val date = LocalDate.of(2026, 9, 18)
    private fun task(target: String = "100") = Task("r", "读书", seriesId = "series", targetAmount = target, unit = "页")
    private fun running(target: String = "100", others: List<Task> = emptyList()): AppState {
        val initial = AppState(listOf(Goal("g", "阅读目标", date, date.plusDays(14), pending = listOf(task(target)) + others)))
        val locked = GoalRules.lockNext(initial, "g")
        return GoalRules.start(locked, "g", "r", 1000).also(GoalRules::validate)
    }
    private fun finish(state: AppState, amount: String): AppState = GoalRules.complete(state, state.active!!.task.id, 2000, amount).also(GoalRules::validate)
    private fun next(state: AppState) = GoalRules.start(state, "g", state.goal("g").next!!.id, 3000)

    @Test fun partialCompletionStoresIncrementAndContinuesSameSeries() {
        val initial = running()
        val partial = finish(initial, "15")
        val goal = partial.goal("g")
        assertEquals("15", goal.done.single().completedAmount)
        assertEquals("15", Quantity.format(goal.accumulated(task())))
        assertEquals(1, goal.completedCount(task()))
        assertEquals(initial.goal("g").pending, goal.pending)
        assertEquals(initial.goal("g").next, goal.next)
        assertFalse(goal.done.single().seriesEnded)
        assertNull(partial.active)
    }

    @Test fun exactTargetAutomaticallyClosesFutureCopiesAndPreservesHistory() {
        val partial = finish(running(), "15")
        val ended = finish(next(partial), "85")
        val goal = ended.goal("g")
        assertEquals(listOf("15", "85"), goal.done.map { it.completedAmount })
        assertEquals("100", Quantity.format(goal.accumulated(task())))
        assertTrue(goal.done.all { it.seriesEnded })
        assertNull(goal.next)
        assertTrue(goal.pending.isEmpty())
        assertTrue(GoalRules.canArchive(ended, goal))
    }

    @Test fun excessAmountIsNotClampedOrDiscarded() {
        val ended = finish(running(), "120.5")
        assertEquals("120.5", ended.goal("g").done.single().completedAmount)
        assertTrue(ended.goal("g").done.single().seriesEnded)
        assertEquals("120.5", Quantity.format(ended.goal("g").accumulated(task())))
    }

    @Test fun decimalArithmeticHasNoRoundingThresholdError() {
        val partial = finish(running("0.3"), "0.1")
        val ended = finish(next(partial), "0.2")
        assertEquals("0.3", Quantity.format(ended.goal("g").accumulated(task("0.3"))))
        assertTrue(ended.goal("g").done.all { it.seriesEnded })
        assertEquals("0.000001", Quantity.normalize("0.000001"))
        assertEquals("1.5", Quantity.normalize("001.5000"))
    }

    @Test fun reachingTargetLeavesOtherLockedTasksAndPendingOrderUnchanged() {
        val otherLoop = Task("other", "另一个循环", seriesId = "other-series", targetAmount = "200", unit = "页")
        val initial = running(others = listOf(Task("s", "单次"), otherLoop, Task("tail", "末尾")))
        val ended = finish(initial, "100")
        assertEquals(initial.goal("g").next, ended.goal("g").next)
        assertEquals(listOf(otherLoop, Task("tail", "末尾")), ended.goal("g").pending)
        assertEquals("0", Quantity.format(ended.goal("g").accumulated(otherLoop)))
    }

    @Test fun invalidOrMissingIncrementNeverCompletesTask() {
        val initial = running()
        listOf("", "0", "-1", "NaN", "Infinity", "1e3", "1,5", "0.0000001", "1234567890123").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { finish(initial, value) }
        }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(initial, "r") }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.completeAndEndRecurrence(initial, "r") }
        assertEquals("r", initial.active!!.task.id)
        assertTrue(initial.goal("g").done.isEmpty())
    }

    @Test fun nonNumericTaskCannotAcquireAmountsByAccident() {
        val single = running().copy(active = ActiveTask("g", Task("single", "单次"), 1000))
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(single, "single", amount = "2") }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validateTask(Task("s", "单次", targetAmount = "100", unit = "页")) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validateTask(Task("m", "手动", seriesId = "m", completedAmount = "1")) }
    }

    @Test fun invalidCreationValuesAndIncompleteMetadataAreRejected() {
        listOf(task("0"), task("-2"), task("").copy(unit = "页"), task().copy(unit = ""), task().copy(unit = null), task().copy(unit = "页\n数"), task().copy(unit = "这是一个超过十二个文字的单位")).forEach {
            assertThrows(IllegalArgumentException::class.java) { GoalRules.validateTask(it) }
        }
        assertEquals("公里", Quantity.unit(" 公里 "))
    }

    @Test fun validationRejectsConflictingTargetsUnitsAndPrematureAmounts() {
        val initial = running()
        val g = initial.goal("g")
        listOf(g.next!!.copy(targetAmount = "101"), g.next.copy(unit = "公里"), g.next.copy(completedAmount = "1")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(initial.copy(goals = listOf(g.copy(next = invalid)))) }
        }
        val partial = finish(initial, "15")
        val history = partial.goal("g").done
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(partial.copy(goals = listOf(partial.goal("g").copy(done = history.map { it.copy(completedAmount = null) })))) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(partial.copy(goals = listOf(partial.goal("g").copy(done = history.map { it.copy(completedAmount = "100") })))) }
    }

    @Test fun bothFormatsPreservePartialActiveAndEndedQuantities() {
        val partial = finish(running(), "15.25")
        val active = next(partial)
        val ended = finish(active, "84.75")
        listOf(partial, active, ended).forEach { original ->
            assertEquals(original, StateCodec.decode(StateCodec.encode(original)))
            assertEquals(original, CsvBackup.decode(CsvBackup.encode(original)).state)
        }
        val restored = CsvBackup.decode(CsvBackup.encode(active)).state
        assertEquals(ended, finish(restored, "84.75"))
    }

    @Test fun malformedCsvQuantitiesFailBeforeRestore() {
        val partial = finish(running(), "15")
        val csv = CsvBackup.encode(partial).toString(Charsets.UTF_8)
        listOf(csv.replace("\"100\",\"页\"", "\"0\",\"页\""),
            csv.replace("\"100\",\"页\",\"15\"", "\"100\",\"页\",\"\""),
            csv.replace("\"100\",\"页\",\"15\"", "\"100\",\"页\",\"100\""),
            csv.replaceFirst("\"100\",\"页\"", "\"100\",\"公里\""),
        ).forEach { corrupted -> assertThrows(IllegalArgumentException::class.java) { CsvBackup.decode(corrupted.toByteArray()) } }
    }

    @Test fun repeatedCompletionDoesNotDoubleCount() {
        val partial = finish(running(), "15")
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(partial, "r", amount = "15") }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(next(partial), "r", amount = "15") }
        assertEquals("15", Quantity.format(partial.goal("g").accumulated(task())))
    }

    @Test fun failedWriteRetainsActiveTaskAndOriginalQuantityThenRetryCommitsOnce() = runBlocking {
        var saved = StateCodec.encode(running())
        var fail = true
        val store = object : SnapshotStore {
            override fun read() = StateCodec.decode(saved)
            override fun write(state: AppState) { if(fail) throw IOException("disk full"); saved = StateCodec.encode(state) }
        }
        val repo = GoalRepository(store); repo.load()
        assertTrue(runCatching { repo.update { GoalRules.complete(it, "r", 2000, "100") } }.isFailure)
        assertNotNull(repo.state.value!!.active)
        assertTrue(repo.state.value!!.goal("g").done.isEmpty())
        fail = false
        repo.update { GoalRules.complete(it, "r", 2000, "100") }
        val restarted = GoalRepository(store); restarted.load()
        assertEquals(1, restarted.state.value!!.goal("g").done.size)
        assertTrue(restarted.state.value!!.goal("g").done.single().seriesEnded)
    }

    @Test fun versionThreeJsonRetainsManualLoopWithoutQuantityFields() {
        val legacy = """{"version":3,"goals":[{"id":"g","title":"旧循环","start":"2026-09-18","end":"2026-10-02","pending":[{"id":"r","title":"练习","seriesId":"series","seriesEnded":false}],"next":null,"done":[],"archived":false}],"active":null}"""
        val restored = StateCodec.decode(legacy)
        assertTrue(restored.goal("g").pending.single().isRecurring)
        assertFalse(restored.goal("g").pending.single().isQuantified)
    }

    @Test fun versionTwoCsvRetainsManualLoopAndCompletedCount() {
        val header = "格式版本,记录类型,目标ID,目标顺序,目标名称,开始日期,截止日期,已归档,目标创建时间,步骤ID,步骤顺序,步骤内容,步骤状态,步骤开始时间,步骤完成时间,导出时间,目标总数,步骤总数,步骤类型,循环ID,循环已结束"
        fun row(vararg values: Pair<Int,String>) = MutableList(21) { "" }.apply {
            this[0] = "2"; values.forEach { (index, value) -> this[index] = value }
        }.joinToString(",")
        val old = listOf(header,
            row(1 to "备份",15 to "2026-09-18T00:00:00Z",16 to "1",17 to "3"),
            row(1 to "目标",2 to "g",3 to "1",4 to "旧循环",5 to "2026-09-18",6 to "2026-10-02",7 to "否"),
            row(1 to "步骤",2 to "g",9 to "p",10 to "1",11 to "练习",12 to "待执行",18 to "循环型",19 to "series",20 to "否"),
            row(1 to "步骤",2 to "g",9 to "n",10 to "1",11 to "练习",12 to "下一个",18 to "循环型",19 to "series",20 to "否"),
            row(1 to "步骤",2 to "g",9 to "d",10 to "1",11 to "练习",12 to "已完成",13 to "2026-09-18T00:00:00Z",14 to "2026-09-18T00:01:00Z",18 to "循环型",19 to "series",20 to "否"),
        ).joinToString("\r\n")
        val restored = CsvBackup.decode(old.toByteArray()).state
        val task = restored.goal("g").next!!
        assertTrue(task.isRecurring)
        assertFalse(task.isQuantified)
        assertEquals(1, restored.goal("g").completedCount(task))
        val finished = GoalRules.complete(GoalRules.start(restored, "g", "n", 1000), "n", 2000)
        assertEquals(2, finished.goal("g").completedCount(task))
    }
}
