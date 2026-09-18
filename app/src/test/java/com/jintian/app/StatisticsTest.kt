package com.jintian.app

import com.jintian.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatisticsTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val date=LocalDate.of(2026,9,17)
    private val now=Instant.parse("2026-09-17T04:00:00Z")
    private fun goal(done:List<Task>,archived:Boolean=false)=Goal("g","目标",date.minusDays(7),date.plusDays(7),done=done,archived=archived)
    @Test fun completedAndActiveDurationsAreBothCounted() {
        val end=now.toEpochMilli()
        val state=AppState(listOf(goal(listOf(Task("a","已完成",end-3600000,end)))) ,ActiveTask("g",Task("b","执行中"),end-1800000))
        val stats=StatsCalculator.calculate(state,now,zone)
        assertEquals(1,stats.todayCompleted)
        assertEquals(1,stats.totalCompleted)
        assertEquals(5400L,stats.elapsedSeconds)
        assertEquals(1,stats.week.last().completed)
    }
    @Test fun archivedGoalsRemainInStatistics() {
        val end=now.toEpochMilli()
        val stats=StatsCalculator.calculate(AppState(listOf(goal(listOf(Task("a","完成",end-60000,end)),true))),now,zone)
        assertEquals(1,stats.completedGoals)
        assertEquals(1,stats.totalCompleted)
        assertEquals(60L,stats.elapsedSeconds)
    }
    @Test fun sevenDayChartUsesLocalCompletionDate() {
        val ends=listOf("2026-09-10T16:01:00Z","2026-09-16T16:01:00Z","2026-09-10T15:59:00Z")
        val done=ends.mapIndexed { i,value -> val end=Instant.parse(value).toEpochMilli();Task("$i","步骤 $i",end-60000,end) }
        val stats=StatsCalculator.calculate(AppState(listOf(goal(done))),now,zone)
        assertEquals(date.minusDays(6),stats.week.first().date)
        assertEquals(1,stats.week.first().completed)
        assertEquals(1,stats.todayCompleted)
        assertEquals(2,stats.week.sumOf { it.completed })
        assertEquals(3,stats.totalCompleted)
    }
    @Test fun oldRecordsCountWithoutFabricatedTime() {
        val stats=StatsCalculator.calculate(AppState(listOf(goal(listOf(Task("old","旧记录"))))),now,zone)
        assertEquals(1,stats.totalCompleted)
        assertEquals(0,stats.todayCompleted)
        assertEquals(0L,stats.elapsedSeconds)
    }
    @Test fun clockAdjustmentDoesNotProduceNegativeTime() {
        assertEquals(0L,TaskTime.elapsedSeconds(10000,5000))
        assertEquals("25:01:01",TaskTime.clockText(90061))
    }
}
