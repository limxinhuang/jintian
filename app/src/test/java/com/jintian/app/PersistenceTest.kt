package com.jintian.app

import com.jintian.app.data.*
import com.jintian.app.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class PersistenceTest {
    private val date=LocalDate.of(2026,9,17)
    private fun state(): AppState = AppState(listOf(
        Goal("g","作品集",date,date.plusDays(14),pending=listOf(Task("later","后续任务")),next=Task("next","下一项"),done=listOf(Task("done","已完成",1000,61000))),
        Goal("h","阅读",date,date.plusDays(20),next=Task("other","另一目标下一项"))),
        ActiveTask("g",Task("active","正在做"),123456))
    @Test fun roundTripPreservesOrderAndActiveSlot() {
        assertEquals(state(),StateCodec.decode(StateCodec.encode(state())))
    }
    @Test fun invalidDataIsRejectedRatherThanReset() {
        assertThrows(Exception::class.java) { StateCodec.decode("broken json") }
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decode("{\"version\":5}") }
    }
    @Test fun activeTaskSurvivesRepositoryRestart() = runBlocking {
        val store=MemoryStore(state())
        val first=GoalRepository(store); first.load()
        first.update { GoalRules.complete(it,"active") }
        first.update { GoalRules.start(it,"h","other",333) }
        val restarted=GoalRepository(store); restarted.load()
        assertEquals("other",restarted.state.value?.active?.task?.id)
        assertEquals(333L,restarted.state.value?.active?.startedAt)
        assertEquals(2,restarted.state.value?.goal("g")?.done?.size)
    }
    @Test fun versionOneDataMigratesWithoutInventingHistoricalTimes() {
        val old="""{"version":1,"goals":[{"id":"g","title":"旧目标","start":"2026-09-17","end":"2026-10-01","pending":[],"next":null,"done":[{"id":"t","title":"旧步骤","minutes":60}],"archived":true}],"active":null}"""
        val migrated=StateCodec.decode(old)
        assertEquals("旧步骤",migrated.goal("g").done.single().title)
        assertNull(migrated.goal("g").done.single().startedAt)
        assertEquals(migrated,StateCodec.decode(StateCodec.encode(migrated)))
    }
    @Test fun concurrentStartsCommitExactlyOne() = runBlocking {
        val store=MemoryStore(state().copy(active=null))
        val repo=GoalRepository(store); repo.load()
        val attempts=listOf("g" to "next","h" to "other").map { (g,t) -> async { runCatching { repo.update { GoalRules.start(it,g,t,1) } }.isSuccess } }.awaitAll()
        assertEquals(1,attempts.count { it })
        assertEquals(1,store.writes)
        assertNotNull(repo.state.value?.active)
    }
    @Test fun failedSaveDoesNotPublishUnpersistedChanges() = runBlocking {
        val original=state()
        val store=MemoryStore(original);store.failWrite=true
        val repo=GoalRepository(store);repo.load()
        assertTrue(runCatching { repo.update { GoalRules.complete(it,"active") } }.isFailure)
        assertEquals(original,repo.state.value)
        assertEquals(original,store.read())
    }
    @Test fun failedLoadNeverBecomesAnEmptyWritableState() = runBlocking {
        val store=object:SnapshotStore {
            override fun read():AppState=throw IOException("unreadable")
            override fun write(state:AppState)=error("must not write")
        }
        val repo=GoalRepository(store)
        assertTrue(runCatching { repo.load() }.isFailure)
        assertNull(repo.state.value)
        assertTrue(runCatching { repo.update { AppState() } }.isFailure)
    }
    @Test fun restoreReplacesTheWholeSnapshotAndCanRestoreAnEmptyBackup() = runBlocking {
        val original=state()
        val store=MemoryStore(original)
        val repo=GoalRepository(store);repo.load()
        val replacement=CsvBackup.decode(CsvBackup.encode(AppState())).state
        repo.restore(original,replacement)
        assertEquals(AppState(),repo.state.value)
        assertEquals(AppState(),store.read())
    }
    @Test fun staleImportPreviewCannotOverwriteNewerEdits() = runBlocking {
        val original=state()
        val store=MemoryStore(original)
        val repo=GoalRepository(store);repo.load()
        repo.update { GoalRules.complete(it,"active") }
        val changed=repo.state.value
        assertTrue(runCatching { repo.restore(original,AppState()) }.isFailure)
        assertEquals(changed,repo.state.value)
        assertEquals(changed,store.read())
        assertEquals(1,store.writes)
    }
    @Test fun failedRestoreKeepsThePreviousSnapshot() = runBlocking {
        val original=state()
        val store=MemoryStore(original);store.failWrite=true
        val repo=GoalRepository(store);repo.load()
        assertTrue(runCatching { repo.restore(original,AppState()) }.isFailure)
        assertEquals(original,repo.state.value)
        assertEquals(original,store.read())
    }
    private class MemoryStore(state:AppState):SnapshotStore {
        private var text=StateCodec.encode(state)
        var writes=0
        var failWrite=false
        override fun read()=StateCodec.decode(text)
        override fun write(state:AppState) {
            if(failWrite) throw IOException("disk full")
            text=StateCodec.encode(state);writes++
        }
    }
}
