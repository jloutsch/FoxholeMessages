package dev.octoshrimpy.quik.db

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database, replacing Realm (#44). Version 1 is the first Room schema; later
 * changes need a Room migration and a test against the exported schema in data/schemas.
 */
@Database(entities = [SchemaPlaceholder::class], version = 1, exportSchema = true)
@TypeConverters(StringListConverter::class)
abstract class FoxholeDatabase : RoomDatabase()

/** Room needs at least one table; replaced by the real tables in Task 7 of the migration plan. */
@Entity
data class SchemaPlaceholder(@PrimaryKey val id: Long)
