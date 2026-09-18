package com.jintian.app.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class StepGroupMode { SINGLE, MANUAL, QUANTITY }
data class GroupMemberTemplate(val id: String = UUID.randomUUID().toString(), val title: String)
data class StepGroup(
    val id: String = UUID.randomUUID().toString(), val name: String, val mode: StepGroupMode,
    val members: List<GroupMemberTemplate>, val targetAmount: String? = null, val unit: String? = null,
    val ended: Boolean = false,
)
data class StepGroupRound(
    val id: String = UUID.randomUUID().toString(), val groupId: String, val number: Int,
    val memberIds: List<String>, val completedAt: Long? = null, val completedAmount: String? = null,
)

data class Task(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val startedAt: Long? = null, val completedAt: Long? = null,
    val seriesId: String? = null, val seriesEnded: Boolean = false,
    val targetAmount: String? = null, val unit: String? = null, val completedAmount: String? = null,
    val groupId: String? = null, val groupRoundId: String? = null, val groupPosition: Int? = null,
) {
    val isRecurring get() = seriesId != null
    val isQuantified get() = targetAmount != null
    val isGrouped get() = groupId != null
}

data class Goal(
    val id: String = UUID.randomUUID().toString(), val title: String, val start: LocalDate, val end: LocalDate,
    val pending: List<Task> = emptyList(), val next: Task? = null, val done: List<Task> = emptyList(),
    val archived: Boolean = false, val createdAt: Long? = null,
    val groups: List<StepGroup> = emptyList(), val groupRounds: List<StepGroupRound> = emptyList(),
    val lockedGroupRoundId: String? = null,
) {
    fun completedCount(task: Task): Int = if (task.isRecurring) done.count { it.seriesId == task.seriesId } else 0
    fun accumulated(task: Task): BigDecimal = if (!task.isQuantified) BigDecimal.ZERO else done.filter { it.seriesId == task.seriesId }
        .fold(BigDecimal.ZERO) { sum, t -> sum + Quantity.parse(requireNotNull(t.completedAmount)) }
    fun group(id: String) = groups.find { it.id == id } ?: throw IllegalArgumentException("关联组不存在")
    fun round(id: String) = groupRounds.find { it.id == id } ?: throw IllegalArgumentException("关联轮次不存在")
    fun completedRounds(groupId: String) = groupRounds.count { it.groupId == groupId && it.completedAt != null }
    fun groupAccumulated(groupId: String) = groupRounds.filter { it.groupId == groupId && it.completedAt != null }
        .fold(BigDecimal.ZERO) { sum, r -> sum + (r.completedAmount?.let(Quantity::parse) ?: BigDecimal.ZERO) }
}

data class ActiveTask(val goalId: String, val task: Task, val startedAt: Long)
data class AppState(val goals: List<Goal> = emptyList(), val active: ActiveTask? = null) {
    val visibleGoals get() = goals.filterNot { it.archived }
    fun goal(id: String) = goals.find { it.id == id } ?: throw IllegalArgumentException("目标不存在")
}

object GoalRules {
    fun goalTitle(title: String): String = title.trim().also { require(it.isNotEmpty() && it.length <= 60) { "目标名称需为 1～60 个字" } }
    fun taskTitle(title: String): String = title.trim().also { require(it.isNotEmpty() && it.length <= 120) { "任务内容需为 1～120 个字" } }
    fun groupName(name: String, first: String, count: Int): String = name.trim().ifEmpty { "$first 等 $count 步" }
        .also { require(it.length <= 60) { "关联组名称最多 60 个字" } }
    fun validateDates(start: LocalDate, end: LocalDate) {
        require(ChronoUnit.DAYS.between(start, end) in 0..28) { "截止日期需在今天至四周后（28 天内）" }
    }
    fun validateTask(task: Task) {
        taskTitle(task.title)
        require((task.startedAt == null) == (task.completedAt == null)) { "完成记录的时间不完整" }
        require(task.seriesId == null || task.seriesId.isNotBlank()) { "循环编号为空" }
        require(!task.seriesEnded || task.isRecurring) { "单次型步骤不能有循环结束标记" }
        if (task.isQuantified) {
            require(task.isRecurring) { "数值累计仅适用于循环步骤" }; Quantity.parse(task.targetAmount!!)
            Quantity.unit(requireNotNull(task.unit) { "请填写单位" }); task.completedAmount?.let(Quantity::parse)
        } else require(task.unit == null && task.completedAmount == null) { "非累计步骤不能包含数值记录" }
        val groupFields = listOf(task.groupId, task.groupRoundId, task.groupPosition)
        require(groupFields.all { it == null } || groupFields.all { it != null }) { "关联步骤信息不完整" }
        if (task.isGrouped) {
            require(!task.isRecurring && !task.seriesEnded) { "关联组成员不能同时保留独立循环" }
            require(task.groupId!!.isNotBlank() && task.groupRoundId!!.isNotBlank() && task.groupPosition!! >= 0) { "关联步骤信息无效" }
        }
    }

