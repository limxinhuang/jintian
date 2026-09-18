package com.jintian.app.domain

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.math.BigDecimal

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val seriesId: String? = null,
    val seriesEnded: Boolean = false,
    val targetAmount: String? = null,
    val unit: String? = null,
    val completedAmount: String? = null,
) {
    val isRecurring get() = seriesId != null
    val isQuantified get() = targetAmount != null
}
data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    val pending: List<Task> = emptyList(),
    val next: Task? = null,
    val done: List<Task> = emptyList(),
    val archived: Boolean = false,
    val createdAt: Long? = null,
) {
    fun completedCount(task: Task): Int = if (task.isRecurring) done.count { it.seriesId == task.seriesId } else 0
    fun accumulated(task: Task): BigDecimal = if (!task.isQuantified) BigDecimal.ZERO else
        done.filter { it.seriesId == task.seriesId }.fold(BigDecimal.ZERO) { sum, t -> sum + Quantity.parse(requireNotNull(t.completedAmount)) }
}
data class ActiveTask(val goalId: String, val task: Task, val startedAt: Long)
data class AppState(val goals: List<Goal> = emptyList(), val active: ActiveTask? = null) {
    val visibleGoals get() = goals.filterNot { it.archived }
    fun goal(id: String): Goal = goals.find { it.id == id } ?: throw IllegalArgumentException("目标不存在")
}

