package com.jintian.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jintian.app.domain.Goal
import com.jintian.app.domain.GoalRules
import com.jintian.app.domain.Task
import com.jintian.app.domain.Quantity
import com.jintian.app.domain.StepGroupMode
import com.jintian.app.domain.StepGroup
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
fun GroupForm(
    goal: Goal,
    busy: Boolean,
    existing: StepGroup? = null,
    onSave: (List<String>, String, StepGroupMode, String?, String?) -> Unit,
    onCancel: () -> Unit,
) {
    val eligible = goal.pending.filter { !it.isGrouped || it.groupId == existing?.id }
    val existingIds = existing?.let { group -> goal.groupRounds.single { it.groupId == group.id }.memberIds }.orEmpty()
    var selected by rememberSaveable(existing?.id) { mutableStateOf(existingIds) }
    var userSorted by rememberSaveable(existing?.id) { mutableStateOf(existing != null) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Float>() }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var mode by rememberSaveable(existing?.id) { mutableStateOf((existing?.mode ?: StepGroupMode.SINGLE).name) }
    var target by rememberSaveable(existing?.id) { mutableStateOf(existing?.targetAmount ?: "") }
    var unit by rememberSaveable(existing?.id) { mutableStateOf(existing?.unit ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun move(id: String, delta: Int) {
        val list = selected.toMutableList(); val from = list.indexOf(id); val to = from + delta
        if(from >= 0 && to in list.indices) { list.add(to, list.removeAt(from)); selected = list; userSorted = true }
    }
    fun toggle(task: Task) {
        if (task.id in selected) {
            selected = selected - task.id
            if (selected.isEmpty()) userSorted = false
        } else {
            if (!userSorted) {
                selected = eligible.filter { it.id in selected || it.id == task.id }.map { it.id }
            } else {
                val taskOrder = eligible.indexOfFirst { it.id == task.id }
                val insertAfterIndex = selected.indexOfLast { id ->
                    eligible.indexOfFirst { it.id == id } < taskOrder
                }
                val list = selected.toMutableList()
                if (insertAfterIndex >= 0) list.add(insertAfterIndex + 1, task.id)
                else list.add(0, task.id)
                selected = list
            }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if(existing == null) "选择至少两个待执行步骤。保存后它们会成为一个必须按顺序完成的整体。" else "可增减成员或调整内部顺序。移出的成员会按原相对顺序放在组后。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        eligible.forEach { task ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                toggle(task)
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(task.id in selected, { toggle(task) }, enabled = !busy)
                Text(task.title, Modifier.weight(1f))
            }
        }
        if(selected.isNotEmpty()) {
            Text("组内顺序预览（长按卡片可拖动调整）", style = MaterialTheme.typography.labelLarge)
            selected.forEachIndexed { index, id ->
                key(id) {
                    val task = eligible.first { it.id == id }
                    val isDragging = draggedId == id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) translationY = dragOffsetY
                            }
                            .onGloballyPositioned { coordinates ->
                                itemHeights[id] = coordinates.size.height.toFloat()
                            }
                            .pointerInput(id, busy) {
                                if (!busy) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedId = id
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggedId = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggedId = null
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffsetY += amount.y
                                            val curr = selected.indexOf(id)
                                            if (curr >= 0) {
                                                if (dragOffsetY > 0 && curr < selected.lastIndex) {
                                                    val nextId = selected[curr + 1]
                                                    val h = itemHeights[nextId] ?: 60f
                                                    if (dragOffsetY > h * 0.5f) {
                                                        val list = selected.toMutableList()
                                                        list.add(curr + 1, list.removeAt(curr))
                                                        selected = list
                                                        dragOffsetY -= h
                                                        userSorted = true
                                                    }
                                                } else if (dragOffsetY < 0 && curr > 0) {
                                                    val prevId = selected[curr - 1]
                                                    val h = itemHeights[prevId] ?: 60f
                                                    if (dragOffsetY < -h * 0.5f) {
                                                        val list = selected.toMutableList()
                                                        list.add(curr - 1, list.removeAt(curr))
                                                        selected = list
                                                        dragOffsetY += h
                                                        userSorted = true
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = if (isDragging) 6.dp else 0.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.DragHandle, "拖动把手", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format(Locale.ROOT, "%02d", index + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { move(id, -1) }, enabled = !busy && index > 0) { Text("上移") }
                            TextButton(onClick = { move(id, 1) }, enabled = !busy && index < selected.lastIndex) { Text("下移") }
                        }
                    }
                }
            }
            val insertPosition = if(existing == null) {
                goal.pending.indexOfFirst { it.id in selected }
            } else {
                val oldFirst = goal.pending.indexOfFirst { it.id in existingIds }
                if (oldFirst >= 0) goal.pending.take(oldFirst).count { it.id !in (existingIds + selected).toSet() } else 0
            }
            val remaining = goal.pending.filterNot { it.id in (existingIds + selected).toSet() }
            Text("保存后位于原队列第 ${insertPosition + 1} 项；未选步骤顺序保持不变：${remaining.joinToString(" → ") { it.title }.ifEmpty { "无" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(name, { if(it.length <= 60) name = it }, Modifier.fillMaxWidth(), label = { Text("组名（可选）") }, singleLine = true, enabled = !busy)
        Text("执行方式", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == StepGroupMode.SINGLE.name, { mode = StepGroupMode.SINGLE.name }, { Text("单次") }, enabled = !busy)
            FilterChip(mode == StepGroupMode.MANUAL.name, { mode = StepGroupMode.MANUAL.name }, { Text("手动循环") }, enabled = !busy)
            FilterChip(mode == StepGroupMode.QUANTITY.name, { mode = StepGroupMode.QUANTITY.name }, { Text("累计达标") }, enabled = !busy)
        }
        if(mode == StepGroupMode.QUANTITY.name) {
            OutlinedTextField(target, { if(it.length <= 19) target = it }, Modifier.fillMaxWidth(), label = { Text("组级目标数值") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
            OutlinedTextField(unit, { if(it.length <= 12) unit = it }, Modifier.fillMaxWidth(), label = { Text("单位") }, singleLine = true, enabled = !busy)
        }
        if(selected.any { id -> eligible.first { it.id == id }.isRecurring }) Text("所选步骤包含尚未启动的独立循环。保存后将改为整组循环，原单步骤循环不再单独生成副本。", color = MaterialTheme.colorScheme.primary)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try {
                require(selected.size >= 2) { "请至少选择两个步骤" }
                val selectedMode = StepGroupMode.valueOf(mode)
                val amount = if(selectedMode == StepGroupMode.QUANTITY) Quantity.normalize(target) else null
                val cleanUnit = if(amount != null) Quantity.unit(unit) else null
                onSave(selected, name, selectedMode, amount, cleanUnit)
            } catch(e: IllegalArgumentException) { error = e.message }
        }, Modifier.fillMaxWidth().testTag("save-group"), enabled = !busy && eligible.size >= 2) { Text(if(existing == null) "预览无误，建立关联" else "保存关联修改") }
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
