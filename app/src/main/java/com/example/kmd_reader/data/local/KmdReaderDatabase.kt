package com.example.kmd_reader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkEntity::class,
        ScriptIssueEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KmdReaderDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao

    abstract fun scriptIssueDao(): ScriptIssueDao

    companion object {
        const val DatabaseName = "kmd-reader.db"

        fun create(context: Context): KmdReaderDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KmdReaderDatabase::class.java,
                DatabaseName
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