    fun validate(state: AppState) {
        require(state.visibleGoals.size <= 5) { "同时最多保留五个目标" }
        require(state.goals.map { it.id }.distinct().size == state.goals.size) { "目标编号重复" }
        val allTasks = mutableListOf<Task>()
        val groupOwners = mutableMapOf<String, String>()
        state.goals.forEach { g ->
            goalTitle(g.title); require(ChronoUnit.DAYS.between(g.start, g.end) in 0..29) { "目标日期范围无效" }
            require(g.id.isNotBlank()) { "目标编号为空" }
            require(!g.archived || (g.pending.isEmpty() && g.next == null && g.done.isNotEmpty() && state.active?.goalId != g.id && g.groups.all { it.ended || it.mode == StepGroupMode.SINGLE })) { "未完成的目标不能归档" }
            require((g.pending + listOfNotNull(g.next)).all { it.startedAt == null && it.completedAt == null }) { "待执行步骤不能包含执行记录" }
            allTasks += g.pending; allTasks += g.done; g.next?.let(allTasks::add)
            val goalTasks = g.pending + g.done + listOfNotNull(g.next, state.active?.takeIf { it.goalId == g.id }?.task)
            val byId = goalTasks.associateBy { it.id }
            require(g.groups.map { it.id }.distinct().size == g.groups.size) { "关联组编号重复" }
            require(g.groupRounds.map { it.id }.distinct().size == g.groupRounds.size) { "关联轮次编号重复" }
            g.groups.forEach { group ->
                require(groupOwners.put(group.id, g.id) == null) { "同一个关联组不能属于多个目标" }
                groupName(group.name, group.members.firstOrNull()?.title.orEmpty(), group.members.size)
                require(group.members.size >= 2 && group.members.map { it.id }.distinct().size == group.members.size) { "关联组至少需要两个不同成员" }
                group.members.forEach { taskTitle(it.title); require(it.id.isNotBlank()) }
                when (group.mode) {
                    StepGroupMode.QUANTITY -> { Quantity.parse(requireNotNull(group.targetAmount)); Quantity.unit(requireNotNull(group.unit)) }
                    else -> require(group.targetAmount == null && group.unit == null) { "非累计关联组不能包含数值目标" }
                }
                val rounds = g.groupRounds.filter { it.groupId == group.id }.sortedBy { it.number }
                require(rounds.isNotEmpty() && rounds.map { it.number } == (1..rounds.size).toList()) { "关联组轮次顺序无效" }
                val uncompleted = rounds.filter { it.completedAt == null }
                val isLocked = uncompleted.any { it.id == g.lockedGroupRoundId }
                if (group.ended) require(uncompleted.isEmpty()) { "已结束关联组不能保留未完成轮次" }
                else if (group.mode == StepGroupMode.SINGLE) {
                    require(uncompleted.size == 1) { "单次关联组必须且只能保留一个未完成轮次" }
                } else {
                    if (isLocked) require(uncompleted.size == 2) { "进行中的循环关联组必须保留一个未来轮次" }
                    else require(uncompleted.size == 1) { "未锁定循环关联组必须保留一个待安排轮次" }
                }
                if (group.mode == StepGroupMode.QUANTITY) {
                    require(rounds.all { (it.completedAt == null) == (it.completedAmount == null) }) { "累计关联组轮次数值不完整" }
                    require(group.ended == (rounds.all { it.completedAt != null } && g.groupAccumulated(group.id) >= Quantity.parse(group.targetAmount!!))) { "累计关联组结束状态与数值不一致" }
                } else require(rounds.all { it.completedAmount == null }) { "非累计关联组不能包含完成量" }
            }
            g.groupRounds.forEach { round ->
                val group = g.groups.find { it.id == round.groupId } ?: throw IllegalArgumentException("轮次引用了不存在的关联组")
                require(round.number > 0 && round.memberIds.size == group.members.size && round.memberIds.distinct().size == round.memberIds.size) { "关联轮次成员无效" }
                round.memberIds.forEachIndexed { index, id ->
                    val task = byId[id] ?: throw IllegalArgumentException("关联轮次缺少成员")
                    require(task.groupId == group.id && task.groupRoundId == round.id && task.groupPosition == index) { "关联轮次成员顺序矛盾" }
                }
                val completed = round.memberIds.mapIndexedNotNull { i, id -> i.takeIf { byId.getValue(id).completedAt != null } }
                require(completed == (0 until completed.size).toList()) { "关联组成员必须按顺序完成" }
                require((round.completedAt != null) == (completed.size == round.memberIds.size)) { "关联轮次完成状态不一致" }
            }
            require(goalTasks.filter { it.isGrouped }.all { t -> g.groupRounds.count { t.id in it.memberIds } == 1 }) { "关联步骤必须且只能属于一个轮次" }
            g.lockedGroupRoundId?.let { lock ->
                val round = g.round(lock); require(round.completedAt == null) { "不能锁定已完成轮次" }
                val completedCount = round.memberIds.count { byId.getValue(it).completedAt != null }
                val expectedId = round.memberIds[completedCount]
                val activeTask = state.active?.takeIf { it.goalId == g.id }?.task
                if (activeTask?.groupRoundId == lock) {
                    require(activeTask.id == expectedId) { "当前执行成员与组内顺序不一致" }
                    val nextExpectedId = round.memberIds.getOrNull(completedCount + 1)
                    require(g.next?.id == nextExpectedId) { "关联组下一步与组内顺序不一致" }
                } else {
                    if (activeTask != null) require(completedCount == 0) { "关联组开始后不能执行其他独立步骤" }
                    require(g.next?.id == expectedId) { "关联组下一步必须为当前应执行成员" }
                }
            }
            if (g.lockedGroupRoundId == null) require(g.groupRounds.none { r -> r.completedAt == null && r.memberIds.any { it == g.next?.id || it == state.active?.task?.id } }) { "已进入执行路径的关联轮次必须持有锁" }
        }
        state.active?.let {
            require(!state.goal(it.goalId).archived); require(it.task.startedAt == null && it.task.completedAt == null)
            allTasks += it.task
        }
        allTasks.forEach { require(it.id.isNotBlank()); validateTask(it) }
        require(allTasks.map { it.id }.distinct().size == allTasks.size) { "一个任务只能处于一个状态" }
        val owners = mutableMapOf<String, String>()
        state.goals.forEach { g ->
            val upcoming = g.pending + listOfNotNull(g.next, state.active?.takeIf { it.goalId == g.id }?.task)
            require(upcoming.none { it.seriesEnded }); require(upcoming.all { it.completedAmount == null })
            require(g.done.all { !it.isQuantified || (it.completedAmount != null && it.completedAt != null) })
            (upcoming + g.done).filter { it.isRecurring }.groupBy { it.seriesId!! }.forEach { (series, tasks) ->
                require(owners.put(series, g.id) == null) { "同一个循环不能属于多个目标" }
                require(tasks.map { it.seriesEnded }.distinct().size == 1)
                require(g.pending.count { it.seriesId == series } == if (tasks.first().seriesEnded) 0 else 1) { "进行中的循环必须保留一个待执行副本" }
                require(tasks.map { it.targetAmount?.let(Quantity::normalize) to it.unit }.distinct().size == 1)
                if (tasks.first().isQuantified) require(tasks.first().seriesEnded == (g.accumulated(tasks.first()) >= Quantity.parse(tasks.first().targetAmount!!)))
            }
        }
    }

