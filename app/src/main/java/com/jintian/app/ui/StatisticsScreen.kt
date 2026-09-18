package com.jintian.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jintian.app.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun StatisticsScreen(state: AppState, now: Instant, onGoal: (String) -> Unit, onBackup: (() -> Unit)? = null) {
    val zone = ZoneId.systemDefault()
    val stats = StatsCalculator.calculate(state,now,zone)
    val recent = state.goals.flatMap { g -> g.done.map { g to it } }.sortedByDescending { it.second.completedAt ?: Long.MIN_VALUE }.take(5)
    LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item("summary") {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Metric("今日完成","${stats.todayCompleted} 步",Modifier.weight(1f).testTag("stat-today"))
                    Metric("累计完成","${stats.totalCompleted} 步",Modifier.weight(1f).testTag("stat-total"))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Metric("已完成目标","${stats.completedGoals} 个",Modifier.weight(1f))
                    Metric("累计执行时长",TaskTime.shortText(stats.elapsedSeconds),Modifier.weight(1f).testTag("stat-duration"))
                }
                Text("执行时长包含后台经过的时间，以及当前正在执行的步骤。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(onBackup!=null) item("backup") {
            OutlinedButton(onClick=onBackup,modifier=Modifier.fillMaxWidth().testTag("open-backup")) { Text("数据备份 · 导入 / 导出 CSV") }
        }
        item("week") {
            Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("最近 7 天",style=MaterialTheme.typography.titleMedium); Text("${stats.week.sumOf { it.completed }} 步",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    val max = (stats.week.maxOfOrNull { it.completed } ?: 0).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) {
                        stats.week.forEach { day ->
                            Column(Modifier.weight(1f).semantics(mergeDescendants=true) { contentDescription="${day.date}，完成 ${day.completed} 个步骤" },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("${day.completed}",style=MaterialTheme.typography.labelMedium)
                                Box(Modifier.height(96.dp).fillMaxWidth(),contentAlignment=Alignment.BottomCenter) {
                                    Box(Modifier.fillMaxWidth(.65f).height(if(day.completed==0) 3.dp else (96f*day.completed/max).dp).background(if(day.completed==0) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,RoundedCornerShape(6.dp)))
                                }
                                Text(if(day.date==stats.week.last().date) "今天" else "${day.date.monthValue}/${day.date.dayOfMonth}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if(stats.totalCompleted == 0) Text("完成第一个步骤后，记录会出现在这里。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item("goal-heading") { Text("目标进度",style=MaterialTheme.typography.titleMedium) }
        if(state.goals.isEmpty()) item("no-goals") { Text("还没有目标，去「今天」创建一个。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.goals,key={it.id}) { g ->
            val total=g.done.size+g.pending.size+(if(g.next==null) 0 else 1)+(if(state.active?.goalId==g.id) 1 else 0)
            Surface(Modifier.fillMaxWidth().clickable { onGoal(g.id) },shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(g.title,style=MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress={if(total==0) 0f else g.done.size.toFloat()/total},modifier=Modifier.fillMaxWidth(),drawStopIndicator={})
                    Text("${g.done.size} / $total 步已完成${if(g.archived) " · 已归档" else ""}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(g.pending.any { it.isRecurring }) Text("循环步骤按每次完成计数，后续步骤会随循环追加。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(recent.isNotEmpty()) {
            item("recent-heading") { Text("最近完成",style=MaterialTheme.typography.titleMedium) }
            items(recent,key={"recent-${it.second.id}"}) { (g,t) ->
                Column(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(t.title,style=MaterialTheme.typography.bodyLarge)
                    if(t.isRecurring) TaskKind(t, g)
                    Text(g.title,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(t.completedAt!=null) "${Instant.ofEpochMilli(t.completedAt).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} 完成 · 用时 ${TaskTime.shortText(TaskTime.completedSeconds(t))}" else "历史步骤 · 未记录执行时间",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(modifier.semantics(mergeDescendants=true) {},shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.titleLarge)
        }
    }
}
