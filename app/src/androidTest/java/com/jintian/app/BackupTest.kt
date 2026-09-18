package com.jintian.app

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jintian.app.data.CsvBackup
import com.jintian.app.data.CsvDocuments
import com.jintian.app.data.CsvSnapshot
import com.jintian.app.domain.*
import com.jintian.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupTest {
    @get:Rule val compose=createComposeRule()
    @Test fun backupEntryAndButtonsAreReachableFromStatistics() {
        var exports=0;var imports=0
        compose.setContent { JintianTheme { JintianContent(AppState(),false,{_,_->},backupContent={BackupScreen(AppState(),false,{exports++},{imports++})}) } }
        compose.onNodeWithTag("tab-stats").performClick()
        compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("open-backup"))
        compose.onNodeWithTag("open-backup").performClick()
        compose.onNodeWithTag("export-csv").performScrollTo().performClick()
        compose.onNodeWithTag("import-csv").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,exports);assertEquals(1,imports) }
    }
    @Test fun importNeedsExplicitConfirmationAndWarnsAboutAnEmptyBackup() {
        var confirmed=0;var canceled=0
        val date=LocalDate.now()
        val existing=AppState(listOf(Goal("g","当前目标",date,date.plusDays(14))))
        compose.setContent { JintianTheme { ImportPreviewDialog(ImportPreview(CsvSnapshot(AppState(),Instant.now()),existing),false,{canceled++},{confirmed++}) } }
        compose.onNodeWithText("这是空备份，恢复后会清空当前所有目标和步骤。").assertExists()
        compose.runOnIdle { assertEquals(0,confirmed) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1,canceled);assertEquals(0,confirmed) }
    }
    @Test fun contentResolverCanWriteAndReadACompleteCsvBackup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"csv-${UUID.randomUUID()}.csv")
        val date=LocalDate.now()
        val state=AppState(listOf(Goal("g","中文备份",date,date.plusDays(14),pending=listOf(Task("t","包含,逗号和\"引号\"\n换行")))))
        try {
            val documents=CsvDocuments(context.contentResolver)
            documents.write(Uri.fromFile(file),CsvBackup.encode(state))
            assertEquals(state,documents.read(Uri.fromFile(file)).state)
        } finally { file.delete() }
    }
}