    private fun update(state: AppState, id: String, block: (Goal) -> Goal): AppState {
        val goal = state.goal(id); require(!goal.archived) { "已归档目标不能修改" }
        return state.copy(goals = state.goals.map { if (it.id == id) block(it) else it })
    }
    fun addGoal(state: AppState, title: String, end: LocalDate, now: ZonedDateTime = ZonedDateTime.now(), id: String = UUID.randomUUID().toString()): AppState {
        require(state.visibleGoals.size < 5) { "最多五个目标，请先完成并归档一个" }; val start = now.toLocalDate(); validateDates(start, end)
        require(state.goals.none { it.id == id }); return state.copy(goals = state.goals + Goal(id, goalTitle(title), start, end, createdAt = now.toInstant().toEpochMilli()))
    }
    fun addTask(state: AppState, goalId: String, task: Task): AppState {
        validateTask(task); require(!task.isGrouped)
        if (task.isRecurring) require(state.goals.none { g -> (g.pending + g.done + listOfNotNull(g.next)).any { it.seriesId == task.seriesId } } && state.active?.task?.seriesId != task.seriesId)
        return update(state, goalId) { it.copy(pending = it.pending + task.copy(title = task.title.trim())) }.also(::validate)
    }
    fun editTask(state: AppState, goalId: String, taskId: String, title: String): AppState = update(state, goalId) { g ->
        val clean = taskTitle(title); val target = (g.pending + listOfNotNull(g.next)).find { it.id == taskId } ?: throw IllegalArgumentException("只能编辑待执行任务或下一项的文字")
        when {
            target.isGrouped -> { val p = target.groupPosition!!; val id = target.groupId!!; g.copy(
                groups = g.groups.map { if (it.id == id) it.copy(members = it.members.mapIndexed { i, m -> if (i == p) m.copy(title = clean) else m }) else it },
                pending = g.pending.map { if (it.groupId == id && it.groupPosition == p) it.copy(title = clean) else it },
                next = g.next?.let { if (it.groupId == id && it.groupPosition == p) it.copy(title = clean) else it }) }
            target.isRecurring -> g.copy(pending = g.pending.map { if (it.seriesId == target.seriesId) it.copy(title = clean) else it }, next = g.next?.let { if (it.seriesId == target.seriesId) it.copy(title = clean) else it })
            g.next?.id == taskId -> g.copy(next = g.next.copy(title = clean))
            else -> g.copy(pending = g.pending.map { if (it.id == taskId) it.copy(title = clean) else it })
        }
    }.also(::validate)
    fun deleteTask(state: AppState, goalId: String, taskId: String): AppState = update(state, goalId) { g ->
        val task = g.pending.find { it.id == taskId } ?: throw IllegalArgumentException("只能删除待执行任务")
        require(!task.isGrouped) { "关联组成员不能单独删除，请编辑或解除关联" }; require(canDeleteTask(state, g, task)) { "已开始的循环不能单独删除后续步骤" }
        g.copy(pending = g.pending.filterNot { it.id == taskId })
    }.also(::validate)
    fun canDeleteTask(state: AppState, goal: Goal, task: Task) = !task.isGrouped && (!task.isRecurring || (goal.done + listOfNotNull(goal.next, state.active?.takeIf { it.goalId == goal.id }?.task)).none { it.seriesId == task.seriesId })

