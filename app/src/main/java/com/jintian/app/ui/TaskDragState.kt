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
    private fun blocks(value: List<Task>): List<List<Task>> {
        val seen = mutableSetOf<String>()
        return buildList {
            value.forEach { task ->
                val round = task.groupRoundId
                if(round == null) add(listOf(task))
                else if(seen.add(round)) add(value.filter { it.groupRoundId == round })
            }
        }
    }
    private fun reorder() {
        val id = draggedId ?: return
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key.toString().startsWith("task-") }
        val keys = visible.map { it.key.toString().removePrefix("task-") }
        // Wait for the previous placement before evaluating the next crossing.
        // Otherwise a fast pointer/scroll event can swap against stale coordinates.
        if(blocks(tasks).map { it.first().id }.filter { it in keys } != keys) return
        val target = visible.minByOrNull { abs(it.offset + it.size/2f - (top + height/2f)) } ?: return
        val grouped = blocks(tasks).toMutableList()
        val from = grouped.indexOfFirst { block -> block.any { it.id == id } }
        val targetId = target.key.toString().removePrefix("task-")
        val to = grouped.indexOfFirst { block -> block.any { it.id == targetId } }
        if(from >= 0 && to >= 0 && from != to) {
            grouped.add(to,grouped.removeAt(from))
            tasks = grouped.flatten()
        }
    }
    fun offset(id: String): Float {
        if(draggedId != id) return 0f
        val item = list.layoutInfo.visibleItemsInfo.find { it.key == "task-$id" } ?: return 0f
        return top - item.offset
    }
    fun cancel() { draggedId = null; tasks = original }
    fun revert() { draggedId = null; tasks = original }
    fun finish() {
        val id = draggedId ?: return
        val originalBlocks = blocks(original)
        val currentBlocks = blocks(tasks)
        val source = originalBlocks.indexOfFirst { block -> block.any { it.id == id } }
        val destination = currentBlocks.indexOfFirst { block -> block.any { it.id == id } }
        draggedId = null
        if(destination >= 0 && source >= 0 && destination != source) {
            val anchor = if(destination < source) currentBlocks[destination + 1].first().id else currentBlocks[destination - 1].first().id
            val targetIndex = original.indexOfFirst { it.id == anchor }
            if(targetIndex >= 0) {
                try {
                    onDrop(id, targetIndex)
                } catch (_: Throwable) {
                    tasks = original
                }
            } else {
                tasks = original
            }
        }
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
fun rememberTaskDragState(list: LazyListState, tasks: List<Task>, onDrop: (String, Int, () -> Unit) -> Unit): TaskDragState {
    val drop by rememberUpdatedState(onDrop)
    val state = remember(list) {
        var instance: TaskDragState? = null
        val created = TaskDragState(list, tasks) { id, index ->
            drop(id, index) { instance?.revert() }
        }
        instance = created
        created
    }
    LaunchedEffect(tasks) { state.sync(tasks) }
    return state
}

@Composable
fun rememberTaskDragState(list: LazyListState, tasks: List<Task>, onDrop: (String, Int) -> Unit): TaskDragState =
    rememberTaskDragState(list, tasks) { id, index, _ -> onDrop(id, index) }
