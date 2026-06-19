package com.example.kmd_reader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkEntity::class,
        ScriptIssueEntity::class,
        LocalLibraryEntity::class,
        LocalRevisionEntity::class,
        LocalDraftEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class KmdReaderDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao

    abstract fun scriptIssueDao(): ScriptIssueDao

    abstract fun localLibraryDao(): LocalLibraryDao

    abstract fun localRevisionDao(): LocalRevisionDao

    abstract fun localDraftDao(): LocalDraftDao

    companion object {
        const val DatabaseName = "kmd-reader.db"

        // 显式 migration 2→3：新增 local_library / local_revisions / local_drafts 三张表。
        // 不依赖 fallbackToDestructiveMigration（会清空 works/script_issues）。
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_library` (
                        `workId` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `onShelf` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `authorName` TEXT NOT NULL,
                        `presentationMode` TEXT NOT NULL,
                        `aspectRatio` TEXT NOT NULL,
                        `kmdSource` TEXT,
                        `contentUri` TEXT NOT NULL,
                        `readingProgress` REAL NOT NULL,
                        `readingTimeMs` INTEGER,
                        `readingDurationMs` INTEGER,
                        `lastReadAt` INTEGER,
                        `importedAt` INTEGER,
                        `cachedAt` INTEGER,
                        PRIMARY KEY(`workId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_revisions` (
                        `id` TEXT NOT NULL,
                        `workId` TEXT NOT NULL,
                        `baseRevisionId` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `label` TEXT,
                        `synced` INTEGER NOT NULL,
                        `cloudRevisionId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workId`) REFERENCES `local_library`(`workId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_revisions_workId` ON `local_revisions` (`workId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_drafts` (
                        `id` TEXT NOT NULL,
                        `workId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workId`) REFERENCES `local_library`(`workId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_drafts_workId` ON `local_drafts` (`workId`)")
            }
        }

        fun create(context: Context): KmdReaderDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KmdReaderDatabase::class.java,
                DatabaseName
            )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