    fun createGroup(state: AppState, goalId: String, orderedTaskIds: List<String>, name: String = "", mode: StepGroupMode = StepGroupMode.SINGLE,
        targetAmount: String? = null, unit: String? = null, groupId: String = UUID.randomUUID().toString(), roundId: String = UUID.randomUUID().toString()): AppState = update(state, goalId) { g ->
        require(orderedTaskIds.size >= 2 && orderedTaskIds.distinct().size == orderedTaskIds.size) { "请至少选择两个不同步骤" }
        val selected = orderedTaskIds.map { id -> g.pending.find { it.id == id } ?: throw IllegalArgumentException("只能关联待执行步骤") }
        require(selected.none { it.isGrouped }) { "一个步骤最多属于一个关联组" }
        selected.filter { it.isRecurring }.forEach { t ->
            require((g.done + listOfNotNull(g.next, state.active?.takeIf { it.goalId == g.id }?.task)).none { it.seriesId == t.seriesId } && g.pending.count { it.seriesId == t.seriesId } == 1) { "已开始的独立循环不能直接加入关联组" }
        }
        val target = if (mode == StepGroupMode.QUANTITY) Quantity.normalize(requireNotNull(targetAmount) { "请填写累计目标" }) else null
        val cleanUnit = if (mode == StepGroupMode.QUANTITY) Quantity.unit(requireNotNull(unit) { "请填写单位" }) else null
        require(mode == StepGroupMode.QUANTITY || (targetAmount == null && unit == null))
        val group = StepGroup(groupId, groupName(name, selected.first().title, selected.size), mode, selected.map { GroupMemberTemplate(title = it.title) }, target, cleanUnit)
        val members = selected.mapIndexed { i, t -> t.copy(seriesId = null, seriesEnded = false, targetAmount = null, unit = null, completedAmount = null, groupId = groupId, groupRoundId = roundId, groupPosition = i) }
        val first = g.pending.indexOfFirst { it.id in orderedTaskIds }; val pending = g.pending.filterNot { it.id in orderedTaskIds }.toMutableList().apply { addAll(first, members) }
        g.copy(pending = pending, groups = g.groups + group, groupRounds = g.groupRounds + StepGroupRound(roundId, groupId, 1, members.map { it.id }))
    }.also(::validate)

