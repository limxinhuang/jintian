package com.jintian.app.data

import com.jintian.app.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate

data class CsvSnapshot(val state: AppState, val exportedAt: Instant) {
    val stepCount: Int get() = CsvBackup.stepCount(state)
}

/** Versioned, RFC 4180-style CSV. Goal rows preserve empty goals; metadata detects truncation. */
object CsvBackup {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private val legacyHeader = listOf("格式版本", "记录类型", "目标ID", "目标顺序", "目标名称", "开始日期", "截止日期", "已归档", "目标创建时间", "步骤ID", "步骤顺序", "步骤内容", "步骤状态", "步骤开始时间", "步骤完成时间", "导出时间", "目标总数", "步骤总数")
    private val versionTwoHeader = legacyHeader + listOf("步骤类型", "循环ID", "循环已结束")
    private val header = versionTwoHeader + listOf("累计目标数值", "累计单位", "本次完成数值")
    fun stepCount(state: AppState) = state.goals.sumOf { it.pending.size + it.done.size + if(it.next != null) 1 else 0 } + if(state.active != null) 1 else 0
    private fun time(value: Long?) = value?.let { Instant.ofEpochMilli(it).toString() } ?: ""
    private fun row(vararg cells: Pair<Int,String>): List<String> = MutableList(header.size) { "" }.apply {
        this[0] = "3"; cells.forEach { (index,value) -> this[index] = value }
    }
    // Keep titles starting with spreadsheet operators as literal text. Escape the
    // escape prefix too, so import can recover titles exactly, including apostrophes.
    private fun guarded(value: String): Boolean = value.startsWith("'") || value.firstOrNull() in listOf('\t','\r','\n') || value.trimStart().firstOrNull() in listOf('=','+','-','@')
    private fun protect(value: String) = if(guarded(value)) "'$value" else value
    private fun unprotect(value: String) = if(value.startsWith("'") && guarded(value.drop(1))) value.drop(1) else value
    private fun quote(value: String) = "\"" + protect(value).replace("\"", "\"\"") + "\""