/** All edits return a new valid snapshot, including the one global execution slot. */
object GoalRules {
    fun goalTitle(title: String): String = title.trim().also {
        require(it.isNotEmpty() && it.length <= 60) { "目标名称需为 1～60 个字" }
    }
    fun taskTitle(title: String): String = title.trim().also {
        require(it.isNotEmpty() && it.length <= 120) { "任务内容需为 1～120 个字" }
    }
    fun validateDates(start: LocalDate, end: LocalDate) {
        require(ChronoUnit.DAYS.between(start, end) in 0..28) { "截止日期需在今天至四周后（28 天内）" }
    }
    fun validateTask(task: Task) {
        taskTitle(task.title)
        require((task.startedAt == null) == (task.completedAt == null)) { "完成记录的时间不完整" }
        require(task.seriesId == null || task.seriesId.isNotBlank()) { "循环编号为空" }
        require(!task.seriesEnded || task.isRecurring) { "单次型步骤不能有循环结束标记" }
        if(task.isQuantified) {
            require(task.isRecurring) { "数值累计仅适用于循环步骤" }
            Quantity.parse(task.targetAmount!!)
            Quantity.unit(requireNotNull(task.unit) { "请填写单位" })
            task.completedAmount?.let { Quantity.parse(it) }
        } else require(task.unit == null && task.completedAmount == null) { "非累计步骤不能包含数值记录" }
    }
    fun validate(state: AppState) {
        require(state.visibleGoals.size <= 5) { "同时最多保留五个目标" }
        require(state.goals.map { it.id }.distinct().size == state.goals.size) { "目标编号重复" }
        val allTasks = mutableListOf<Task>()
        state.goals.forEach { g ->
            goalTitle(g.title)
            // Old releases allowed 29 days. Keep those saved goals and backups readable;
            // new goals always use the stricter creation rule above.
            require(ChronoUnit.DAYS.between(g.start, g.end) in 0..29) { "目标日期范围无效" }
            require(g.id.isNotBlank()) { "目标编号为空" }
            require(!g.archived || (g.pending.isEmpty() && g.next == null && g.done.isNotEmpty())) { "未完成的目标不能归档" }
            require((g.pending + listOfNotNull(g.next)).all { it.startedAt == null && it.completedAt == null }) { "待执行步骤不能包含执行记录" }
            allTasks.addAll(g.pending); allTasks.addAll(g.done); g.next?.let(allTasks::add)
        }
        state.active?.let {
            require(!state.goal(it.goalId).archived) { "已归档目标不能执行任务" }
            require(it.task.startedAt == null && it.task.completedAt == null) { "当前步骤不能已经完成" }
            allTasks.add(it.task)
        }
        allTasks.forEach { require(it.id.isNotBlank()); validateTask(it) }
        require(allTasks.map { it.id }.distinct().size == allTasks.size) { "一个任务只能处于一个状态" }
        val owners = mutableMapOf<String, String>()
        state.goals.forEach { g ->
            val upcoming = g.pending + listOfNotNull(g.next, state.active?.takeIf { it.goalId == g.id }?.task)
            require(upcoming.none { it.seriesEnded }) { "已结束的循环不能继续执行" }
            require(upcoming.all { it.completedAmount == null }) { "未完成步骤不能包含本次完成量" }
            require(g.done.all { !it.isQuantified || (it.completedAmount != null && it.completedAt != null) }) { "累计步骤的完成数值或时间缺失" }
            (upcoming + g.done).filter { it.isRecurring }.groupBy { it.seriesId!! }.forEach { (series, tasks) ->
                require(owners.put(series, g.id) == null) { "同一个循环不能属于多个目标" }
                require(tasks.map { it.seriesEnded }.distinct().size == 1) { "循环结束状态不一致" }
                require(g.pending.count { it.seriesId == series } == if(tasks.first().seriesEnded) 0 else 1) { "进行中的循环必须保留一个待执行副本" }
                require(tasks.map { it.targetAmount?.let(Quantity::normalize) to it.unit }.distinct().size == 1) { "同一循环的目标数值和单位必须一致" }
                val first = tasks.first()
                if(first.isQuantified) require(first.seriesEnded == (g.accumulated(first) >= Quantity.parse(first.targetAmount!!))) { "循环结束状态与累计数值不一致" }
            }
        }
    }
    private fun update(state: AppState, id: String, block: (Goal) -> Goal): AppState {
        val goal = state.goal(id)
        require(!goal.archived) { "已归档目标不能修改" }
        return state.copy(goals = state.goals.map { if (it.id == id) block(it) else it })
    }
    fun addGoal(state: AppState, title: String, end: LocalDate, now: ZonedDateTime = ZonedDateTime.now(), id: String = UUID.randomUUID().toString()): AppState {
        require(state.visibleGoals.size < 5) { "最多五个目标，请先完成并归档一个" }
        val start = now.toLocalDate()
        validateDates(start, end)
        require(state.goals.none { it.id == id }) { "目标已存在" }
        return state.copy(goals = state.goals + Goal(id, goalTitle(title), start, end, createdAt = now.toInstant().toEpochMilli()))
    }
    fun addTask(state: AppState, goalId: String, task: Task): AppState {
        validateTask(task)
        if (task.isRecurring) require(state.goals.none { g ->
            (g.pending + g.done + listOfNotNull(g.next)).any { it.seriesId == task.seriesId }
        } && state.active?.task?.seriesId != task.seriesId) { "新增循环必须使用独立编号" }
        return update(state, goalId) { it.copy(pending = it.pending + task.copy(title = task.title.trim())) }.also(::validate)
    }
    fun editTask(state: AppState, goalId: String, taskId: String, title: String): AppState = update(state, goalId) { g ->
        val clean = taskTitle(title)
        val target = (g.pending + listOfNotNull(g.next)).find { it.id == taskId }
            ?: throw IllegalArgumentException("只能编辑待执行任务或下一项的文字")
        if (target.isRecurring) return@update g.copy(
            pending = g.pending.map { if (it.seriesId == target.seriesId) it.copy(title = clean) else it },
            next = g.next?.let { if (it.seriesId == target.seriesId) it.copy(title = clean) else it },
        )
        if (g.next?.id == taskId) {
            // A locked next item may only have its wording corrected.
            g.copy(next = g.next.copy(title = clean))
        } else {
            require(g.pending.any { it.id == taskId }) { "只能编辑待执行任务或下一项的文字" }
            g.copy(pending = g.pending.map { if (it.id == taskId) it.copy(title = clean) else it })
        }
    }
    fun deleteTask(state: AppState, goalId: String, taskId: String): AppState = update(state, goalId) { g ->
        require(g.pending.any { it.id == taskId }) { "只能删除待执行任务" }
        require(canDeleteTask(state, g, g.pending.first { it.id == taskId })) { "已开始的循环不能单独删除后续步骤" }
        g.copy(pending = g.pending.filterNot { it.id == taskId })
    }
    fun canDeleteTask(state: AppState, goal: Goal, task: Task): Boolean = !task.isRecurring ||
        (goal.done + listOfNotNull(goal.next, state.active?.takeIf { it.goalId == goal.id }?.task))
            .none { it.seriesId == task.seriesId }

