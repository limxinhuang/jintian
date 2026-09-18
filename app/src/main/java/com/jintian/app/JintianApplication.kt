package com.jintian.app

import android.app.Application
import com.jintian.app.data.AtomicSnapshotStore
import com.jintian.app.data.GoalRepository
import java.io.File

class JintianApplication : Application() {
    val repository by lazy { GoalRepository(AtomicSnapshotStore(File(filesDir, "goals-v1.json"))) }
}
