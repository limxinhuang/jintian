package com.jintian.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jintian.app.MainViewModel
import com.jintian.app.domain.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

typealias EditState = ((AppState) -> AppState, () -> Unit) -> Unit

@Composable
fun JintianApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }
    if(state == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (loadError == null) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("正在读取目标…") }
                else { Text(loadError!!); Spacer(Modifier.height(16.dp)); Button(onClick = viewModel::load) { Text("重试") } }
            }
        }
    } else JintianContent(state!!, busy, { change, done -> viewModel.edit(change, done) }, snackbar,backupContent={BackupRoute(viewModel)})
}

@Composable
fun JintianContent(state: AppState, busy: Boolean, onEdit: EditState, snackbar: SnackbarHostState = remember { SnackbarHostState() }, backupContent: (@Composable () -> Unit)? = null) {
    var route by rememberSaveable { mutableStateOf("home") }
    var goalId by rememberSaveable { mutableStateOf("") }
    var taskId by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("home") }
    var detailParent by rememberSaveable { mutableStateOf("home") }
    var endingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var quantityTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val now = rememberNow()
    val day = DayClock.at(now.atZone(ZoneId.systemDefault()))
    val scope = rememberCoroutineScope()
    val selected = state.goals.find { it.id == goalId }
    fun home() { route = "home"; tab = "home" }
    fun detail(id: String) { if(route != "detail" && route != "task") detailParent=route; goalId = id; route = "detail" }
    fun back() {
        if (busy) return
        route = when(route) { "task" -> "detail"; "detail" -> detailParent; "archives" -> tab; "backup" -> "stats"; "stats" -> "home"; else -> "home" }
        if(route == "home") tab="home"
    }
    fun editTask(id: String = "") { taskId = id; route = "task" }
    fun change(block: (AppState) -> AppState, message: String? = null) {
        onEdit(block) { if(message != null) scope.launch { snackbar.showSnackbar(message) } }
    }
    fun complete(id: String) {
        if(state.active?.takeIf { it.task.id == id }?.task?.isQuantified == true) quantityTaskId = id
        else change({ GoalRules.complete(it, id) }, "已完成，可以选择下一件事。")
    }
    val quantityTask = state.active?.takeIf { it.task.id == quantityTaskId && it.task.isQuantified }
    if(quantityTask != null) QuantityCompletionDialog(quantityTask.task, state.goal(quantityTask.goalId), busy,
        onDismiss = { quantityTaskId = null },
        onConfirm = { amount ->
            onEdit({ GoalRules.complete(it, quantityTask.task.id, amount = amount) }) {
                quantityTaskId = null
                scope.launch { snackbar.showSnackbar("本次完成量已记录。") }
            }
        })
    val ending = state.active?.takeIf { it.task.id == endingTaskId && it.task.isRecurring }
    if(ending != null) AlertDialog(
        onDismissRequest = { if(!busy) endingTaskId = null },
        title = { Text("完成本次并结束循环？") },
        text = { Text("本次会计入完成次数。这个循环自动生成的待执行和下一项将被移除，已有完成记录会保留。") },
        confirmButton = { TextButton(onClick = {
            onEdit({ GoalRules.completeAndEndRecurrence(it, ending.task.id) }) {
                endingTaskId = null
                scope.launch { snackbar.showSnackbar("本次已完成，循环已结束。") }
            }
        }, enabled = !busy, modifier = Modifier.testTag("confirm-end-recurrence")) { Text("完成并结束") } },
        dismissButton = { TextButton(onClick = { endingTaskId = null }, enabled = !busy) { Text("取消") } },
    )
    BackHandler(route != "home") { back() }
    val heading = when(route) { "new-goal" -> "创建目标"; "task" -> if(taskId.isEmpty()) "拆出一个小步骤" else "编辑步骤"; "detail" -> selected?.title ?: "目标详情"; "archives" -> "已归档目标"; "stats" -> "统计"; "backup" -> "数据备份"; else -> "今天" }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if(route != "new-goal" && route != "task") NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected=tab=="home",onClick=::home,enabled=!busy,icon={Icon(Icons.Outlined.Home,null)},label={Text("今天")},modifier=Modifier.testTag("tab-home"))
                NavigationBarItem(selected=tab=="stats",onClick={tab="stats";route="stats"},enabled=!busy,icon={Icon(Icons.Outlined.BarChart,null)},label={Text("统计")},modifier=Modifier.testTag("tab-stats"))
            }
        },
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                if(route == "home") Text(day.date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if(route != "home" && route != "stats") IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回") }
                    Text(heading, Modifier.weight(1f).padding(start = if(route == "home") 4.dp else 0.dp), style = if(route == "home") MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    if(route == "home") {
                        IconButton(onClick = { route = "archives" }) { Icon(Icons.Outlined.Inventory2,"已归档目标") }
                        FilledTonalButton(onClick = { route = "new-goal" }, enabled = !busy && state.visibleGoals.size < 5, modifier = Modifier.testTag("new-goal"), contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Outlined.Add,null,Modifier.size(18.dp)); Text("目标") }
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            key(route, goalId, taskId) {
                when(route) {
                    "new-goal" -> GoalForm(busy, onSave = { title, end ->
                        val id = UUID.randomUUID().toString()
                        onEdit({ GoalRules.addGoal(it,title,end,id=id) }) { detailParent="home";goalId=id;route="detail" }
                    }, onCancel = ::home)
                    "task" -> if(selected != null) {
                        val t = selected.next?.takeIf { it.id == taskId } ?: selected.pending.find { it.id == taskId }
                        TaskForm(selected,t,t != null && selected.next?.id == t.id,busy,onSave = { title, recurring, amount, unit ->
                            onEdit({ if(t == null) GoalRules.addTask(it,selected.id,Task(title=title,seriesId=if(recurring) UUID.randomUUID().toString() else null,targetAmount=amount,unit=unit)) else GoalRules.editTask(it,selected.id,t.id,title) }) { route = "detail" }
                        },onCancel = { route = "detail" })
                    }
                    "detail" -> if(selected != null) GoalDetail(state,selected,day.date,now,busy,
                        onEndRecurrence = { endingTaskId = it },
                        onStart = { selected.next?.id?.let { id -> change({ GoalRules.start(it,selected.id,id,System.currentTimeMillis()) }) } },
                        onComplete = ::complete,
                        onLock = { change({ GoalRules.lockNext(it,selected.id) },"下一项已锁定。") },
                        onAdd = { editTask() }, onEditTask = { editTask(it) },
                        onDelete = { id -> change({ GoalRules.deleteTask(it,selected.id,id) }) },
                        onMove = { id, index -> change({ GoalRules.moveTask(it,selected.id,id,index) }) },
                        onArchive = { onEdit({ GoalRules.archive(it,selected.id) }) { home(); scope.launch { snackbar.showSnackbar("目标已完成并归档。") } } },
                        onDeleteEmpty = { onEdit({ GoalRules.deleteEmptyGoal(it,selected.id) }) { home() } })
                    "archives" -> ArchiveList(state.goals.filter { it.archived },::detail)
                    "stats" -> StatisticsScreen(state,now,::detail,onBackup=if(backupContent!=null) {{route="backup"}} else null)
                    "backup" -> backupContent?.invoke()
                    else -> HomeScreen(state,day,now,busy,::detail,{ route = "new-goal" },
                        onEndRecurrence = { endingTaskId = it },
                        onStart = { id, nextId -> change({ GoalRules.start(it,id,nextId,System.currentTimeMillis()) }) },
                        onComplete = ::complete)
                }
            }
        }
    }
}

