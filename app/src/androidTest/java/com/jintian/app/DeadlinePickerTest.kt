package com.jintian.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jintian.app.ui.DeadlinePicker
import com.jintian.app.ui.JintianTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DeadlinePickerTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 9, 18)
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "qa-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun todayRemainsMarkedWhenAnotherDateIsSelectedAndPastDaysAreDisabled() {
        var confirmed: LocalDate? = null
        compose.setContent { JintianTheme { DeadlinePicker(today.plusDays(1), today, {}, { confirmed = it }) } }
        compose.onNodeWithTag("calendar-today-banner").assertExists()
        compose.onNodeWithTag("calendar-day-$today").assertTextContains("今天").assertIsNotSelected()
        compose.onNodeWithTag("calendar-day-${today.minusDays(1)}").assertIsNotEnabled()
        compose.onNodeWithTag("calendar-day-${today.plusDays(1)}").assertIsSelected()
        capture("calendar-light")
        compose.onNodeWithTag("calendar-day-$today").performScrollTo().performClick().assertIsSelected().assertTextContains("今天")
        compose.onNodeWithTag("confirm-deadline").performClick()
        compose.runOnIdle { assertEquals(today, confirmed) }
    }

    @Test fun fourWeekBoundaryAndTodayShortcutWorkAcrossMonthsInDarkTheme() {
        var confirmed: LocalDate? = null
        val last = today.plusWeeks(4)
        compose.setContent { JintianTheme(darkTheme = true) { DeadlinePicker(today, today, {}, { confirmed = it }) } }
        compose.onNodeWithTag("calendar-previous").assertIsNotEnabled()
        compose.onNodeWithTag("calendar-next").performScrollTo().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("calendar-today-banner").assertExists()
        compose.onNodeWithTag("calendar-day-$last").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("calendar-day-${last.plusDays(1)}").assertIsNotEnabled()
        capture("calendar-dark")
        compose.onNodeWithTag("confirm-deadline").performClick()
        compose.runOnIdle { assertEquals(last, confirmed) }
        compose.onNodeWithTag("calendar-select-today").performScrollTo().performClick()
        compose.onNodeWithTag("calendar-day-$today").assertIsSelected().assertTextContains("今天")
    }

    @Test fun cancellingNeverCommitsDraftDate() {
        var confirmed: LocalDate? = null
        var dismissed = false
        compose.setContent { JintianTheme { DeadlinePicker(today.plusDays(7), today, { dismissed = true }, { confirmed = it }) } }
        compose.onNodeWithTag("calendar-day-$today").performScrollTo().performClick()
        compose.onNodeWithTag("cancel-deadline").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(confirmed) }
    }
}
