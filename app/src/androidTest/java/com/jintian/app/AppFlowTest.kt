package com.jintian.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jintian.app.domain.*
import com.jintian.app.ui.DayCountdown
import com.jintian.app.ui.JintianContent
import com.jintian.app.ui.JintianTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose=createComposeRule()
    private var state by mutableStateOf(AppState())
    private fun display(dark: Boolean = false) {
        compose.setContent { JintianTheme(darkTheme=dark) { JintianContent(state,false,{change,done->state=change(state).also(GoalRules::validate);done()}) } }
    }
    private fun capture(name: String) {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir,"qa-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun createGoalAndTaskThenLockStartComplete() {
        display()
        compose.onNodeWithTag("new-goal").performClick()
        compose.onNodeWithText("开始日期").assertDoesNotExist()
        compose.onNodeWithTag("goal-title").performTextInput("完成作品集")
        compose.onNodeWithTag("save-goal").performClick()
        compose.onNodeWithTag("add-task").performClick()
        compose.onNodeWithTag("task-title").performTextInput("整理项目截图")
        compose.onNodeWithTag("task-minutes").assertDoesNotExist()
        compose.onNodeWithTag("task-one-day").assertDoesNotExist()
        compose.onNodeWithTag("save-task").performScrollTo().performClick()
        compose.onNodeWithTag("lock-next").performClick()
        val task=state.visibleGoals.single().next!!
        compose.onNodeWithTag("start-${task.id}").performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("active-start-time"))
        compose.onNodeWithTag("active-start-time").assertExists()
        compose.onNodeWithTag("active-elapsed").assertExists()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("complete-task"))
        compose.onNodeWithTag("complete-task").performClick()
        compose.runOnIdle {
            assertNull(state.active)
            assertEquals("整理项目截图",state.goals.single().done.single().title)
            assertNotNull(state.goals.single().done.single().startedAt)
            assertNotNull(state.goals.single().done.single().completedAt)
        }
        compose.onNodeWithTag("tab-stats").performClick()
        compose.onNodeWithTag("stat-today").assertTextContains("1 步",substring=true)
        capture("statistics")
    }
    @Test fun activeTaskDisablesEveryOtherStartAndUnlocksAfterCompletion() {
        val date=LocalDate.now()
        state=AppState(listOf(
            Goal("a","作品集",date,date.plusDays(14),next=Task("next-a","写项目介绍")),
            Goal("b","阅读",date,date.plusDays(14),next=Task("next-b","阅读第二章"))),
            ActiveTask("a",Task("current","整理截图"),System.currentTimeMillis()-1800000))
        display()
        capture("home")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("start-next-b"))
        compose.onNodeWithTag("start-next-b").assertIsNotEnabled()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("complete-task"))
        compose.onNodeWithTag("complete-task").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("start-next-b"))
        compose.onNodeWithTag("start-next-b").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("b",state.active?.goalId) }
    }
    @Test fun draggingWholeCardReordersPendingAndLeavesLockedNextInPlace() {
        val date=LocalDate.now()
        state=AppState(listOf(Goal("g","作品集",date,date.plusDays(14),next=Task("locked","固定下一项"),pending=listOf(Task("a","步骤 A"),Task("b","步骤 B")))))
        display()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("detail-g"))
        compose.onNodeWithTag("detail-g").performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("pending-b"))
        capture("detail")
        val from=compose.onNodeWithTag("pending-b").fetchSemanticsNode().boundsInRoot
        val to=compose.onNodeWithTag("pending-a").fetchSemanticsNode().boundsInRoot
        val list=compose.onNodeWithTag("goal-list").fetchSemanticsNode().boundsInRoot
        // Hold the card's text area (not a handle), then move above its predecessor.
        val start=Offset(from.center.x-list.left,from.top+30-list.top)
        val end=Offset(to.center.x-list.left,to.top+24-list.top)
        compose.onNodeWithTag("goal-list").performTouchInput {
            down(start)
            advanceEventTime(750)
            moveTo(start)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("goal-list").performTouchInput {
            moveTo(end,delayMillis=400)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("goal-list").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf("b","a"),state.goal("g").pending.map { it.id })
            assertEquals("locked",state.goal("g").next?.id)
        }
    }
    @Test fun dayCountdownShowsTimeAndSemanticProgress() {
        compose.setContent { JintianTheme { DayCountdown(DayProgress(LocalDate.of(2026,9,17),.5f,43200)) } }
        compose.onNodeWithTag("day-remaining").assertTextEquals("12:00:00")
        compose.onNodeWithTag("day-progress").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.5f,0f..1f))
    }
    @Test fun bottomNavigationShowsStatisticsAndReturnsHomeInDarkTheme() {
        display(dark=true)
        compose.onNodeWithTag("tab-stats").performClick()
        compose.onNodeWithTag("stat-total").assertTextContains("0 步",substring=true)
        capture("statistics-dark")
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("day-progress").assertExists()
    }
    @Test fun createRecurringStepCountOneRoundThenConfirmFinalCompletion() {
        val date = LocalDate.now()
        state = AppState(listOf(Goal("g", "每天练习", date, date.plusDays(14))))
        display()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("detail-g"))
        compose.onNodeWithTag("detail-g").performClick()
        compose.onNodeWithTag("add-task").performClick()
        compose.onNodeWithTag("type-recurring").performClick()
        compose.onNodeWithTag("task-title").performTextInput("练习一组")
        compose.onNodeWithTag("save-task").performScrollTo().performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("lock-next"))
        compose.onNodeWithTag("lock-next").performClick()
        val first = state.goal("g").next!!
        compose.runOnIdle {
            assertTrue(first.isRecurring)
            assertEquals(1, state.goal("g").pending.size)
        }
        compose.onNodeWithTag("task-kind-${first.id}").assertTextEquals("循环型 · 已完成 0 次")
        compose.onNodeWithTag("start-${first.id}").performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("complete-task"))
        compose.onNodeWithTag("complete-task").performClick()
        val next = state.goal("g").next!!
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("task-kind-${next.id}"))
        compose.onNodeWithTag("task-kind-${next.id}").assertTextEquals("循环型 · 已完成 1 次")
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("start-${next.id}"))
        compose.onNodeWithTag("start-${next.id}").performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("end-recurrence"))
        compose.onNodeWithTag("end-recurrence").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNotNull(state.active); assertEquals(1, state.goal("g").done.size) }
        compose.onNodeWithTag("end-recurrence").performClick()
        compose.onNodeWithTag("confirm-end-recurrence").performClick()
        compose.runOnIdle {
            assertNull(state.active)
            assertNull(state.goal("g").next)
            assertTrue(state.goal("g").pending.isEmpty())
            assertEquals(2, state.goal("g").done.size)
            assertTrue(state.goal("g").done.all { it.seriesEnded })
        }
        compose.onNodeWithText("完成并归档目标").performScrollTo().assertExists()
        capture("recurrence-ended")
    }
    @Test fun quantityLoopAcceptsPerCompletionAmountAndEndsAtTarget() {
        val date = LocalDate.now()
        state = AppState(listOf(Goal("g", "阅读", date, date.plusDays(14))))
        display()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("detail-g"))
        compose.onNodeWithTag("detail-g").performClick()
        compose.onNodeWithTag("add-task").performClick()
        compose.onNodeWithTag("type-recurring").performClick()
        compose.onNodeWithTag("repeat-quantity").performClick()
        compose.onNodeWithTag("target-amount").performScrollTo().performTextInput("20")
        compose.onNodeWithTag("quantity-unit").performScrollTo().performTextInput("页")
        compose.onNodeWithTag("task-title").performScrollTo().performTextInput("阅读一段")
        compose.onNodeWithTag("save-task").performScrollTo().performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("lock-next"))
        compose.onNodeWithTag("lock-next").performClick()
        val first = state.goal("g").next!!
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("start-${first.id}"))
        compose.onNodeWithTag("start-${first.id}").performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("complete-task"))
        compose.onNodeWithTag("complete-task").performClick()
        compose.onNodeWithTag("completion-amount").performTextInput("0")
        compose.onNodeWithTag("confirm-quantity").performClick()
        compose.onNodeWithText("数值必须大于 0").assertExists()
        compose.runOnIdle { assertTrue(state.goal("g").done.isEmpty()) }
        compose.onNodeWithTag("completion-amount").performTextReplacement("5.5")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNotNull(state.active); assertTrue(state.goal("g").done.isEmpty()) }
        compose.onNodeWithTag("complete-task").performClick()
        compose.onNodeWithTag("completion-amount").performTextInput("5.5")
        compose.onNodeWithTag("confirm-quantity").performClick()
        val second = state.goal("g").next!!
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("task-quantity-${second.id}"))
        compose.onNodeWithTag("task-quantity-${second.id}").assertTextEquals("累计 5.5 / 20 页")
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("start-${second.id}"))
        compose.onNodeWithTag("start-${second.id}").performClick()
        compose.onNodeWithTag("goal-list").performScrollToNode(hasTestTag("complete-task"))
        compose.onNodeWithTag("complete-task").performClick()
        compose.onNodeWithTag("completion-amount").performTextInput("15")
        compose.onNodeWithText("本次计入后：20.5 / 20 页").assertExists()
        compose.onNodeWithTag("confirm-quantity").performClick()
        compose.runOnIdle {
            val goal = state.goal("g")
            assertNull(state.active)
            assertNull(goal.next)
            assertTrue(goal.pending.isEmpty())
            assertEquals(listOf("5.5", "15"), goal.done.map { it.completedAmount })
            assertTrue(goal.done.all { it.seriesEnded })
        }
        capture("quantity-ended")
    }
}