private fun due(goal: Goal, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today,goal.end)
    return when { days < 0 -> "已到期 ${-days} 天"; days == 0L -> "今天截止"; else -> "还剩 $days 天" }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ActiveCard(state: AppState, now: Instant, busy: Boolean, onGoal: (String) -> Unit, onComplete: (String) -> Unit, onEndRecurrence: (String) -> Unit) {
    Panel(active = true, modifier = Modifier.testTag("active-card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary,CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("正在执行", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("一次一件事", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val active = state.active
        if(active == null) {
            Text("现在，选择一件事开始", style = MaterialTheme.typography.titleMedium)
            Text(if(state.visibleGoals.isEmpty()) "先创建一个近期想完成的目标。" else "从任意目标的下一项开始。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(active.task.title, style = MaterialTheme.typography.titleLarge)
            TaskKind(active.task, state.goal(active.goalId))
            TextButton(onClick = { onGoal(active.goalId) }, contentPadding = PaddingValues(0.dp)) { Text(state.goal(active.goalId).title + " ↗") }
            val started=Instant.ofEpochMilli(active.startedAt).atZone(ZoneId.systemDefault())
            val seconds=TaskTime.elapsedSeconds(active.startedAt,now.toEpochMilli())
            Text("开始于 ${started.format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))}", Modifier.testTag("active-start-time"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("已持续",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(TaskTime.clockText(seconds),Modifier.testTag("active-elapsed"),style=MaterialTheme.typography.headlineSmall,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            if(seconds >= 86400) Text("这个步骤已超过一天，请尽快完成。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            Button(onClick = { onComplete(active.task.id) }, Modifier.fillMaxWidth().testTag("complete-task"), enabled = !busy) { Icon(Icons.Outlined.Check,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(active.task.isRecurring) "完成本次" else "完成这一步") }
            if(active.task.isRecurring && !active.task.isQuantified) TextButton(onClick = { onEndRecurrence(active.task.id) }, Modifier.fillMaxWidth().testTag("end-recurrence"), enabled = !busy) { Text("完成并结束循环") }
        }
    }
}

@Composable
private fun NextTask(task: Task, goal: Goal, startEnabled: Boolean, onStart: () -> Unit, onEdit: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("下一个 · 已锁定顺序", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(onEdit != null) TextButton(onClick = onEdit) { Text("编辑文字") }
    }
    Text(task.title, style = MaterialTheme.typography.titleMedium)
    TaskKind(task, goal)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onStart, enabled = startEnabled, modifier = Modifier.testTag("start-${task.id}")) { Text("开始 →") }
    }
}

@Composable
private fun HomeScreen(state: AppState, day: DayProgress, now: Instant, busy: Boolean, onGoal: (String) -> Unit, onAdd: () -> Unit, onStart: (String,String) -> Unit, onComplete: (String) -> Unit, onEndRecurrence: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("day") { DayCountdown(day) }
        item("active") { ActiveCard(state,now,busy,onGoal,onComplete,onEndRecurrence) }
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("接下来",style=MaterialTheme.typography.titleMedium)
                    Text("${state.visibleGoals.size} / 5 个目标",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(state.active != null) Text("完成当前任务后，再开始下一项。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.visibleGoals.size == 5) Text("五个目标已满，完成并归档后可创建新目标。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(state.visibleGoals.isEmpty()) item("empty") {
            Panel {
                Text("把一件想做的事，变成下一步。",style=MaterialTheme.typography.titleMedium)
                Text("设定四周内的目标，再拆成一天内能完成的小任务。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick=onAdd,enabled=!busy) { Text("创建第一个目标") }
            }
        }
        items(state.visibleGoals,key={it.id}) { goal ->
            Panel {
                Row(Modifier.fillMaxWidth().clickable { onGoal(goal.id) },verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(goal.title,style=MaterialTheme.typography.titleMedium); Text(due(goal,day.date),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick={onGoal(goal.id)},modifier=Modifier.testTag("detail-${goal.id}")) { Text("详情 ↗") }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                if(goal.next != null) NextTask(goal.next,goal,!busy && state.active == null,{onStart(goal.id,goal.next.id)})
                else {
                    Text(if(goal.pending.isNotEmpty()) "待确认下一项" else "暂无下一项",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GoalDetail(state: AppState, goal: Goal, today: LocalDate, now: Instant, busy: Boolean, onStart: () -> Unit, onComplete: (String) -> Unit, onLock: () -> Unit, onAdd: () -> Unit, onEditTask: (String) -> Unit, onDelete: (String) -> Unit, onMove: (String,Int) -> Unit, onArchive: () -> Unit, onDeleteEmpty: () -> Unit, onEndRecurrence: (String) -> Unit) {
    var showDone by rememberSaveable { mutableStateOf(goal.archived) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteEmpty by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val drag = rememberTaskDragState(list,goal.pending,onMove)
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(drag.draggedId) { if(drag.draggedId != null) drag.autoScroll(edge) }
    if(deleteId != null) AlertDialog(onDismissRequest={deleteId=null},title={Text("删除这个待执行任务？")},text={Text(goal.pending.find { it.id==deleteId }?.title ?: "")},confirmButton={TextButton(onClick={deleteId?.let(onDelete);deleteId=null},enabled=!busy){Text("删除")}},dismissButton={TextButton(onClick={deleteId=null}){Text("保留")}})
    if(deleteEmpty) AlertDialog(onDismissRequest={deleteEmpty=false},title={Text("删除这个空目标？")},text={Text(goal.title)},confirmButton={TextButton(onClick={deleteEmpty=false;onDeleteEmpty()},enabled=!busy){Text("删除")}},dismissButton={TextButton(onClick={deleteEmpty=false}){Text("保留")}})
    LazyColumn(Modifier.fillMaxSize().testTag("goal-list").pointerInput(drag,busy) {
        if(!busy) detectDragGesturesAfterLongPress(onDragStart=drag::start,onDragEnd=drag::finish,onDragCancel=drag::cancel,onDrag={change,amount->if(drag.draggedId!=null){change.consume();drag.drag(amount)}})
    },state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item("dates") { Text("截止 ${goal.end} · ${if(goal.archived) "已归档" else due(goal,today)}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(state.active?.goalId == goal.id) item("active") { ActiveCard(state,now,busy,{},onComplete,onEndRecurrence) }
        if(!goal.archived) {
            item("next") {
                Panel {
                    if(goal.next != null) NextTask(goal.next,goal,!busy && state.active==null,onStart,{onEditTask(goal.next.id)})
                    else {
                        Text("下一个",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(goal.pending.isEmpty()) "暂无下一项" else "排好顺序后，确认第一项。",style=MaterialTheme.typography.titleMedium)
                        if(goal.pending.isNotEmpty()) Button(onClick=onLock,enabled=!busy,modifier=Modifier.testTag("lock-next")) { Text("确认下一项") }
                    }
                    if(state.active != null) Text("完成当前正在执行的任务后，才能开始下一项。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("pending-heading") {
                Column {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("待执行 · ${goal.pending.size}",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=onAdd,enabled=!busy,modifier=Modifier.testTag("add-task")) { Text("＋ 添加步骤") }
                    }
                    Text("长按任意卡片拖动排序。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(goal.pending.isEmpty()) item("no-pending") { Text("待执行清单为空，新任务会按添加顺序排列。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(drag.tasks,key={_,t->"task-${t.id}"}) { index, task ->
                PendingRow(task,goal,GoalRules.canDeleteTask(state,goal,task),index,goal.pending.size,busy,drag.draggedId==task.id,drag.offset(task.id),{onMove(task.id,it)},{onEditTask(task.id)},{deleteId=task.id})
            }
        }
        item("done-heading") { TextButton(onClick={showDone=!showDone}) { Text("${if(showDone) "▾" else "▸"} 已完成 · ${goal.done.size}") } }
        if(showDone) {
            if(goal.done.isEmpty()) item("no-done") { Text("完成的任务会保留在这里。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(goal.done,key={"done-${it.id}"}) { t -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Check,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(t.title,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TaskKind(t, goal)
                    if(t.startedAt!=null && t.completedAt!=null) {
                        Text("${Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} 开始 · 用时 ${TaskTime.shortText(TaskTime.completedSeconds(t))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
        }
        if(GoalRules.canArchive(state,goal)) item("archive") { Button(onClick=onArchive,Modifier.fillMaxWidth(),enabled=!busy) { Text("完成并归档目标") } }
        if(!goal.archived && goal.pending.isEmpty() && goal.next==null && goal.done.isEmpty() && state.active?.goalId!=goal.id) item("delete-empty") { TextButton(onClick={deleteEmpty=true},enabled=!busy) { Text("删除空目标") } }
    }
}

@Composable
private fun PendingRow(task: Task, goal: Goal, canDelete: Boolean, index: Int, count: Int, busy: Boolean, dragging: Boolean, offset: Float, onMove: (Int) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(Modifier.fillMaxWidth().zIndex(if(dragging) 1f else 0f).graphicsLayer { translationY=offset }.testTag("pending-${task.id}").semantics {
        customActions=if(busy) emptyList() else buildList {
            if(index>0) add(CustomAccessibilityAction("前移一位") { onMove(index-1);true })
            if(index<count-1) add(CustomAccessibilityAction("后移一位") { onMove(index+1);true })
        }
    },shape=RoundedCornerShape(20.dp),color=if(dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,shadowElevation=if(dragging) 8.dp else 0.dp) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(String.format(Locale.ROOT,"%02d",index+1),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(task.title,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                Icon(Icons.Outlined.DragHandle,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TaskKind(task, goal)
            if(!canDelete) Text(if(task.isQuantified) "累计达到目标后自动结束循环。" else "循环已开始，执行时可选择结束循环。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick=onEdit,enabled=!busy) { Text("编辑") }
                if(canDelete) TextButton(onClick=onDelete,enabled=!busy) { Text("删除") }
            }
        }
    }
}

@Composable
internal fun TaskKind(task: Task, goal: Goal) {
    Text(if(task.isRecurring) "${if(task.isQuantified) "累计型循环" else "循环型"} · 已完成 ${goal.completedCount(task)} 次${if(task.seriesEnded) " · 已结束" else ""}" else "单次型",
        style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.primary,
        modifier=Modifier.testTag("task-kind-${task.id}"))
    if(task.isQuantified) {
        val accumulated = goal.accumulated(task)
        Text("累计 ${Quantity.format(accumulated)} / ${task.targetAmount} ${task.unit}", style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.primary, modifier=Modifier.testTag("task-quantity-${task.id}"))
        LinearProgressIndicator(progress={ accumulated.divide(Quantity.parse(task.targetAmount!!), 6, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) }, modifier=Modifier.fillMaxWidth(), drawStopIndicator={})
        task.completedAmount?.let { Text("本次完成 $it ${task.unit}", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ArchiveList(goals: List<Goal>, onGoal: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(goals.isEmpty()) item { Text("完成并归档的目标会保留在这里，不占用五个目标的名额。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        items(goals,key={it.id}) { goal -> Panel(Modifier.clickable { onGoal(goal.id) }) { Text(goal.title,style=MaterialTheme.typography.titleMedium);Text("${goal.done.size} 项任务已完成 · 查看记录 ↗",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
