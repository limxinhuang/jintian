package com.jintian.app

import com.jintian.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GoalRulesTest {
    private val today = LocalDate.of(2026,9,17)
    private val now = today.atTime(10,30).atZone(ZoneId.of("Asia/Shanghai"))
    private fun goal(id: String = "g", pending: List<Task> = emptyList(), next: Task? = null) = Goal(id,"目标 $id",today,today.plusDays(14),pending,next)
    private fun task(id: String) = Task(id,"任务 $id")
    @Test fun goalPeriodBoundaries() {
        listOf(0L,1L,7L,8L,28L).forEach { GoalRules.addGoal(AppState(),"目标",today.plusDays(it),now).also(GoalRules::validate) }
        listOf(-1L,29L,30L,31L).forEach { days -> assertThrows(IllegalArgumentException::class.java) { GoalRules.addGoal(AppState(),"目标",today.plusDays(days),now) } }
    }
    @Test fun oldTwentyNineDayGoalsRemainEditableAndRestorable() {
        val legacy = AppState(listOf(goal().copy(end = today.plusDays(29))))
        GoalRules.validate(legacy)
        val edited = GoalRules.addTask(legacy, "g", task("new"))
        assertEquals(edited, com.jintian.app.data.StateCodec.decode(com.jintian.app.data.StateCodec.encode(edited)))
        assertEquals(edited, com.jintian.app.data.CsvBackup.decode(com.jintian.app.data.CsvBackup.encode(edited)).state)
    }
    @Test fun sameDayGoalsPersistAndFourWeeksWorksAcrossYearBoundary() {
        val sameDay = GoalRules.addGoal(AppState(), "今天做完", today, now)
        assertEquals(sameDay, com.jintian.app.data.StateCodec.decode(com.jintian.app.data.StateCodec.encode(sameDay)))
        assertEquals(sameDay, com.jintian.app.data.CsvBackup.decode(com.jintian.app.data.CsvBackup.encode(sameDay)).state)
        val december = now.withMonth(12).withDayOfMonth(20)
        GoalRules.addGoal(AppState(), "跨年目标", december.toLocalDate().plusWeeks(4), december)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.addGoal(AppState(), "太远", december.toLocalDate().plusDays(29), december) }
    }
    @Test fun sixthActiveGoalRejected() {
        val state = AppState((1..5).map { goal(it.toString()) })
        assertThrows(IllegalArgumentException::class.java) { GoalRules.addGoal(state,"第六个",today.plusDays(14),now) }
    }
    @Test fun appendKeepsManualOrder() {
        var state = AppState(listOf(goal(pending=listOf(task("a"),task("b")))))
        state = GoalRules.moveTask(state,"g","b",0)
        state = GoalRules.addTask(state,"g",task("c"))
        assertEquals(listOf("b","a","c"),state.goal("g").pending.map { it.id })
        assertNull(state.goal("g").next)
    }
    @Test fun confirmationLocksOnlyTheHead() {
        val state = GoalRules.lockNext(AppState(listOf(goal(pending=listOf(task("a"),task("b"))))),"g")
        assertEquals("a",state.goal("g").next?.id)
        assertEquals(listOf("b"),state.goal("g").pending.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { GoalRules.lockNext(state,"g") }
    }
    @Test fun lockedTaskCannotBeMovedOrDeleted() {
        val state=AppState(listOf(goal(pending=listOf(task("b")),next=task("a"))))
        assertThrows(IllegalArgumentException::class.java) { GoalRules.moveTask(state,"g","a",0) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.deleteTask(state,"g","a") }
    }
    @Test fun lockedTaskOnlyAllowsWordingChanges() {
        val state=GoalRules.editTask(AppState(listOf(goal(next=task("a")))),"g","a","修正文字")
        assertEquals("修正文字",state.goal("g").next?.title)
        assertEquals("a",state.goal("g").next?.id)
    }
    @Test fun globalExecutionSlotIsSharedAcrossGoals() {
        var state=AppState(listOf(goal("g",next=task("a")),goal("h",next=task("b"))))
        state=GoalRules.start(state,"g","a",123)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.start(state,"h","b",124) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.start(state,"g","a",124) }
        assertEquals("a",state.active?.task?.id)
    }
    @Test fun startingPromotesNextButDoesNotAutoStartIt() {
        var state=AppState(listOf(goal(pending=listOf(task("b"),task("c")),next=task("a"))))
        state=GoalRules.start(state,"g","a",123)
        assertEquals("b",state.goal("g").next?.id)
        assertEquals("c",state.goal("g").pending.single().id)
        state=GoalRules.complete(state,"a")
        assertNull(state.active)
        assertEquals("a",state.goal("g").done.single().id)
        assertEquals("b",state.goal("g").next?.id)
    }
    @Test fun staleActionsAreRejected() {
        val state=GoalRules.start(AppState(listOf(goal(next=task("a")))),"g","a",0)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(state,"wrong") }
        val done=GoalRules.complete(state,"a")
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(done,"a") }
    }
    @Test fun archiveRetainsHistoryAndFreesSlot() {
        val ready=goal().copy(done=listOf(task("a")))
        var state=AppState(listOf(ready)+(1..4).map { goal(it.toString()) })
        state=GoalRules.archive(state,"g")
        state=GoalRules.addGoal(state,"新目标",today.plusDays(14),now)
        assertEquals(5,state.visibleGoals.size)
        assertEquals(6,state.goals.size)
        assertEquals("a",state.goal("g").done.single().id)
    }
    @Test fun unfinishedGoalCannotArchive() {
        val state=AppState(listOf(goal(next=task("a"))))
        assertThrows(IllegalArgumentException::class.java) { GoalRules.archive(state,"g") }
    }
    @Test fun taskOnlyRequiresConcreteNonblankContent() {
        val state=AppState(listOf(goal()))
        assertThrows(IllegalArgumentException::class.java) { GoalRules.addTask(state,"g",Task(title="   ")) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.addTask(state,"g",Task(title="长".repeat(121))) }
        GoalRules.addTask(state,"g",Task(title="一天内可完成"))
    }
    @Test fun duplicateTaskAcrossStatesRejected() {
        val state=AppState(listOf(goal(pending=listOf(task("a")),next=task("a"))))
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(state) }
    }
    @Test fun creationTimeIsAutomaticallyRecorded() {
        val state=GoalRules.addGoal(AppState(),"目标",today.plusDays(14),now)
        assertEquals(today,state.goals.single().start)
        assertEquals(now.toInstant().toEpochMilli(),state.goals.single().createdAt)
    }
    @Test fun completionPreservesStartAndFinishTimestamps() {
        var state=GoalRules.start(AppState(listOf(goal(next=task("a")))),"g","a",1000)
        state=GoalRules.complete(state,"a",91000)
        assertEquals(1000L,state.goal("g").done.single().startedAt)
        assertEquals(91000L,state.goal("g").done.single().completedAt)
        assertEquals(90L,TaskTime.completedSeconds(state.goal("g").done.single()))
    }
    @Test fun onlyEmptyGoalsCanBeDeleted() {
        assertTrue(GoalRules.deleteEmptyGoal(AppState(listOf(goal())),"g").goals.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { GoalRules.deleteEmptyGoal(AppState(listOf(goal(next=task("a")))),"g") }
    }
}
