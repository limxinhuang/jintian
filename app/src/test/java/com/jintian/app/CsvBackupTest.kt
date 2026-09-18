package com.jintian.app

import com.jintian.app.data.CsvBackup
import com.jintian.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CsvBackupTest {
    private val date = LocalDate.of(2026,9,17)
    private val exported = Instant.parse("2026-09-17T10:15:30.123Z")
    private fun sample() = AppState(listOf(
        Goal("g1","作品集",date,date.plusDays(14),pending=listOf(Task("b","第二步"),Task("a","第一步")),next=Task("n","下一项"),done=listOf(Task("d","已完成",1000,61000)),createdAt=123),
        Goal("g2","已归档目标",date,date.plusDays(8),done=listOf(Task("old","旧版记录")),archived=true),
        Goal("g3","空目标",date,date.plusDays(29))),ActiveTask("g1",Task("current","当前步骤"),123456))
    private fun text(state: AppState = sample()) = CsvBackup.encode(state,exported).toString(Charsets.UTF_8)
    private fun reject(text: String) { assertThrows(IllegalArgumentException::class.java) { CsvBackup.decode(text.toByteArray(Charsets.UTF_8)) } }

    @Test fun completeBackupRoundTripPreservesAllStatesAndTimes() {
        val snapshot = CsvBackup.decode(CsvBackup.encode(sample(),exported))
        assertEquals(sample(),snapshot.state)
        assertEquals(exported,snapshot.exportedAt)
        assertEquals(6,snapshot.stepCount)
        assertEquals(listOf("b","a"),snapshot.state.goal("g1").pending.map { it.id })
    }
    @Test fun emptyApplicationIsAValidExplicitEmptyBackup() {
        val snapshot=CsvBackup.decode(CsvBackup.encode(AppState(),exported))
        assertEquals(AppState(),snapshot.state)
        assertEquals(0,snapshot.stepCount)
    }
    @Test fun quotesCommasNewlinesAndChineseAreLossless() {
        val original=AppState(listOf(Goal("g","中文,\"目标\"",date,date.plusDays(14),pending=listOf(Task("t","先读\"引号\",再做记录\r\n第二行\n第三行 😀")))))
        assertEquals(original,CsvBackup.decode(CsvBackup.encode(original)).state)
    }
    @Test fun spreadsheetLikeTitlesRemainLiteralAndReversible() {
        val titles=listOf("=SUM(A1:A2)","+1+1","-123","@name","'单引号","'=公式","  =前面有空格","\t=制表符","正常文字")
        val original=AppState(listOf(Goal("g","=目标",date,date.plusDays(14),pending=titles.mapIndexed { i,s -> Task("t$i",s) })))
        val bytes=CsvBackup.encode(original)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\"'=SUM(A1:A2)\""))
        assertEquals(original,CsvBackup.decode(bytes).state)
    }
    @Test fun bomAndCommonLineEndingsAreSupported() {
        val csv=text()
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(sample(),CsvBackup.decode(csv.removePrefix("\uFEFF").replace("\r\n","\n").toByteArray()).state)
        assertEquals(sample(),CsvBackup.decode(csv.removeSuffix("\r\n").toByteArray()).state)
    }
    @Test fun physicalRowOrderDoesNotOverrideRecordedOrder() {
        val lines=text().removeSuffix("\r\n").split("\r\n")
        val shuffled=(listOf(lines.first())+lines.drop(1).reversed()).joinToString("\r\n")
        assertEquals(sample(),CsvBackup.decode(shuffled.toByteArray()).state)
    }
    @Test fun truncatedRowsAndUnclosedQuotesAreRejected() {
        val lines=text().split("\r\n").filter { it.isNotEmpty() }
        reject(lines.dropLast(1).joinToString("\r\n"))
        reject(lines.first()+"\r\n\"未闭合")
    }
    @Test fun wrongHeadersAndUnknownVersionsAreRejected() {
        reject(text().replace("格式版本","别的表格"))
        reject(text().replaceFirst("\"3\",\"备份\"","\"4\",\"备份\""))
        reject(text().replaceFirst("\"备份\"","\"未知记录\""))
    }
    @Test fun missingOrDuplicateMetadataIsRejected() {
        val lines=text().removeSuffix("\r\n").split("\r\n")
        reject((listOf(lines.first())+lines.drop(2)).joinToString("\r\n"))
        reject((lines+lines[1]).joinToString("\r\n"))
    }
    @Test fun orphanTasksAndDuplicateIdentifiersAreRejected() {
        reject(text().replaceFirst("\"步骤\",\"g1\"","\"步骤\",\"missing\""))
        reject(text().replace("\"g2\"","\"g1\""))
        reject(text().replace("\"b\"","\"a\""))
    }
    @Test fun multipleActiveOrNextTasksAreRejected() {
        reject(text().replace("\"下一个\"","\"正在执行\""))
        reject(text().replace("\"待执行\"","\"下一个\""))
    }
    @Test fun moreThanFiveVisibleGoalsIsRejected() {
        val archived=(1..6).map { Goal("g$it","目标 $it",date,date.plusDays(14),done=listOf(Task("t$it","已完成")),archived=true) }
        reject(text(AppState(archived)).replace("\"是\"","\"否\""))
    }
    @Test fun gapsInStepOrderAreRejected() {
        reject(text().replaceFirst("\"b\",\"1\"","\"b\",\"9\""))
    }
    @Test fun invalidDatesAndIncompleteTimeRecordsAreRejected() {
        reject(text().replace("2026-10-01","2026-09-16"))
        reject(text().replace("1970-01-01T00:02:03.456Z",""))
        reject(text().replace("1970-01-01T00:01:01Z",""))
    }
    @Test fun invalidQuotedCsvIsRejected() {
        reject(text().replaceFirst("\"作品集\"","\"作品集\"junk"))
        reject(text().replaceFirst("\"作品集\"","作品\"集"))
    }
    @Test fun malformedUtf8AndOversizedFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvBackup.decode(byteArrayOf(0xC3.toByte(),0x28)) }
        assertThrows(IllegalArgumentException::class.java) { CsvBackup.decode(ByteArray(CsvBackup.MAX_BYTES+1)) }
    }
    @Test fun unknownStepStatusCannotSilentlyLoseData() { reject(text().replace("\"待执行\"","\"未知状态\"")) }
    @Test fun populatedIrrelevantColumnsAreNotSilentlyDiscarded() {
        reject(text().replaceFirst("\"3\",\"步骤\",\"g1\",\"\"","\"3\",\"步骤\",\"g1\",\"偷偷写入\""))
    }
    @Test fun versionOneBackupStillRestoresEveryStepAsSingle() {
        // Literal v1 schema, independent of the current writer.
        val header = "格式版本,记录类型,目标ID,目标顺序,目标名称,开始日期,截止日期,已归档,目标创建时间,步骤ID,步骤顺序,步骤内容,步骤状态,步骤开始时间,步骤完成时间,导出时间,目标总数,步骤总数"
        fun row(vararg values: Pair<Int,String>) = MutableList(18) { "" }.apply {
            this[0] = "1"; values.forEach { (index, value) -> this[index] = value }
        }.joinToString(",")
        val old = listOf(header,
            row(1 to "备份",15 to exported.toString(),16 to "1",17 to "1"),
            row(1 to "目标",2 to "g",3 to "1",4 to "旧目标",5 to "2026-09-17",6 to "2026-10-01",7 to "否"),
            row(1 to "步骤",2 to "g",9 to "t",10 to "1",11 to "旧步骤",12 to "待执行"),
        ).joinToString("\r\n")
        val restored = CsvBackup.decode(old.toByteArray()).state
        assertEquals(Task("t", "旧步骤"), restored.goal("g").pending.single())
        assertEquals(restored, CsvBackup.decode(CsvBackup.encode(restored)).state)
    }
    @Test fun invalidRecurrenceMetadataIsRejected() {
        reject(text().replaceFirst("\"单次型\"", "\"未知类型\""))
        reject(text().replaceFirst("\"单次型\"", "\"循环型\""))
        reject(text().replaceFirst("\"单次型\",\"\",\"否\"", "\"单次型\",\"series\",\"否\""))
        reject(text().replaceFirst("\"单次型\",\"\",\"否\"", "\"单次型\",\"\",\"是\""))
        val original = AppState(listOf(Goal("g","循环目标",date,date.plusDays(14),pending=listOf(Task("r","循环",seriesId="series")))))
        reject(text(original).replace("\"循环型\",\"series\",\"否\"", "\"循环型\",\"series\",\"是\""))
    }
}