    fun unlinkGroup(state: AppState, goalId: String, groupId: String): AppState = update(state, goalId) { g ->
        val rounds = g.groupRounds.filter { it.groupId == groupId }; require(rounds.size == 1 && rounds.single().completedAt == null && g.lockedGroupRoundId != rounds.single().id) { "关联组开始后不能解除" }
        val ids = rounds.single().memberIds.toSet(); g.copy(pending = g.pending.map { if (it.id in ids) it.copy(groupId = null, groupRoundId = null, groupPosition = null) else it }, groups = g.groups.filterNot { it.id == groupId }, groupRounds = g.groupRounds.filterNot { it.groupId == groupId })
    }.also(::validate)

    fun editGroup(state: AppState, goalId: String, groupId: String, orderedTaskIds: List<String>, name: String,
        mode: StepGroupMode, targetAmount: String? = null, unit: String? = null): AppState = update(state, goalId) { g ->
        val old = g.group(groupId); val rounds = g.groupRounds.filter { it.groupId == groupId }
        require(rounds.size == 1 && rounds.single().completedAt == null && g.lockedGroupRoundId != rounds.single().id) { "关联组开始后不能修改成员结构" }
        require(orderedTaskIds.size >= 2 && orderedTaskIds.distinct().size == orderedTaskIds.size) { "关联组至少需要两个成员" }
        val oldIds = rounds.single().memberIds
        val allowed = g.pending.filter { !it.isGrouped || it.groupId == groupId }.associateBy { it.id }
        val selected = orderedTaskIds.map { allowed[it] ?: throw IllegalArgumentException("只能选择尚未进入执行路径的步骤") }
        selected.filter { it.isRecurring }.forEach { t ->
            require((g.done + listOfNotNull(g.next, state.active?.takeIf { it.goalId == g.id }?.task)).none { it.seriesId == t.seriesId } && g.pending.count { it.seriesId == t.seriesId } == 1) { "已开始的独立循环不能直接加入关联组" }
        }
        val target = if(mode == StepGroupMode.QUANTITY) Quantity.normalize(requireNotNull(targetAmount) { "请填写累计目标" }) else null
        val cleanUnit = if(mode == StepGroupMode.QUANTITY) Quantity.unit(requireNotNull(unit) { "请填写单位" }) else null
        require(mode == StepGroupMode.QUANTITY || (targetAmount == null && unit == null))
        val members = selected.mapIndexed { i,t -> t.copy(seriesId=null,seriesEnded=false,targetAmount=null,unit=null,completedAmount=null,groupId=groupId,groupRoundId=rounds.single().id,groupPosition=i) }
        val removed = g.pending.filter { it.id in oldIds && it.id !in orderedTaskIds }.map { it.copy(groupId=null,groupRoundId=null,groupPosition=null) }
        val first = g.pending.indexOfFirst { it.id in oldIds }
        val insertIndex = if (first >= 0) g.pending.take(first).count { it.id !in (oldIds + orderedTaskIds).toSet() } else 0
        val pending = g.pending.filterNot { it.id in (oldIds + orderedTaskIds).toSet() }.toMutableList().apply { addAll(insertIndex,members + removed) }
        g.copy(pending=pending,
            groups=g.groups.map { if(it.id == groupId) old.copy(name=groupName(name,members.first().title,members.size),mode=mode,members=members.map { GroupMemberTemplate(title=it.title) },targetAmount=target,unit=cleanUnit) else it },
            groupRounds=g.groupRounds.map { if(it.id == rounds.single().id) it.copy(memberIds=members.map { t -> t.id }) else it })
    }.also(::validate)

