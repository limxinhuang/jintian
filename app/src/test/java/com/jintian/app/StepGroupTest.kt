package com.jintian.app

import com.jintian.app.data.CsvBackup
import com.jintian.app.data.StateCodec
import com.jintian.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StepGroupTest {
    private val day = LocalDate.of(2026, 9, 18)
    private fun state(vararg tasks: Task) = AppState(listOf(Goal("g", "目标", day, day.plusDays(10), pending = tasks.toList())))
    private fun grouped(mode: StepGroupMode = StepGroupMode.SINGLE, target: String? = null, unit: String? = null): AppState {
        val base = state(Task("a","A"),Task("x","X"),Task("b","B"),Task("y","Y"),Task("c","C"))
        return GoalRules.createGroup(base,"g",listOf("a","b","c"),"流程",mode,target,unit,"group","round-1")
    }
    private fun runCurrent(state: AppState, at: Long): AppState {
        val next = state.goal("g").next!!
        return GoalRules.complete(GoalRules.start(state,"g",next.id,at),next.id,at+1)
    }

    @Test fun selectedStepsBecomeOneOrderedBlockAtFirstSelectedPosition() {
        val result = grouped()
        assertEquals(listOf("a","b","c","x","y"),result.goal("g").pending.map { it.id })
        assertEquals(listOf(0,1,2),result.goal("g").pending.take(3).map { it.groupPosition })
        assertEquals("流程",result.goal("g").groups.single().name)
    }

    @Test fun unlockedGroupCanBeEditedOrUnlinkedWithoutDeletingSteps() {
        val edited = GoalRules.editGroup(grouped(),"g","group",listOf("c","a","x"),"新流程",StepGroupMode.MANUAL)
        assertEquals(listOf("c","a","x","b","y"),edited.goal("g").pending.map { it.id })
        assertEquals("新流程",edited.goal("g").group("group").name)
        val unlinked = GoalRules.unlinkGroup(edited,"g","group")
        assertTrue(unlinked.goal("g").groups.isEmpty())
        assertEquals(listOf("c","a","x","b","y"),unlinked.goal("g").pending.map { it.id })
        assertTrue(unlinked.goal("g").pending.none { it.isGrouped || it.isRecurring })
    }

    @Test fun outerMoveMovesWholeRoundAndRejectsInsertionIntoItsMembers() {
        val moved = GoalRules.moveTask(grouped(),"g","a",3)
        assertEquals(listOf("x","a","b","c","y"),moved.goal("g").pending.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { GoalRules.moveTask(grouped(),"g","x",1) }
    }

    @Test fun firstPromotionLocksImmediatelyAndGeneratesExactlyOneWholeFutureRound() {
        val locked = GoalRules.lockNext(grouped(StepGroupMode.MANUAL),"g")
        val goal = locked.goal("g")
        assertEquals("a",goal.next!!.id)
        assertEquals("round-1",goal.lockedGroupRoundId)
        assertEquals(2,goal.groupRounds.size)
        assertEquals(3,goal.groupRounds.last().memberIds.size)
        assertEquals(1,goal.groupRounds.count { it.id != goal.lockedGroupRoundId && it.completedAt == null })
        assertEquals(locked,StateCodec.decode(StateCodec.encode(locked)))
    }

    @Test fun groupAdvancesInOrderAndReleasesOnlyAfterLastMember() {
        var value = GoalRules.lockNext(grouped(),"g")
        value = runCurrent(value,10)
        assertEquals("b",value.goal("g").next!!.id)
        assertEquals("round-1",value.goal("g").lockedGroupRoundId)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.start(value,"g","x",20) }
        value = runCurrent(value,20)
        assertEquals("c",value.goal("g").next!!.id)
        val c = value.goal("g").next!!
        value = GoalRules.start(value,"g",c.id,30)
        assertNull(value.goal("g").next)
        assertEquals("round-1",value.goal("g").lockedGroupRoundId)
        value = GoalRules.complete(value,c.id,31)
        assertNull(value.goal("g").lockedGroupRoundId)
        assertEquals("x",value.goal("g").next!!.id)
        assertEquals(1,value.goal("g").completedRounds("group"))
        assertEquals(3,value.goal("g").done.size)
    }

    @Test fun differentGoalsMayBothHoldLocksButExecutionSlotRemainsGlobal() {
        val first = grouped(StepGroupMode.MANUAL)
        val other = Goal("h","其他",day,day.plusDays(10),pending=listOf(Task("d","D"),Task("e","E")))
        var value = first.copy(goals = first.goals + other)
        value = GoalRules.createGroup(value,"h",listOf("d","e"),groupId="group-h",roundId="round-h")
        value = GoalRules.lockNext(GoalRules.lockNext(value,"g"),"h")
        assertNotNull(value.goal("g").lockedGroupRoundId)
        assertNotNull(value.goal("h").lockedGroupRoundId)
        val running = GoalRules.start(value,"g","a",1)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.start(running,"h","d",2) }
    }

    @Test fun manualEndCountsRoundAndRemovesOnlyFutureRound() {
        var value = GoalRules.lockNext(grouped(StepGroupMode.MANUAL),"g")
        value = runCurrent(value,1); value = runCurrent(value,3)
        val c = value.goal("g").next!!
        value = GoalRules.start(value,"g",c.id,5)
        value = GoalRules.completeAndEndGroup(value,c.id,6)
        val goal = value.goal("g")
        assertTrue(goal.group("group").ended)
        assertEquals(1,goal.groupRounds.size)
        assertEquals(1,goal.completedRounds("group"))
        assertEquals("x",goal.next!!.id)
        assertEquals(listOf("y"),goal.pending.map { it.id })
    }

    @Test fun quantityIsRecordedOncePerRoundAndEndsAtTarget() {
        var value = GoalRules.lockNext(grouped(StepGroupMode.QUANTITY,"10","页"),"g")
        value = runCurrent(value,1); value = runCurrent(value,3)
        val c = value.goal("g").next!!
        value = GoalRules.start(value,"g",c.id,5)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(value,c.id,6) }
        value = GoalRules.complete(value,c.id,6,"12")
        assertEquals("12",value.goal("g").groupRounds.single().completedAmount)
        assertEquals("12",Quantity.format(value.goal("g").groupAccumulated("group")))
        assertTrue(value.goal("g").group("group").ended)
    }

    @Test fun linkedCsvRoundTripPreservesLocksRoundsAndMemberOrder() {
        val original = GoalRules.lockNext(grouped(StepGroupMode.MANUAL),"g")
        val restored = CsvBackup.decode(CsvBackup.encode(original)).state
        assertEquals(original,restored)
    }
}
