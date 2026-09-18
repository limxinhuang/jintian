package com.jintian.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import com.jintian.app.domain.Task
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Only pending cards enter this local preview. Persist the final order on drop. */
@Stable
class TaskDragState(private val list: LazyListState, initial: List<Task>, private val onDrop: (String, Int) -> Unit) {
    var tasks by mutableStateOf(initial)
        private set
    private var original = initial
    var draggedId by mutableStateOf<String?>(null)
        private set
    private var top by mutableFloatStateOf(0f)
    private var height by mutableFloatStateOf(0f)
    private var pointerY by mutableFloatStateOf(0f)
    fun sync(value: List<Task>) { original = value; if(draggedId == null) tasks = value }
    fun start(point: Offset) {
        val y = point.y - list.layoutInfo.beforeContentPadding
        val item = list.layoutInfo.visibleItemsInfo.find { it.key.toString().startsWith("task-") && y in it.offset.toFloat()..(it.offset+it.size).toFloat() } ?: return
        draggedId = item.key.toString().removePrefix("task-")
        top = item.offset.toFloat(); height = item.size.toFloat(); pointerY = y
    }
    fun drag(amount: Offset) { if(draggedId != null) { top += amount.y; pointerY += amount.y; reorder() } }
    private fun reorder() {
        val id = draggedId ?: return
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key.toString().startsWith("task-") }
        val keys = visible.map { it.key.toString().removePrefix("task-") }
        // Wait for the previous placement before evaluating the next crossing.
        // Otherwise a fast pointer/scroll event can swap against stale coordinates.
        if(tasks.map { it.id }.filter { it in keys } != keys) return
        val target = visible.minByOrNull { abs(it.offset + it.size/2f - (top + height/2f)) } ?: return
        val from = tasks.indexOfFirst { it.id == id }
        val to = tasks.indexOfFirst { "task-${it.id}" == target.key }
        if(from >= 0 && to >= 0 && from != to) tasks = tasks.toMutableList().apply { add(to,removeAt(from)) }
    }
    fun offset(id: String): Float {
        if(draggedId != id) return 0f
        val item = list.layoutInfo.visibleItemsInfo.find { it.key == "task-$id" } ?: return 0f
        return top - item.offset
    }
    fun cancel() { draggedId = null; tasks = original }
    fun finish() {
        val id = draggedId ?: return
        val destination = tasks.indexOfFirst { it.id == id }
        val source = original.indexOfFirst { it.id == id }
        draggedId = null
        if(destination >= 0 && destination != source) onDrop(id,destination)
    }
    suspend fun autoScroll(edge: Float) {
        while(draggedId != null) {
            val layout = list.layoutInfo
            val distance = when {
                pointerY < layout.viewportStartOffset + edge -> -(layout.viewportStartOffset + edge - pointerY).coerceAtMost(edge)
                pointerY > layout.viewportEndOffset - edge -> (pointerY - layout.viewportEndOffset + edge).coerceAtMost(edge)
                else -> 0f
            }
            if(distance != 0f) { list.scrollBy(distance * .2f); reorder() }
            delay(16)
        }
    }
}

@Composable
fun rememberTaskDragState(list: LazyListState, tasks: List<Task>, onDrop: (String,Int) -> Unit): TaskDragState {
    val drop by rememberUpdatedState(onDrop)
    val state = remember(list) { TaskDragState(list,tasks) { id,index -> drop(id,index) } }
    LaunchedEffect(tasks) { state.sync(tasks) }
    return state
}
