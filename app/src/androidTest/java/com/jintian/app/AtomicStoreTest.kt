package com.jintian.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jintian.app.data.AtomicSnapshotStore
import com.jintian.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AtomicStoreTest {
    @Test fun actualDeviceFileRetainsActiveTaskAndQueue() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"test-${UUID.randomUUID()}.json")
        try {
            val store=AtomicSnapshotStore(file)
            assertEquals(AppState(),store.read())
            val date=LocalDate.now()
            var state=AppState(listOf(Goal("g","目标",date,date.plusDays(14),pending=listOf(Task("b","下一个")),next=Task("a","当前任务"))))
            state=GoalRules.start(state,"g","a",123)
            store.write(state)
            assertEquals(state,AtomicSnapshotStore(file).read())
            state=GoalRules.complete(state,"a")
            store.write(state)
            assertEquals(state,AtomicSnapshotStore(file).read())
        } finally { file.delete() }
    }
}