    /** Only promotion generates a successor. Reading, saving and completing never do. */
    private fun promote(goal: Goal): Goal {
        val next = goal.pending.firstOrNull()
        val tail = goal.pending.drop(1)
        val successor = next?.takeIf { it.isRecurring }?.copy(id = UUID.randomUUID().toString())
        return goal.copy(next = next, pending = tail + listOfNotNull(successor))
    }
    fun moveTask(state: AppState, goalId: String, taskId: String, targetIndex: Int): AppState = update(state, goalId) { g ->
        val from = g.pending.indexOfFirst { it.id == taskId }
        require(from >= 0 && targetIndex in g.pending.indices) { "只能调整待执行任务的顺序" }
        val tasks = g.pending.toMutableList()
        tasks.add(targetIndex, tasks.removeAt(from))
        g.copy(pending = tasks)
    }
    fun lockNext(state: AppState, goalId: String): AppState = update(state, goalId) { g ->
        require(g.next == null && g.pending.isNotEmpty()) { "当前没有可确认的下一项" }
        promote(g)
    }
    fun start(state: AppState, goalId: String, taskId: String, now: Long): AppState {
        require(state.active == null) { "请先完成当前正在执行的任务" }
        val g = state.goal(goalId)
        val task = g.next ?: throw IllegalArgumentException("请先确认下一项")
        require(task.id == taskId) { "下一项已发生变化" }
        return update(state, goalId, ::promote)
            .copy(active = ActiveTask(goalId, task, now))
    }
    fun complete(state: AppState, taskId: String, now: Long = System.currentTimeMillis(), amount: String? = null): AppState {
        val current = state.active ?: throw IllegalArgumentException("当前没有正在执行的任务")
        require(current.task.id == taskId) { "正在执行的任务已发生变化" }
        val quantity = if(current.task.isQuantified) Quantity.normalize(requireNotNull(amount) { "请填写本次完成数值" })
            else { require(amount == null) { "此步骤不需要填写数值" }; null }
        val completed = current.task.copy(startedAt = current.startedAt, completedAt = now, completedAmount = quantity)
        val finished = update(state, current.goalId) { it.copy(done = it.done + completed) }.copy(active = null)
        return if(completed.isQuantified && finished.goal(current.goalId).accumulated(completed) >= Quantity.parse(completed.targetAmount!!))
            endSeries(finished, current.goalId, completed.seriesId!!).also(::validate)
        else finished
    }
    fun completeAndEndRecurrence(state: AppState, taskId: String, now: Long = System.currentTimeMillis()): AppState {
        val current = state.active ?: throw IllegalArgumentException("当前没有正在执行的任务")
        require(current.task.id == taskId && current.task.isRecurring) { "只能结束正在执行的循环步骤" }
        require(!current.task.isQuantified) { "累计型循环达到目标后会自动结束" }
        return endSeries(complete(state, taskId, now), current.goalId, current.task.seriesId!!).also(::validate)
    }
    private fun endSeries(state: AppState, goalId: String, series: String): AppState =
        update(state, goalId) { g ->
            g.copy(
                pending = g.pending.filterNot { it.seriesId == series },
                next = g.next?.takeUnless { it.seriesId == series },
                done = g.done.map { if (it.seriesId == series) it.copy(seriesEnded = true) else it },
            )
        }
    fun canArchive(state: AppState, g: Goal) = !g.archived && g.done.isNotEmpty() && g.pending.isEmpty() && g.next == null && state.active?.goalId != g.id
    fun archive(state: AppState, goalId: String): AppState = update(state, goalId) {
        require(canArchive(state, it)) { "完成目标内全部任务后才能归档" }
        it.copy(archived = true)
    }
    fun deleteEmptyGoal(state: AppState, goalId: String): AppState {
        val g = state.goal(goalId)
        require(!g.archived && g.next == null && g.pending.isEmpty() && g.done.isEmpty() && state.active?.goalId != goalId) { "只能删除尚无步骤的空目标" }
        return state.copy(goals = state.goals.filterNot { it.id == goalId })
    }
}
