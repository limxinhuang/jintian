package com.jintian.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jintian.app.domain.Goal
import com.jintian.app.domain.GoalRules
import com.jintian.app.domain.Task
import com.jintian.app.domain.Quantity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun GoalForm(busy: Boolean, onSave: (String, LocalDate) -> Unit, onCancel: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(14).toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val today = rememberNow().atZone(ZoneId.systemDefault()).toLocalDate()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("给近期想完成的事，留一段明确的时间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(title, { if(it.length <= 60) title = it }, Modifier.fillMaxWidth().testTag("goal-title"), label = { Text("目标名称") }, placeholder = { Text("例如：完成个人作品集") }, singleLine = true, enabled = !busy)
        DateField("截止日期", LocalDate.parse(end), today, !busy) { end = it.toString() }
        val days = ChronoUnit.DAYS.between(today, LocalDate.parse(end))
        Text("${if(days == 0L) "今天截止" else "从今天起 $days 天"} · 可选今天至四周后（28 天内）", color = if(days in 0..28) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try {
                val clean = GoalRules.goalTitle(title)
                val from = LocalDate.now(); val to = LocalDate.parse(end)
                GoalRules.validateDates(from, to)
                error = null; onSave(clean, to)
            } catch (e: IllegalArgumentException) { error = e.message }
        }, Modifier.fillMaxWidth().testTag("save-goal"), enabled = !busy) { Text(if(busy) "正在保存…" else "创建并拆分任务") }
        TextButton(onClick = onCancel, Modifier.align(Alignment.CenterHorizontally), enabled = !busy) { Text("取消") }
    }
}

@Composable
private fun DateField(label: String, date: LocalDate, today: LocalDate, enabled: Boolean, onDate: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if(open) DeadlinePicker(date, today, onDismiss = { open = false }, onConfirm = { onDate(it); open = false })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth().testTag("deadline-picker"), enabled = enabled) { Text("${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日") }
    }
}

@Composable
fun TaskForm(goal: Goal, task: Task?, locked: Boolean, busy: Boolean, onSave: (String, Boolean, String?, String?) -> Unit, onCancel: () -> Unit) {
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title ?: "") }
    var recurring by rememberSaveable(task?.id) { mutableStateOf(task?.isRecurring ?: false) }
    var quantified by rememberSaveable(task?.id) { mutableStateOf(task?.isQuantified ?: false) }
    var target by rememberSaveable(task?.id) { mutableStateOf(task?.targetAmount ?: "") }
    var unit by rememberSaveable(task?.id) { mutableStateOf(task?.unit ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(goal.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(task == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("步骤类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !recurring, onClick = { recurring = false }, label = { Text("单次型") }, enabled = !busy, modifier = Modifier.testTag("type-single"))
                    FilterChip(selected = recurring, onClick = { recurring = true }, label = { Text("循环型") }, enabled = !busy, modifier = Modifier.testTag("type-recurring"))
                }
            }
        } else TaskKind(task, goal)
        if(task == null && recurring) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("循环结束方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !quantified, onClick = { quantified = false }, label = { Text("手动结束") }, enabled = !busy, modifier = Modifier.testTag("repeat-manual"))
                    FilterChip(selected = quantified, onClick = { quantified = true }, label = { Text("累计数值达标") }, enabled = !busy, modifier = Modifier.testTag("repeat-quantity"))
                }
            }
            if(quantified) {
                OutlinedTextField(target, { if(it.length <= 19) target = it }, Modifier.fillMaxWidth().testTag("target-amount"), label = { Text("目标数值") }, placeholder = { Text("例如：100") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(unit, { if(it.length <= 12) unit = it }, Modifier.fillMaxWidth().testTag("quantity-unit"), label = { Text("单位") }, placeholder = { Text("例如：页、公里、个") }, singleLine = true, enabled = !busy)
            }
        }
        Text(when { !recurring -> "完成一次，这个步骤就结束。"; quantified -> "进入「下一步」时会自动追加一次。每次完成填写本次数值，累计达到或超过目标后自动结束循环。"; else -> "进入「下一步」时，会自动在待执行队尾追加一次。执行时可选择「完成并结束循环」。" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(title, { if(it.length <= 120) title = it }, Modifier.fillMaxWidth().testTag("task-title"), label = { Text("这一步具体做什么？") }, placeholder = { Text("用一个明确的动作描述任务") }, minLines = 3, enabled = !busy)
        Text("每个步骤必须能在一天内完成。较大的事情，请拆成多个步骤。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (locked) Text("下一项已锁定，只能修改文字，不能调整顺序。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(task?.isRecurring == true) Text("文字修改会同步到同一循环的待执行和下一项，保留已有执行记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try {
                val clean = GoalRules.taskTitle(title)
                val amount = if(recurring && quantified) Quantity.normalize(target) else null
                val cleanUnit = if(amount != null) Quantity.unit(unit) else null
                error = null; onSave(clean, recurring, amount, cleanUnit)
            } catch (e: IllegalArgumentException) { error = e.message }
        }, Modifier.fillMaxWidth().testTag("save-task"), enabled = !busy) { Text(if(busy) "正在保存…" else if(task == null) "加入待执行" else "保存修改") }
        TextButton(onClick = onCancel, Modifier.align(Alignment.CenterHorizontally), enabled = !busy) { Text("取消") }
    }
}
