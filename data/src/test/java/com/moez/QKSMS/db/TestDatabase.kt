package dev.octoshrimpy.quik.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/** A fresh in-memory database for one test. Main-thread queries are allowed here only. */
fun inMemoryDatabase(): FoxholeDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FoxholeDatabase::class.java)
        .allowMainThreadQueries()
        .build()
