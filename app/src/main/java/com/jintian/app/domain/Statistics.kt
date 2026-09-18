package com.jintian.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class DailyCount(val date: LocalDate, val completed: Int)
data class Statistics(
    val todayCompleted: Int,
    val totalCompleted: Int,
    val completedGoals: Int,
    val elapsedSeconds: Long,
    val week: List<DailyCount>,
)

object TaskTime {
    fun elapsedSeconds(startedAt: Long, now: Long): Long = ((now - startedAt) / 1000).coerceAtLeast(0)
    fun clockText(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, safe % 3600 / 60, safe % 60)
    }
    fun shortText(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(0)} 秒"
        seconds < 3600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分钟"
    }
    fun completedSeconds(task: Task): Long = if(task.startedAt != null && task.completedAt != null) elapsedSeconds(task.startedAt, task.completedAt) else 0
}

object StatsCalculator {
    fun calculate(state: AppState, now: Instant, zone: ZoneId): Statistics {
        val today = now.atZone(zone).toLocalDate()
        val done = state.goals.flatMap { it.done }
        val counts = done.mapNotNull { it.completedAt?.let { end -> Instant.ofEpochMilli(end).atZone(zone).toLocalDate() } }.groupingBy { it }.eachCount()
        val week = (6 downTo 0).map { offset -> today.minusDays(offset.toLong()).let { DailyCount(it, counts[it] ?: 0) } }
        return Statistics(
            counts[today] ?: 0,
            done.size,
            state.goals.count { it.archived },
            done.sumOf(TaskTime::completedSeconds) + (state.active?.let { TaskTime.elapsedSeconds(it.startedAt, now.toEpochMilli()) } ?: 0),
            week,
        )
    }
}
