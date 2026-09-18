package com.jintian.app.data

import android.util.AtomicFile
import com.jintian.app.domain.AppState
import com.jintian.app.domain.GoalRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

interface SnapshotStore {
    fun read(): AppState
    fun write(state: AppState)
}

class AtomicSnapshotStore(file: File) : SnapshotStore {
    private val atomic = AtomicFile(file)
    override fun read(): AppState {
        val bytes = try { atomic.readFully() } catch (e: FileNotFoundException) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return AppState()
            throw e
        }
        return StateCodec.decode(bytes.toString(Charsets.UTF_8))
    }
    override fun write(state: AppState) {
        val bytes = StateCodec.encode(state).toByteArray(Charsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}

/** Serialize read-modify-write across all screens; publish only persisted snapshots. */
class GoalRepository(private val store: SnapshotStore) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AppState?>(null)
    val state = _state.asStateFlow()
    suspend fun load() = mutex.withLock {
        if (_state.value == null) _state.value = withContext(Dispatchers.IO) { store.read().also(GoalRules::validate) }
    }
    suspend fun update(change: (AppState) -> AppState) = mutex.withLock {
        val current = checkNotNull(_state.value) { "数据尚未读取完成" }
        val updated = change(current).also(GoalRules::validate)
        withContext(Dispatchers.IO + NonCancellable) {
            store.write(updated)
            _state.value = updated
        }
    }
    suspend fun restore(expected: AppState, replacement: AppState) = update { current ->
        require(current == expected) { "预览后当前数据已变化，请重新选择备份文件再确认" }
        replacement
    }
}