    private fun blocks(g: Goal): List<List<String>> { val pendingIds = g.pending.map { it.id }.toSet(); val seen = mutableSetOf<String>(); return buildList { g.pending.forEach { t -> val key = t.groupRoundId ?: t.id; if (seen.add(key)) add(if (t.isGrouped) g.round(t.groupRoundId!!).memberIds.filter { it in pendingIds } else listOf(t.id)) } } }
    fun moveTask(state: AppState, goalId: String, taskId: String, targetIndex: Int): AppState = update(state, goalId) { g ->
        val task = g.pending.find { it.id == taskId } ?: throw IllegalArgumentException("只能调整待执行任务的顺序")
        val target = g.pending.getOrNull(targetIndex) ?: throw IllegalArgumentException("目标位置无效")
        if (task.isGrouped) require(task.groupRoundId != g.lockedGroupRoundId) { "已锁定关联组不能移动" }
        if (target.isGrouped) {
            val roundTasks = g.pending.filter { it.groupRoundId == target.groupRoundId }
            val firstIdx = g.pending.indexOfFirst { it.id == roundTasks.first().id }
            val lastIdx = g.pending.indexOfFirst { it.id == roundTasks.last().id }
            require(targetIndex !in (firstIdx + 1)..lastIdx) { "不能把独立步骤插入关联组内部" }
        }
        val b = blocks(g).toMutableList()
        val from = b.indexOfFirst { taskId in it }
        val to = b.indexOfFirst { target.id in it }
        require(from != to || task.id == target.id) { "不能在关联组内部调整顺序" }
        b.add(to, b.removeAt(from))
        val byId = g.pending.associateBy { it.id }
        g.copy(pending = b.flatten().map { byId.getValue(it) })
    }.also(::validate)
    fun moveGroupRound(state: AppState, goalId: String, roundId: String, targetBlockIndex: Int): AppState = update(state, goalId) { g ->
        require(roundId != g.lockedGroupRoundId) { "已锁定关联组不能移动" }; val b = blocks(g).toMutableList(); val from = b.indexOfFirst { ids -> ids.any { id -> g.pending.find { it.id == id }?.groupRoundId == roundId } }; require(from >= 0 && targetBlockIndex in b.indices); b.add(targetBlockIndex, b.removeAt(from)); val byId = g.pending.associateBy { it.id }; g.copy(pending = b.flatten().map { byId.getValue(it) })
    }.also(::validate)

