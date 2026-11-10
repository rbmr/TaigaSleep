package com.rbmr.taigasleep.sync.db

import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.RuntimeEnvironment

/** The app's database in memory, for the tests that run against the real tables. */
internal fun testDatabase(): TaigaDatabase =
    Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaigaDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/** Where a store keeps the queries it publishes, for as long as a test needs it. */
internal fun storeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
