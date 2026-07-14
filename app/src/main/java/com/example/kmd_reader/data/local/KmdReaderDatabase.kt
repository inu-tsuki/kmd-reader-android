package com.example.kmd_reader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room database for community cache plus the R3 local-library persistence model. */
@Database(
    entities = [
        WorkEntity::class,
        ScriptIssueEntity::class,
        LocalLibraryEntity::class,
        LocalRevisionEntity::class,
        LocalDraftEntity::class
    ],
    version = 4,
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
        // internal：迁移测试需要直接复跑 migrate()，验证 v2→v3 不丢数据、新表结构正确。
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
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

        // R3-D1：migration 3→4 — local_library 加 4 个指针列 + local_revisions outbox → commit 模型。
        // 合并两组 schema 变更到一次迁移（少一次 migration），与 r3-local-reader-plan.md §2.7/§R3-D1 对齐。
        // local_revisions 是纯接口预留（无生产写入路径），旧表 DROP+重建不丢用户数据。
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── local_library：加 4 个 nullable 指针列（ALTER TABLE ADD COLUMN 对 nullable 列可行）──
                db.execSQL("ALTER TABLE `local_library` ADD COLUMN `bundleId` TEXT")
                db.execSQL("ALTER TABLE `local_library` ADD COLUMN `activeRevisionId` TEXT")
                db.execSQL("ALTER TABLE `local_library` ADD COLUMN `contentHash` TEXT")
                db.execSQL("ALTER TABLE `local_library` ADD COLUMN `originWorkId` TEXT")

                // ── local_revisions：outbox → commit 模型（破坏性 schema 变更，重建表）──
                // 旧表无生产写入路径（仅接口预留），直接 DROP 重建；不尝试迁移旧 outbox 行。
                db.execSQL("DROP TABLE IF EXISTS `local_revisions`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_revisions` (
                        `id` TEXT NOT NULL,
                        `workId` TEXT NOT NULL,
                        `parentRevisionId` TEXT,
                        `contentHash` TEXT NOT NULL,
                        `sourcePath` TEXT NOT NULL,
                        `storageMode` TEXT NOT NULL,
                        `message` TEXT,
                        `syncState` TEXT NOT NULL,
                        `remoteRevisionId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workId`) REFERENCES `local_library`(`workId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_revisions_workId` ON `local_revisions` (`workId`)")
            }
        }

        fun create(context: Context): KmdReaderDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KmdReaderDatabase::class.java,
                DatabaseName
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