    private fun appendFutureRound(g: Goal, groupId: String): Goal { val group = g.group(groupId); if (group.mode == StepGroupMode.SINGLE || group.ended) return g; val rounds = g.groupRounds.filter { it.groupId == groupId }; if (rounds.any { it.completedAt == null && it.id != g.lockedGroupRoundId }) return g; val rid = UUID.randomUUID().toString(); val members = group.members.mapIndexed { i, m -> Task(title = m.title, groupId = group.id, groupRoundId = rid, groupPosition = i) }; return g.copy(pending = g.pending + members, groupRounds = g.groupRounds + StepGroupRound(rid, group.id, (rounds.maxOfOrNull { it.number } ?: 0) + 1, members.map { it.id })) }
    private fun promote(g: Goal): Goal { val next = g.pending.firstOrNull() ?: return g.copy(next = null); var r = g.copy(next = next, pending = g.pending.drop(1)); if (next.isGrouped) { require(r.lockedGroupRoundId == null) { "请先完成本目标的当前关联组" }; require(next.groupPosition == 0) { "关联组必须从第一步开始" }; r = appendFutureRound(r.copy(lockedGroupRoundId = next.groupRoundId), next.groupId!!) } else if (next.isRecurring) r = r.copy(pending = r.pending + next.copy(id = UUID.randomUUID().toString())); return r }
    fun lockNext(state: AppState, goalId: String): AppState = update(state, goalId) { g -> require(g.next == null && g.pending.isNotEmpty()); require(g.lockedGroupRoundId == null) { "请先完成本目标的当前关联组" }; promote(g) }.also(::validate)
    fun start(state: AppState, goalId: String, taskId: String, now: Long): AppState { require(state.active == null) { "请先完成当前正在执行的任务" }; val g = state.goal(goalId); val task = g.next ?: throw IllegalArgumentException("请先确认下一项"); require(task.id == taskId) { "下一项已发生变化" }; val advanced = if (task.isGrouped) { require(g.lockedGroupRoundId == task.groupRoundId); val round = g.round(task.groupRoundId!!); val completedCount = round.memberIds.count { id -> g.done.any { it.id == id } }; require(task.groupPosition == completedCount) { "只能启动当前应执行的关联步骤" }; val successorId = round.memberIds.getOrNull(task.groupPosition!! + 1); if (successorId == null) g.copy(next = null) else { val successor = g.pending.find { it.id == successorId } ?: throw IllegalArgumentException("关联组后继步骤缺失"); g.copy(next = successor, pending = g.pending.filterNot { it.id == successorId }) } } else promote(g); return update(state, goalId) { advanced }.copy(active = ActiveTask(goalId, task, now)).also(::validate) }

