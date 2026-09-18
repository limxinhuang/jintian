package com.jintian.app

import com.jintian.app.data.CsvBackup
import com.jintian.app.data.StateCodec
import com.jintian.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {
    private val date = LocalDate.of(2026, 9, 18)
    private fun loop(id: String = "r", series: String = "series") = Task(id, "练习", seriesId = series)
    private fun state(vararg tasks: Task) = AppState(listOf(Goal("g", "练习目标", date, date.plusDays(14), pending = tasks.toList())))
    private fun start(state: AppState) = GoalRules.start(state, "g", state.goal("g").next!!.id, 1000)
    private fun active(vararg tasks: Task) = start(GoalRules.lockNext(state(*tasks), "g"))

    @Test fun singleTaskNeverGeneratesCopies() {
        val locked = GoalRules.lockNext(state(Task("s", "单次")), "g")
        assertTrue(locked.goal("g").pending.isEmpty())
        val finished = GoalRules.complete(start(locked), "s", 2000)
        assertTrue(GoalRules.canArchive(finished, finished.goal("g")))
    }

    @Test fun lockingRecurringTaskAppendsOneFreshCopyAtQueueTail() {
        val locked = GoalRules.lockNext(state(loop(), Task("s", "另一件事")), "g")
        assertEquals("r", locked.goal("g").next!!.id)
        assertEquals("s", locked.goal("g").pending.first().id)
        val copy = locked.goal("g").pending.last()
        assertEquals("series", copy.seriesId)
        assertEquals("练习", copy.title)
        assertNotEquals("r", copy.id)
        assertNull(copy.startedAt)
        assertEquals(0, locked.goal("g").completedCount(copy))
        GoalRules.validate(locked)
    }

    @Test fun automaticPromotionAlsoGeneratesCopyBeforeAnyCompletion() {
        val running = active(Task("s", "先做"), loop(), Task("tail", "队尾"))
        assertEquals("s", running.active!!.task.id)
        assertEquals("r", running.goal("g").next!!.id)
        assertEquals(listOf("队尾", "练习"), running.goal("g").pending.map { it.title })
        assertTrue(running.goal("g").done.isEmpty())
        GoalRules.validate(running)
    }

    @Test fun aLoneLoopKeepsExactlyOnePendingAndOneNextWhileActive() {
        val running = active(loop())
        assertEquals(1, running.goal("g").pending.size)
        assertNotNull(running.goal("g").next)
        val ids = listOf(running.active!!.task.id, running.goal("g").next!!.id, running.goal("g").pending.single().id)
        assertEquals(3, ids.distinct().size)
        GoalRules.validate(running)
    }

    @Test fun completionIncrementsCountWithoutGeneratingAnything() {
        val running = active(loop())
        val before = running.goal("g")
        val finished = GoalRules.complete(running, "r", 2500)
        val after = finished.goal("g")
        assertEquals(before.pending, after.pending)
        assertEquals(before.next, after.next)
        assertEquals(1, after.completedCount(after.next!!))
        assertEquals(1000L, after.done.single().startedAt)
        assertEquals(2500L, after.done.single().completedAt)
    }

    @Test fun twentyRoundsKeepStableQueueAndAccurateCounts() {
        var snapshot = GoalRules.lockNext(state(loop()), "g")
        repeat(20) { index ->
            snapshot = start(snapshot)
            snapshot = GoalRules.complete(snapshot, snapshot.active!!.task.id, 2000)
            GoalRules.validate(snapshot)
            assertEquals(1, snapshot.goal("g").pending.size)
            assertEquals(index + 1, snapshot.goal("g").completedCount(snapshot.goal("g").next!!))
            snapshot = StateCodec.decode(StateCodec.encode(snapshot))
        }
        assertEquals(20, snapshot.goal("g").done.size)
    }

    @Test fun sameTitlesInIndependentLoopsNeverShareCounts() {
        val running = active(loop(), loop("other", "another-series"))
        val finished = GoalRules.complete(running, "r", 2000)
        assertEquals(1, finished.goal("g").completedCount(loop()))
        assertEquals(0, finished.goal("g").completedCount(finished.goal("g").next!!))
    }

    @Test fun endingCountsCurrentOccurrenceAndRemovesNextAndPendingCopies() {
        val ended = GoalRules.completeAndEndRecurrence(active(loop()), "r", 2500)
        val goal = ended.goal("g")
        assertNull(ended.active)
        assertNull(goal.next)
        assertTrue(goal.pending.isEmpty())
        assertEquals(1, goal.completedCount(goal.done.single()))
        assertTrue(goal.done.single().seriesEnded)
        assertTrue(GoalRules.canArchive(ended, goal))
        assertTrue(GoalRules.archive(ended, "g").goal("g").archived)
    }

    @Test fun endingPreservesOtherStepsTheirOrderAndLockedNext() {
        val running = active(loop(), Task("s", "别的下一项"), loop("r2", "series2"), Task("last", "末尾"))
        val next = running.goal("g").next
        val ended = GoalRules.completeAndEndRecurrence(running, "r", 2000)
        assertEquals(next, ended.goal("g").next)
        assertEquals(listOf("r2", "last"), ended.goal("g").pending.map { it.id })
        assertFalse(ended.goal("g").pending.first().seriesEnded)
    }

    @Test fun endingMarksEntireHistoryAndSurvivesBothBackupFormats() {
        var snapshot = GoalRules.lockNext(state(loop()), "g")
        repeat(3) {
            snapshot = start(snapshot)
            snapshot = GoalRules.complete(snapshot, snapshot.active!!.task.id, 2000)
        }
        snapshot = start(snapshot)
        val ended = GoalRules.completeAndEndRecurrence(snapshot, snapshot.active!!.task.id, 3000)
        assertEquals(4, ended.goal("g").done.size)
        assertTrue(ended.goal("g").done.all { it.seriesEnded })
        assertEquals(ended, StateCodec.decode(StateCodec.encode(ended)))
        assertEquals(ended, CsvBackup.decode(CsvBackup.encode(ended)).state)
    }

    @Test fun liveCsvRoundTripPreservesIdentityOrderAndCountsWithoutExtraCopies() {
        val running = active(loop())
        val once = GoalRules.complete(running, "r", 2000)
        val again = start(once)
        val restored = CsvBackup.decode(CsvBackup.encode(again)).state
        assertEquals(again, restored)
        assertEquals(1, restored.goal("g").completedCount(restored.active!!.task))
        val finished = GoalRules.complete(restored, restored.active!!.task.id, 3000)
        assertEquals(2, finished.goal("g").completedCount(finished.goal("g").next!!))
    }

    @Test fun duplicateCompletionAndStaleEndCannotAffectNewOccurrence() {
        val finished = GoalRules.complete(active(loop()), "r", 2000)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.complete(finished, "r") }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.completeAndEndRecurrence(start(finished), "r") }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.completeAndEndRecurrence(active(Task("s", "单次")), "s") }
    }

    @Test fun generatedCopyCanMoveButCannotBeDeletedToSilentlyBreakLoop() {
        val locked = GoalRules.lockNext(state(loop(), Task("s", "单次")), "g")
        val copy = locked.goal("g").pending.last()
        val moved = GoalRules.moveTask(locked, "g", copy.id, 0)
        assertEquals(copy.id, moved.goal("g").pending.first().id)
        assertEquals(locked.goal("g").next, moved.goal("g").next)
        assertThrows(IllegalArgumentException::class.java) { GoalRules.deleteTask(moved, "g", copy.id) }
        assertTrue(GoalRules.deleteTask(state(loop()), "g", "r").goal("g").pending.isEmpty())
    }

    @Test fun editingFutureCopyUpdatesUpcomingSeriesButPreservesHistoryAndActive() {
        val once = GoalRules.complete(active(loop()), "r", 2000)
        val running = start(once)
        val changed = GoalRules.editTask(running, "g", running.goal("g").pending.single().id, "新练习")
        assertEquals("新练习", changed.goal("g").next!!.title)
        assertEquals("新练习", changed.goal("g").pending.single().title)
        assertEquals("练习", changed.active!!.task.title)
        assertEquals("练习", changed.goal("g").done.single().title)
    }

    @Test fun validationRejectsConflictingEndedFlagsMissingOrExtraCopiesAndCrossGoalSeries() {
        val running = active(loop())
        val g = running.goal("g")
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(running.copy(goals = listOf(g.copy(next = g.next!!.copy(seriesEnded = true))))) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(running.copy(goals = listOf(g.copy(pending = emptyList())))) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(running.copy(goals = listOf(g.copy(pending = g.pending + loop("duplicate"))))) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.validate(running.copy(goals = running.goals + Goal("h", "其他", date, date.plusDays(14), pending = listOf(loop("other"))))) }
        assertThrows(IllegalArgumentException::class.java) { GoalRules.addTask(running, "g", loop("duplicate")) }
    }

    @Test fun oldJsonDefaultsToSingleAndDoesNotInventCopies() {
        val legacy = """{"version":2,"goals":[{"id":"g","title":"旧目标","start":"2026-09-17","end":"2026-10-01","pending":[{"id":"s","title":"旧步骤"}],"next":null,"done":[],"archived":false}],"active":null}"""
        val restored = StateCodec.decode(legacy)
        assertFalse(restored.goal("g").pending.single().isRecurring)
        assertTrue(GoalRules.lockNext(restored, "g").goal("g").pending.isEmpty())
    }
}
