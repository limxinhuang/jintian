package com.jintian.app.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

data class DayProgress(val date: LocalDate, val elapsedFraction: Float, val remainingSeconds: Long) {
    val remainingText: String get() = String.format(Locale.ROOT, "%02d:%02d:%02d", remainingSeconds / 3600, remainingSeconds % 3600 / 60, remainingSeconds % 60)
}

object DayClock {
    /** Recompute from wall clock; do not decrement a counter that drifts in the background. */
    fun at(now: ZonedDateTime): DayProgress {
        val date = now.toLocalDate()
        val start = date.atStartOfDay(now.zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(now.zone).toInstant()
        val total = Duration.between(start, end).toMillis()
        val elapsed = Duration.between(start, now.toInstant()).toMillis().coerceIn(0, total)
        val remaining = ((total - elapsed + 999) / 1000).coerceAtLeast(0)
        return DayProgress(date, (elapsed.toDouble() / total).toFloat(), remaining)
    }
}