    private fun removeFutureRounds(g: Goal, groupId: String): Goal { val future = g.groupRounds.filter { it.groupId == groupId && it.completedAt == null && it.id != g.lockedGroupRoundId }; val ids = future.flatMap { it.memberIds }.toSet(); require(g.next?.id !in ids); return g.copy(pending = g.pending.filterNot { it.id in ids }, groupRounds = g.groupRounds.filterNot { it in future }) }
    private fun completeGroup(state: AppState, current: ActiveTask, now: Long, amount: String?, endManual: Boolean): AppState { val goal = state.goal(current.goalId); val task = current.task; val group = goal.group(task.groupId!!); val round = goal.round(task.groupRoundId!!); require(goal.lockedGroupRoundId == round.id); val last = task.groupPosition == group.members.lastIndex; require(!endManual || (last && group.mode == StepGroupMode.MANUAL)) { "只能在最后一步结束整组循环" }; val quantity = if (last && group.mode == StepGroupMode.QUANTITY) Quantity.normalize(requireNotNull(amount) { "请填写本轮完成数值" }) else { require(amount == null); null }; var changed = goal.copy(done = goal.done + task.copy(startedAt = current.startedAt, completedAt = now)); if (!last) return update(state, goal.id) { changed }.copy(active = null).also(::validate); changed = changed.copy(groupRounds = changed.groupRounds.map { if (it.id == round.id) it.copy(completedAt = now, completedAmount = quantity) else it }, lockedGroupRoundId = null); val shouldEnd = group.mode == StepGroupMode.SINGLE || endManual || (group.mode == StepGroupMode.QUANTITY && changed.groupAccumulated(group.id) >= Quantity.parse(group.targetAmount!!)); if (shouldEnd) { changed = changed.copy(groups = changed.groups.map { if (it.id == group.id) it.copy(ended = true) else it }); changed = removeFutureRounds(changed, group.id) }; if (changed.pending.isNotEmpty()) changed = promote(changed); return update(state, goal.id) { changed }.copy(active = null).also(::validate) }
    fun complete(state: AppState, taskId: String, now: Long = System.currentTimeMillis(), amount: String? = null): AppState { val current = state.active ?: throw IllegalArgumentException("当前没有正在执行的任务"); require(current.task.id == taskId); if (current.task.isGrouped) return completeGroup(state, current, now, amount, false); val quantity = if (current.task.isQuantified) Quantity.normalize(requireNotNull(amount)) else { require(amount == null); null }; val completed = current.task.copy(startedAt = current.startedAt, completedAt = now, completedAmount = quantity); val finished = update(state, current.goalId) { it.copy(done = it.done + completed) }.copy(active = null); return if (completed.isQuantified && finished.goal(current.goalId).accumulated(completed) >= Quantity.parse(completed.targetAmount!!)) endSeries(finished, current.goalId, completed.seriesId!!).also(::validate) else finished.also(::validate) }
    fun completeAndEndRecurrence(state: AppState, taskId: String, now: Long = System.currentTimeMillis()): AppState { val current = state.active ?: throw IllegalArgumentException("当前没有正在执行的任务"); require(current.task.id == taskId); if (current.task.isGrouped) return completeGroup(state, current, now, null, true); require(current.task.isRecurring && !current.task.isQuantified); return endSeries(complete(state, taskId, now), current.goalId, current.task.seriesId!!).also(::validate) }
    fun completeAndEndGroup(state: AppState, taskId: String, now: Long = System.currentTimeMillis()) = completeAndEndRecurrence(state, taskId, now)
    private fun endSeries(state: AppState, goalId: String, series: String) = update(state, goalId) { g -> g.copy(pending = g.pending.filterNot { it.seriesId == series }, next = g.next?.takeUnless { it.seriesId == series }, done = g.done.map { if (it.seriesId == series) it.copy(seriesEnded = true) else it }) }
    fun groupFor(task: Task, goal: Goal) = task.groupId?.let { id -> goal.groups.find { it.id == id } }
    fun roundFor(task: Task, goal: Goal) = task.groupRoundId?.let { id -> goal.groupRounds.find { it.id == id } }
    fun isGroupFinal(task: Task, goal: Goal) = task.isGrouped && task.groupPosition == groupFor(task, goal)!!.members.lastIndex
    fun requiresGroupAmount(task: Task, goal: Goal) = isGroupFinal(task, goal) && groupFor(task, goal)?.mode == StepGroupMode.QUANTITY
    fun canEndGroup(task: Task, goal: Goal) = isGroupFinal(task, goal) && groupFor(task, goal)?.mode == StepGroupMode.MANUAL
    fun canArchive(state: AppState, g: Goal) = !g.archived && g.done.isNotEmpty() && g.pending.isEmpty() && g.next == null && state.active?.goalId != g.id && g.lockedGroupRoundId == null && g.groups.all { it.ended || it.mode == StepGroupMode.SINGLE }
    fun archive(state: AppState, goalId: String) = update(state, goalId) { require(canArchive(state, it)); it.copy(archived = true) }.also(::validate)
    fun deleteEmptyGoal(state: AppState, goalId: String): AppState { val g = state.goal(goalId); require(!g.archived && g.next == null && g.pending.isEmpty() && g.done.isEmpty() && state.active?.goalId != goalId); return state.copy(goals = state.goals.filterNot { it.id == goalId }).also(::validate) }
}
