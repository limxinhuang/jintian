package com.jintian.app.data

import com.jintian.app.domain.ActiveTask
import com.jintian.app.domain.AppState
import com.jintian.app.domain.Goal
import com.jintian.app.domain.GoalRules
import com.jintian.app.domain.GroupMemberTemplate
import com.jintian.app.domain.StepGroup
import com.jintian.app.domain.StepGroupMode
import com.jintian.app.domain.StepGroupRound
import com.jintian.app.domain.Task
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object StateCodec {
    private fun JSONObject.nullableLong(key: String): Long? = if(isNull(key)) null else getLong(key)
    private fun JSONObject.nullableString(key: String): String? = if(isNull(key)) null else getString(key)
    private fun task(value: Task) = JSONObject().put("id", value.id).put("title", value.title)
        .put("startedAt", value.startedAt ?: JSONObject.NULL).put("completedAt", value.completedAt ?: JSONObject.NULL)
        .put("seriesId", value.seriesId ?: JSONObject.NULL).put("seriesEnded", value.seriesEnded)
        .put("targetAmount", value.targetAmount ?: JSONObject.NULL).put("unit", value.unit ?: JSONObject.NULL)
        .put("completedAmount", value.completedAmount ?: JSONObject.NULL)
        .put("groupId", value.groupId ?: JSONObject.NULL).put("groupRoundId", value.groupRoundId ?: JSONObject.NULL)
        .put("groupPosition", value.groupPosition ?: JSONObject.NULL)
    private fun task(value: JSONObject) = Task(value.getString("id"), value.getString("title"), value.nullableLong("startedAt"), value.nullableLong("completedAt"),
        value.nullableString("seriesId"), value.optBoolean("seriesEnded", false),
        value.nullableString("targetAmount"), value.nullableString("unit"), value.nullableString("completedAmount"),
        value.nullableString("groupId"), value.nullableString("groupRoundId"), if(value.isNull("groupPosition")) null else value.getInt("groupPosition"))
    private fun tasks(values: List<Task>) = JSONArray().also { array -> values.forEach { array.put(task(it)) } }
    private fun tasks(array: JSONArray) = (0 until array.length()).map { task(array.getJSONObject(it)) }
    private fun group(value: StepGroup) = JSONObject().put("id", value.id).put("name", value.name).put("mode", value.mode.name)
        .put("targetAmount", value.targetAmount ?: JSONObject.NULL).put("unit", value.unit ?: JSONObject.NULL).put("ended", value.ended)
        .put("members", JSONArray().also { a -> value.members.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title)) } })
    private fun group(value: JSONObject) = StepGroup(value.getString("id"), value.getString("name"), StepGroupMode.valueOf(value.getString("mode")),
        value.getJSONArray("members").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { GroupMemberTemplate(it.getString("id"), it.getString("title")) } } },
        value.nullableString("targetAmount"), value.nullableString("unit"), value.optBoolean("ended", false))
    private fun round(value: StepGroupRound) = JSONObject().put("id", value.id).put("groupId", value.groupId).put("number", value.number)
        .put("memberIds", JSONArray(value.memberIds)).put("completedAt", value.completedAt ?: JSONObject.NULL)
        .put("completedAmount", value.completedAmount ?: JSONObject.NULL)
    private fun round(value: JSONObject) = StepGroupRound(value.getString("id"), value.getString("groupId"), value.getInt("number"),
        value.getJSONArray("memberIds").let { a -> (0 until a.length()).map { a.getString(it) } }, value.nullableLong("completedAt"), value.nullableString("completedAmount"))
    fun encode(state: AppState): String {
        GoalRules.validate(state)
        val goals = JSONArray()
        state.goals.forEach { g ->
            goals.put(JSONObject().put("id", g.id).put("title", g.title)
                .put("start", g.start.toString()).put("end", g.end.toString())
                .put("pending", tasks(g.pending)).put("done", tasks(g.done))
                .put("next", g.next?.let(::task) ?: JSONObject.NULL).put("archived", g.archived)
                .put("createdAt", g.createdAt ?: JSONObject.NULL)
                .put("groups", JSONArray().also { a -> g.groups.forEach { a.put(group(it)) } })
                .put("groupRounds", JSONArray().also { a -> g.groupRounds.forEach { a.put(round(it)) } })
                .put("lockedGroupRoundId", g.lockedGroupRoundId ?: JSONObject.NULL))
        }
        val active = state.active?.let { JSONObject().put("goalId", it.goalId).put("task", task(it.task)).put("startedAt", it.startedAt) }
        return JSONObject().put("version", 5).put("goals", goals).put("active", active ?: JSONObject.NULL).toString()
    }
    fun decode(text: String): AppState {
        val root = JSONObject(text)
        require(root.getInt("version") in 1..5) { "数据版本不受支持，请保留数据并更新应用" }
        require(root.has("goals") && root.has("active")) { "数据内容不完整" }
        val array = root.getJSONArray("goals")
        val goals = (0 until array.length()).map { i ->
            val g = array.getJSONObject(i)
            Goal(g.getString("id"), g.getString("title"), LocalDate.parse(g.getString("start")), LocalDate.parse(g.getString("end")),
                tasks(g.getJSONArray("pending")), if(g.isNull("next")) null else task(g.getJSONObject("next")),
                tasks(g.getJSONArray("done")), g.getBoolean("archived"), g.nullableLong("createdAt"),
                if(g.has("groups")) g.getJSONArray("groups").let { a -> (0 until a.length()).map { group(a.getJSONObject(it)) } } else emptyList(),
                if(g.has("groupRounds")) g.getJSONArray("groupRounds").let { a -> (0 until a.length()).map { round(a.getJSONObject(it)) } } else emptyList(),
                g.nullableString("lockedGroupRoundId"))
        }
        val active = if(root.isNull("active")) null else root.getJSONObject("active").let {
            ActiveTask(it.getString("goalId"), task(it.getJSONObject("task")), it.getLong("startedAt"))
        }
        return AppState(goals, active).also(GoalRules::validate)
    }
}
