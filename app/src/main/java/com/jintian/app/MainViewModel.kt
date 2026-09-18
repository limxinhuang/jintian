package com.jintian.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jintian.app.domain.AppState
import com.jintian.app.data.CsvBackup
import com.jintian.app.data.CsvDocuments
import com.jintian.app.data.CsvSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportPreview(val backup: CsvSnapshot, val current: AppState)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as JintianApplication).repository
    val state = repository.state
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val documents = CsvDocuments(application.contentResolver)
    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview = _importPreview.asStateFlow()
    init { load() }
    fun load() {
        viewModelScope.launch {
            _loadError.value = null
            try { repository.load() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _loadError.value = "暂时无法读取本机数据。原有文件已保留，请重试。"
            }
        }
    }
    fun edit(change: (AppState) -> AppState, onSuccess: () -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repository.update(change)
                onSuccess()
            } catch (e: CancellationException) { throw e } catch (e: IllegalArgumentException) {
                messages.send(e.message ?: "操作未完成，请重试")
            } catch (_: Exception) {
                messages.send("保存失败，修改未生效。请检查手机存储空间后重试。")
            } finally { _busy.value = false }
        }
    }
    fun exportCsv(uri: Uri) = fileAction("导出失败，请重新选择保存位置") {
        repository.load()
        val snapshot = checkNotNull(state.value)
        withContext(Dispatchers.IO) { documents.write(uri,CsvBackup.encode(snapshot)) }
        messages.send("CSV 备份已导出，共 ${snapshot.goals.size} 个目标、${CsvBackup.stepCount(snapshot)} 个步骤。")
    }
    fun previewCsv(uri: Uri) = fileAction("无法读取这个文件，请选择应用导出的 CSV 备份") {
        _importPreview.value = null
        repository.load()
        val backup = withContext(Dispatchers.IO) { documents.read(uri) }
        _importPreview.value = ImportPreview(backup,checkNotNull(state.value))
    }
    fun dismissImport() { if(!_busy.value) _importPreview.value = null }
    fun confirmImport() {
        val preview = _importPreview.value ?: return
        fileAction("恢复失败，当前数据未被替换，请检查存储空间后重试") {
            repository.restore(preview.current,preview.backup.state)
            _importPreview.value = null
            messages.send("CSV 备份已恢复，目标、步骤顺序和时间记录已还原。")
        }
    }
    fun noFilePicker() { viewModelScope.launch { messages.send("没有找到可用的系统文件选择器") } }
    private fun fileAction(failureMessage: String, action: suspend () -> Unit) {
        if(_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch(e: CancellationException) { throw e }
            catch(e: IllegalArgumentException) { messages.send(e.message ?: failureMessage) }
            catch(_: Exception) { messages.send(failureMessage) }
            finally { _busy.value = false }
        }
    }
}
