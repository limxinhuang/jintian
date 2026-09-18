package com.jintian.app

import com.jintian.app.domain.DayClock
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DayClockTest {
    @Test fun startOfDayHasFullRemainingTime() {
        val day=DayClock.at(ZonedDateTime.parse("2026-09-17T00:00:00+08:00[Asia/Shanghai]"))
        assertEquals(0f,day.elapsedFraction,0f)
        assertEquals("24:00:00",day.remainingText)
    }
    @Test fun noonIsHalfway() {
        val day=DayClock.at(ZonedDateTime.parse("2026-09-17T12:00:00+08:00[Asia/Shanghai]"))
        assertEquals(.5f,day.elapsedFraction,0f)
        assertEquals("12:00:00",day.remainingText)
    }
    @Test fun midnightResetsBasedOnDate() {
        val last=DayClock.at(ZonedDateTime.parse("2026-09-17T23:59:59.500+08:00[Asia/Shanghai]"))
        val next=DayClock.at(ZonedDateTime.parse("2026-09-18T00:00:00+08:00[Asia/Shanghai]"))
        assertEquals("00:00:01",last.remainingText)
        assertEquals("24:00:00",next.remainingText)
        assertEquals(last.date.plusDays(1),next.date)
    }
    @Test fun resumingUsesCurrentWallClock() {
        val before=ZonedDateTime.parse("2026-09-17T10:00:00+08:00[Asia/Shanghai]")
        assertEquals("14:00:00",DayClock.at(before).remainingText)
        assertEquals("09:00:00",DayClock.at(before.plusHours(5)).remainingText)
    }
    @Test fun followsLocalTimezone() {
        val instant=Instant.parse("2026-09-17T12:00:00Z")
        assertEquals("04:00:00",DayClock.at(instant.atZone(ZoneId.of("Asia/Shanghai"))).remainingText)
        assertEquals("12:00:00",DayClock.at(instant.atZone(ZoneId.of("UTC"))).remainingText)
    }
    @Test fun daylightSavingDaysUseActualDayLength() {
        val spring=DayClock.at(ZonedDateTime.parse("2026-03-08T00:00:00-05:00[America/New_York]"))
        val autumn=DayClock.at(ZonedDateTime.parse("2026-11-01T00:00:00-04:00[America/New_York]"))
        assertEquals("23:00:00",spring.remainingText)
        assertEquals("25:00:00",autumn.remainingText)
    }
}
