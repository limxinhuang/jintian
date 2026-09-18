package com.jintian.app.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jintian.app.ImportPreview
import com.jintian.app.MainViewModel
import com.jintian.app.data.CsvBackup
import com.jintian.app.domain.AppState
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupRoute(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val preview by viewModel.importPreview.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(viewModel::exportCsv) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::previewCsv) }
    BackupScreen(state ?: AppState(),busy,onExport={
        try { exporter.launch("今天备份-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.csv") }
        catch(_: ActivityNotFoundException) { viewModel.noFilePicker() }
    },onImport={
        // CSV providers report various MIME types. Validate the actual contents.
        try { importer.launch(arrayOf("*/*")) }
        catch(_: ActivityNotFoundException) { viewModel.noFilePicker() }
    })
    preview?.let { ImportPreviewDialog(it,busy,viewModel::dismissImport,viewModel::confirmImport) }
}

@Composable
fun BackupScreen(state: AppState, busy: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("把记录留一份备份",style=MaterialTheme.typography.titleLarge)
                Text("当前 ${state.goals.size} 个目标 · ${CsvBackup.stepCount(state)} 个步骤",style=MaterialTheme.typography.bodyLarge)
                Text("包括全部目标、步骤顺序、执行状态、时间、循环关系，以及累计目标、单位和每次完成数值。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick=onExport,Modifier.fillMaxWidth().testTag("export-csv"),enabled=!busy) { Text("导出 CSV 备份") }
                Text("选择保存位置后生成 CSV 文件，可用表格软件查看。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("从备份恢复",style=MaterialTheme.typography.titleMedium)
                Text("选择「今天」导出的 CSV。先检查文件和预览内容，确认后整份替换当前数据。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick=onImport,Modifier.fillMaxWidth().testTag("import-csv"),enabled=!busy) { Text("导入 CSV 备份") }
                Text("导入前可以先导出当前数据。取消选择或文件校验失败，都不会修改现有记录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在处理文件…",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ImportPreviewDialog(preview: ImportPreview, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val restored = preview.backup.state
    AlertDialog(
        onDismissRequest={ if(!busy) onDismiss() },
        title={ Text("恢复这份 CSV 备份？") },
        text={
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("导出于 ${preview.backup.exportedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"))}")
                Text("备份：${restored.goals.size} 个目标、${preview.backup.stepCount} 个步骤",style=MaterialTheme.typography.titleMedium)
                Text("其中 ${restored.goals.count { it.archived }} 个目标已归档，${restored.goals.sumOf { it.done.size }} 个步骤已完成。")
                restored.goals.take(5).forEach { Text("• ${it.title}${if(it.archived) "（已归档）" else ""}") }
                if(restored.goals.size>5) Text("另有 ${restored.goals.size-5} 个目标。")
                if(restored.active!=null) Text("正在执行的步骤也会还原，并按原开始时间继续计时。")
                HorizontalDivider()
                Text("当前设备：${preview.current.goals.size} 个目标、${CsvBackup.stepCount(preview.current)} 个步骤。")
                Text(if(restored.goals.isEmpty()) "这是空备份，恢复后会清空当前所有目标和步骤。" else "将替换当前所有目标和步骤，不会与当前数据合并。",color=MaterialTheme.colorScheme.error)
                Text("如需保留当前数据，请先取消并导出一份备份。",style=MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton={TextButton(onClick=onConfirm,enabled=!busy,modifier=Modifier.testTag("confirm-import")) { Text(if(busy) "正在恢复…" else "替换并恢复") }},
        dismissButton={TextButton(onClick=onDismiss,enabled=!busy) { Text("取消") }},
    )
}
