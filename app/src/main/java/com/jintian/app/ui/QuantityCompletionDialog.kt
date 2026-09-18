package com.jintian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jintian.app.domain.Goal
import com.jintian.app.domain.Quantity
import com.jintian.app.domain.Task

@Composable
internal fun QuantityCompletionDialog(task: Task, goal: Goal, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var input by rememberSaveable(task.id) { mutableStateOf("") }
    var error by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    val amount = runCatching { Quantity.parse(input) }.getOrNull()
    val accumulated = goal.accumulated(task)
    val total = amount?.let { accumulated + it }
    val reached = total != null && total >= Quantity.parse(task.targetAmount!!)
    AlertDialog(
        onDismissRequest = { if(!busy) onDismiss() },
        title = { Text("记录本次完成量") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.title)
                Text("已累计 ${Quantity.format(accumulated)} / ${task.targetAmount} ${task.unit}")
                OutlinedTextField(input, { if(it.length <= 19) { input = it; error = null } }, Modifier.fillMaxWidth().testTag("completion-amount"), label = { Text("本次完成数值（${task.unit}）") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = error != null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if(total != null) Text("本次计入后：${Quantity.format(total)} / ${task.targetAmount} ${task.unit}")
                if(reached) Text("已达到目标。本次完成后将自动结束循环，移除同一循环的后续步骤，保留全部完成记录。")
                else Text("填写本次增加的数量，支持小数。达到或超过目标后自动结束循环。")
            }
        },
        confirmButton = { TextButton(onClick = {
            try { onConfirm(Quantity.normalize(input)) } catch(e: IllegalArgumentException) { error = e.message }
        }, enabled = !busy, modifier = Modifier.testTag("confirm-quantity")) { Text(if(reached) "完成并结束循环" else "确认完成") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
