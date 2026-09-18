package com.jintian.app.data

import com.jintian.app.domain.ActiveTask
import com.jintian.app.domain.AppState
import com.jintian.app.domain.Goal
import com.jintian.app.domain.GoalRules
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
    private fun task(value: JSONObject) = Task(value.getString("id"), value.getString("title"), value.nullableLong("startedAt"), value.nullableLong("completedAt"),
        value.nullableString("seriesId"), value.optBoolean("seriesEnded", false),
        value.nullableString("targetAmount"), value.nullableString("unit"), value.nullableString("completedAmount"))
    private fun tasks(values: List<Task>) = JSONArray().also { array -> values.forEach { array.put(task(it)) } }
    private fun tasks(array: JSONArray) = (0 until array.length()).map { task(array.getJSONObject(it)) }
    fun encode(state: AppState): String {
        GoalRules.validate(state)
        val goals = JSONArray()
        state.goals.forEach { g ->
            goals.put(JSONObject().put("id", g.id).put("title", g.title)
                .put("start", g.start.toString()).put("end", g.end.toString())
                .put("pending", tasks(g.pending)).put("done", tasks(g.done))
                .put("next", g.next?.let(::task) ?: JSONObject.NULL).put("archived", g.archived)
                .put("createdAt", g.createdAt ?: JSONObject.NULL))
        }
        val active = state.active?.let { JSONObject().put("goalId", it.goalId).put("task", task(it.task)).put("startedAt", it.startedAt) }
        return JSONObject().put("version", 4).put("goals", goals).put("active", active ?: JSONObject.NULL).toString()
    }
    fun decode(text: String): AppState {
        val root = JSONObject(text)
        require(root.getInt("version") in 1..4) { "数据版本不受支持，请保留数据并更新应用" }
        val array = root.getJSONArray("goals")
        val goals = (0 until array.length()).map { i ->
            val g = array.getJSONObject(i)
            Goal(g.getString("id"), g.getString("title"), LocalDate.parse(g.getString("start")), LocalDate.parse(g.getString("end")),
                tasks(g.getJSONArray("pending")), if(g.isNull("next")) null else task(g.getJSONObject("next")),
                tasks(g.getJSONArray("done")), g.getBoolean("archived"), g.nullableLong("createdAt"))
        }
        val active = if(root.isNull("active")) null else root.getJSONObject("active").let {
            ActiveTask(it.getString("goalId"), task(it.getJSONObject("task")), it.getLong("startedAt"))
        }
        return AppState(goals, active).also(GoalRules::validate)
    }
}