    fun encode(state: AppState, exportedAt: Instant = Instant.now()): ByteArray {
        GoalRules.validate(state)
        val rows = mutableListOf(header, row(1 to "备份",15 to exportedAt.toString(),16 to state.goals.size.toString(),17 to stepCount(state).toString()))
        state.goals.forEachIndexed { goalIndex,g ->
            rows += row(1 to "目标",2 to g.id,3 to (goalIndex+1).toString(),4 to g.title,5 to g.start.toString(),6 to g.end.toString(),7 to if(g.archived) "是" else "否",8 to time(g.createdAt))
            fun add(t: Task, status: String, order: Int, startedAt: Long? = t.startedAt) {
                rows += row(1 to "步骤",2 to g.id,9 to t.id,10 to order.toString(),11 to t.title,12 to status,13 to time(startedAt),14 to time(t.completedAt),
                    18 to if(t.isRecurring) "循环型" else "单次型",19 to (t.seriesId ?: ""),20 to if(t.seriesEnded) "是" else "否",
                    21 to (t.targetAmount ?: ""),22 to (t.unit ?: ""),23 to (t.completedAmount ?: ""))
            }
            g.pending.forEachIndexed { i,t -> add(t,"待执行",i+1) }
            g.next?.let { add(it,"下一个",1) }
            state.active?.takeIf { it.goalId == g.id }?.let { add(it.task,"正在执行",1,it.startedAt) }
            g.done.forEachIndexed { i,t -> add(t,"已完成",i+1) }
        }
        require(rows.size <= MAX_ROWS) { "备份记录过多，最多支持 ${MAX_ROWS-2} 条目标和步骤记录" }
        val text = "\uFEFF" + rows.joinToString("\r\n") { cells -> cells.joinToString(",",transform=::quote) } + "\r\n"
        return text.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "备份超过 10 MB，暂时无法导出" } }
    }

    fun decode(bytes: ByteArray): CsvSnapshot {
        require(bytes.size <= MAX_BYTES) { "文件超过 10 MB，请选择「今天」导出的 CSV 备份" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("文件不是有效的 UTF-8 CSV，请选择应用导出的原始备份") }
        val rows = parse(text)
        require(rows.size >= 2 && rows[0] in listOf(header, versionTwoHeader, legacyHeader)) { "CSV 表头或格式不符，请选择「今天」导出的备份文件" }
        val version = when(rows[0]) { legacyHeader -> "1"; versionTwoHeader -> "2"; else -> "3" }
        val legacy = version == "1"
        val data = rows.drop(1).mapIndexed { i, cells ->
            require(cells.size == rows[0].size) { "第 ${i+2} 条记录的列数不正确" }
            require(cells[0] == version) { "不支持此 CSV 备份版本" }
            cells.map(::unprotect) + List(header.size - cells.size) { "" }
        }
        fun requireUnusedEmpty(cells: List<String>, used: Set<Int>) {
            require(cells.indices.all { it in used || cells[it].isEmpty() }) { "${cells[1]}记录中存在不属于该类型的数据" }
        }
        fun number(value: String, label: String, allowZero: Boolean = false): Int {
            val n = value.toIntOrNull()
            require(n != null && n >= if(allowZero) 0 else 1) { "$label 不是有效的整数" }
            return n
        }
        fun instant(value: String, label: String): Instant = try { Instant.parse(value) }
            catch (_: Exception) { throw IllegalArgumentException("$label 格式无效") }
        fun millis(value: String, label: String): Long? = if(value.isEmpty()) null else try { instant(value,label).toEpochMilli() }
            catch (_: Exception) { throw IllegalArgumentException("$label 超出有效范围") }
        fun date(value: String): LocalDate = try { LocalDate.parse(value) } catch (_: Exception) { throw IllegalArgumentException("目标日期无效") }
        require(data.all { it[1] in setOf("备份","目标","步骤") }) { "CSV 包含未知记录类型" }
        val metadata = data.filter { it[1] == "备份" }
        require(metadata.size == 1) { "备份信息缺失或重复" }
        val meta = metadata.single()
        requireUnusedEmpty(meta,setOf(0,1,15,16,17))
        val exported = instant(meta[15],"导出时间")
        val goalRows = data.filter { it[1] == "目标" }
        val taskRows = data.filter { it[1] == "步骤" }
        require(number(meta[16],"目标总数",true) == goalRows.size && number(meta[17],"步骤总数",true) == taskRows.size) { "备份记录数量不完整，文件可能被截断或修改" }
        fun ordered(rows: List<List<String>>, column: Int, label: String): List<List<String>> {
            val numbered = rows.map { number(it[column],"$label 顺序") to it }.sortedBy { it.first }
            require(numbered.map { it.first } == (1..rows.size).toList()) { "$label 的顺序重复或不连续" }
            return numbered.map { it.second }
        }
        val baseGoals = ordered(goalRows,3,"目标").map { r ->
            requireUnusedEmpty(r,(0..8).toSet())
            require(r[7] == "是" || r[7] == "否") { "已归档列只能为「是」或「否」" }
            Goal(r[2],r[4],date(r[5]),date(r[6]),archived=r[7] == "是",createdAt=millis(r[8],"目标创建时间"))
        }
        require(baseGoals.map { it.id }.distinct().size == baseGoals.size) { "备份存在重复目标 ID" }
        val goalIds = baseGoals.map { it.id }.toSet()
        taskRows.forEach { r ->
            requireUnusedEmpty(r,setOf(0,1,2,9,10,11,12,13,14,18,19,20,21,22,23))
            require(r[2] in goalIds) { "步骤引用了不存在的目标" }
            require(r[12] in setOf("待执行","下一个","正在执行","已完成")) { "步骤状态无效" }
            if(!legacy) {
                require(r[18] in setOf("单次型", "循环型") && r[20] in setOf("是", "否")) { "步骤类型或循环结束状态无效" }
                require((r[18] == "循环型") == r[19].isNotBlank()) { "步骤类型与循环编号不一致" }
                require(r[18] == "循环型" || (r[19].isEmpty() && r[20] == "否")) { "单次型步骤不能有循环信息" }
            }
        }
        require(taskRows.map { it[9] }.distinct().size == taskRows.size) { "备份存在重复步骤 ID" }
        require(taskRows.count { it[12] == "正在执行" } <= 1) { "备份中有多个正在执行的步骤，无法恢复" }
        var active: ActiveTask? = null
        val groupedTasks = taskRows.groupBy { it[2] }
        val goals = baseGoals.map { g ->
            val grouped = groupedTasks[g.id].orEmpty().groupBy { it[12] }
            fun tasks(status: String): List<Task> = ordered(grouped[status].orEmpty(),10,status).map { r ->
                val started = millis(r[13],"步骤开始时间"); val completed = millis(r[14],"步骤完成时间")
                when(status) {
                    "待执行", "下一个" -> require(started == null && completed == null) { "未执行的步骤不能有开始或完成时间" }
                    "正在执行" -> require(started != null && completed == null) { "正在执行步骤的时间记录不完整" }
                    "已完成" -> require((started == null) == (completed == null)) { "已完成步骤的时间记录不完整" }
                }
                val task = Task(r[9],r[11],seriesId=r[19].takeIf { it.isNotEmpty() },seriesEnded=r[20] == "是",
                    targetAmount=r[21].takeIf { it.isNotEmpty() },unit=r[22].takeIf { it.isNotEmpty() },completedAmount=r[23].takeIf { it.isNotEmpty() })
                if(status == "正在执行") task.also { active = ActiveTask(g.id,it,started!!) }
                else task.copy(startedAt=started,completedAt=completed)
            }
            val next = tasks("下一个")
            require(next.size <= 1) { "一个目标只能有一个下一项" }
            tasks("正在执行")
            g.copy(pending=tasks("待执行"),next=next.singleOrNull(),done=tasks("已完成"))
        }
        return CsvSnapshot(AppState(goals,active).also(GoalRules::validate),exported)
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closed = false
        var touched = false
        var i = 0
        fun finishCell() {
            require(cells.size < header.size) { "CSV 列数过多" }
            cells += cell.toString(); cell.setLength(0); closed = false; touched = false
        }
        fun finishRow() {
            finishCell()
            require(rows.size < MAX_ROWS) { "CSV 记录过多" }
            rows += cells; cells = mutableListOf()
        }
        while(i < text.length) {
            val c = text[i]
            if(quoted) {
                if(c == '"') {
                    if(i+1 < text.length && text[i+1] == '"') { cell.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else cell.append(c)
            } else when(c) {
                '"' -> { require(!touched && cell.isEmpty() && !closed) { "CSV 引号位置无效" }; quoted = true; touched = true }
                ',' -> finishCell()
                '\r', '\n' -> { finishRow(); if(c == '\r' && i+1 < text.length && text[i+1] == '\n') i++ }
                else -> { require(!closed) { "CSV 引号后存在多余内容" }; cell.append(c); touched = true }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合，文件可能不完整" }
        if(touched || closed || cell.isNotEmpty() || cells.isNotEmpty()) finishRow()
        return rows
    }
}
