package com.jintian.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun DeadlinePicker(date: LocalDate, today: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val lastDate = today.plusWeeks(4)
    val initial = date.coerceIn(today, lastDate)
    var chosenText by rememberSaveable(today.toString()) { mutableStateOf(initial.toString()) }
    var monthText by rememberSaveable(today.toString()) { mutableStateOf(YearMonth.from(initial).toString()) }
    val chosen = LocalDate.parse(chosenText)
    val month = YearMonth.parse(monthText)
    val colors = MaterialTheme.colorScheme
    val fullDate = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择截止日期") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = colors.primary, contentColor = colors.onPrimary, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("calendar-today-banner")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("今天", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(today.format(fullDate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthText = month.minusMonths(1).toString() }, enabled = month > YearMonth.from(today), modifier = Modifier.testTag("calendar-previous")) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月")
                    }
                    Text("${month.year}年${month.monthValue}月", textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("calendar-month"))
                    IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = month < YearMonth.from(lastDate), modifier = Modifier.testTag("calendar-next")) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                    }
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                val rows = (offset + month.lengthOfMonth() + 6) / 7
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(rows) { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(7) { day ->
                                val number = week * 7 + day - offset + 1
                                if(number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(52.dp))
                                else {
                                    val value = month.atDay(number)
                                    val isToday = value == today
                                    val isSelected = value == chosen
                                    val selectable = value in today..lastDate
                                    Surface(
                                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("calendar-day-$value")
                                            .clickable(enabled = selectable, role = Role.Button) { chosenText = value.toString() }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = "${value.format(fullDate)}${if(isToday) "，今天" else ""}"
                                                selected = isSelected
                                            },
                                        color = when { isSelected -> colors.primary; isToday -> colors.primaryContainer; else -> colors.surface },
                                        contentColor = when { isSelected -> colors.onPrimary; isToday -> colors.onPrimaryContainer; !selectable -> colors.onSurface.copy(alpha = .35f); else -> colors.onSurface },
                                        border = if(isToday) BorderStroke(2.dp, colors.primary) else null,
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            Text(number.toString(), style = MaterialTheme.typography.bodyLarge, fontWeight = if(isToday || isSelected) FontWeight.Bold else FontWeight.Normal)
                                            if(isToday || isSelected) Text(if(isToday) "今天" else "已选", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { chosenText = today.toString(); monthText = YearMonth.from(today).toString() }, modifier = Modifier.align(Alignment.End).testTag("calendar-select-today")) { Text("选择今天") }
                Text("已选：${chosen.format(fullDate)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("calendar-selection"))
                Text("可选今天至 ${lastDate.monthValue}月${lastDate.dayOfMonth}日，最长四周。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }, modifier = Modifier.testTag("confirm-deadline")) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel-deadline")) { Text("取消") } },
    )
}
