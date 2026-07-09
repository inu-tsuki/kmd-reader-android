package com.example.kmd_reader.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Finding 3 回归：MIGRATION_2_3 必须真正被跑过、且不丢数据。
 *
 * exportSchema=false，无法用 MigrationTestHelper 的 schema 对比，所以手动构造 v2 库
 * （只有 works + script_issues），塞入 v2 数据，再跑真实的 [KmdReaderDatabase.MIGRATION_2_3]，
 * 然后以 v3 Room 库重新打开，断言：
 *   - v2 的 works / script_issues 数据还在；
 *   - local_library / local_revisions / local_drafts 三张新表已建好且结构可写；
 *   - 新表的外键级联在新库上仍然有效。
 *
 * 这条测试保护的是「不要因为兜底的 fallbackToDestructiveMigration 把用户 works 历史清掉」。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class KmdReaderDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun initContext() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun migration2To3PreservesV2DataAndCreatesNewTables() = runTest {
        // 1) 建一个 v2 库（works + script_issues），写入 v2 数据。
        val v2 = createV2Database(context)
        v2.use { db ->
            insertV2Work(db, id = "rain-city", title = "雨城慢镜")
            insertV2Issue(db, id = "iss-1", workId = "rain-city", message = "剧本审查意见")
        }

        // 2) 以同样的 db 文件为底，跑真实 MIGRATION_2_3 + MIGRATION_3_4，再以 v4 Room 库打开。
        //    不用 .use{}：Room DAO 是 suspend，use 的 lambda 非挂起，无法直接调用。
        val migrated = Room.databaseBuilder(
            context.applicationContext,
            KmdReaderDatabase::class.java,
            DB_NAME
        )
            .addMigrations(KmdReaderDatabase.MIGRATION_2_3, KmdReaderDatabase.MIGRATION_3_4)
            // 不加 fallbackToDestructiveMigration：迁移失败必须让测试爆掉，而不是静默清库。
            .allowMainThreadQueries()
            .build()

        try {
            // ── v2 数据存活 ──
            val work = migrated.workDao().getById("rain-city")
            assertNotNull("v2 work must survive migration", work)
            assertEquals("雨城慢镜", work?.title)
            val issues = migrated.scriptIssueDao().getByWorkId("rain-city")
            assertEquals("v2 script_issues must survive migration", 1, issues.size)
            assertEquals("剧本审查意见", issues.single().message)

            // ── 新表可写且结构正确 ──
            val libraryDao = migrated.localLibraryDao()
            val revisionDao = migrated.localRevisionDao()
            val draftDao = migrated.localDraftDao()

            libraryDao.upsert(
                LocalLibraryEntity(
                    workId = "rain-city", source = "Mock", onShelf = true,
                    title = "雨城慢镜", authorName = "Mira",
                    presentationMode = "Stage", aspectRatio = "9:16",
                    kmdSource = null, contentUri = "mock/rain-city.kmd",
                    readingProgress = 0f, readingTimeMs = null, readingDurationMs = null,
                    lastReadAt = null, importedAt = 10L, cachedAt = null
                )
            )
            revisionDao.insert(
                LocalRevisionEntity(
                    id = "rev-1", workId = "rain-city", parentRevisionId = null,
                    contentHash = "sha256-rev-1",
                    sourcePath = "bundles/bundle-1/revisions/rev-1.kmd",
                    storageMode = "full", message = null, syncState = "local",
                    remoteRevisionId = null, createdAt = 1L
                )
            )
            draftDao.upsert(LocalDraftEntity(id = "d1", workId = "rain-city", type = "issue", payload = "{}", updatedAt = 1L))

            assertEquals("rain-city", libraryDao.getByWorkId("rain-city")?.workId)
            assertNotNull(revisionDao.getLatestRevision("rain-city"))
            assertEquals(1, draftDao.getByWorkId("rain-city").size)

            // ── 新表的外键级联在新库上仍然生效 ──
            libraryDao.deleteByWorkId("rain-city")
            assertNull(revisionDao.getLatestRevision("rain-city"))
            assertTrue(draftDao.getByWorkId("rain-city").isEmpty())
        } finally {
            migrated.close()
        }
    }

    /**
     * MIGRATION_3_4 验证：local_library 加 4 个指针列 + local_revisions outbox → commit 模型重建。
     *
     * 构造一个 v3 库（works + script_issues + local_library 旧 schema + local_revisions 旧 outbox 行 +
     * local_drafts），跑真实 MIGRATION_3_4，以 v4 Room 库打开，断言：
     *   - works / script_issues / local_library / local_drafts 数据存活；
     *   - local_library 新增 4 个指针列存在且可 round-trip；
     *   - local_revisions 旧 outbox 行已被 DROP（纯接口预留，无生产写入）；
     *   - local_revisions 新表可写 commit-model 行；
     *   - FK CASCADE 仍生效。
     */
    @Test
    fun migration3To4PreservesDataAndRevisesRevisionSchema() = runTest {
        // 1) 建一个 v3 库（含旧 local_revisions outbox schema），写入 v3 数据。
        val v3 = createV3Database(context)
        v3.use { db ->
            insertV3Work(db, id = "rain-city", title = "雨城慢镜")
            insertV3Issue(db, id = "iss-1", workId = "rain-city", message = "剧本审查意见")
            insertV3LibraryEntry(db, workId = "rain-city", title = "雨城慢镜")
            insertV3OutboxRevision(db, id = "rev-old", workId = "rain-city")
            insertV3Draft(db, id = "d1", workId = "rain-city")
        }

        // 2) 跑 MIGRATION_3_4，以 v4 Room 库打开（不加 destructive fallback）。
        val migrated = Room.databaseBuilder(
            context.applicationContext,
            KmdReaderDatabase::class.java,
            DB_NAME_V4
        )
            .addMigrations(KmdReaderDatabase.MIGRATION_2_3, KmdReaderDatabase.MIGRATION_3_4)
            // 不加 fallbackToDestructiveMigration：迁移失败必须让测试爆掉。
            .allowMainThreadQueries()
            .build()

        try {
            // ── v3 数据存活 ──
            val work = migrated.workDao().getById("rain-city")
            assertNotNull("v3 work must survive migration 3→4", work)
            assertEquals("雨城慢镜", work?.title)
            val issues = migrated.scriptIssueDao().getByWorkId("rain-city")
            assertEquals("v3 script_issues must survive", 1, issues.size)

            // ── local_library 数据存活 + 新指针列 round-trip ──
            val libraryDao = migrated.localLibraryDao()
            val existingEntry = libraryDao.getByWorkId("rain-city")
            assertNotNull("v3 local_library entry must survive", existingEntry)
            // 旧 entry 的指针字段应为 null（迁移只加列，不填值）
            assertNull("bundleId should be null after ALTER ADD COLUMN", existingEntry?.bundleId)
            assertNull("activeRevisionId should be null after ALTER ADD COLUMN", existingEntry?.activeRevisionId)
            // round-trip：写入带指针的 entry，读回验证
            libraryDao.upsert(
                existingEntry!!.copy(
                    bundleId = "bundle-uuid-1",
                    activeRevisionId = "rev-local-1",
                    contentHash = "sha256-abc",
                    originWorkId = "cloud-work-42"
                )
            )
            val withPointers = libraryDao.getByWorkId("rain-city")
            assertEquals("bundleId round-trip", "bundle-uuid-1", withPointers?.bundleId)
            assertEquals("activeRevisionId round-trip", "rev-local-1", withPointers?.activeRevisionId)
            assertEquals("contentHash round-trip", "sha256-abc", withPointers?.contentHash)
            assertEquals("originWorkId round-trip", "cloud-work-42", withPointers?.originWorkId)

            // ── local_revisions：旧 outbox 行已被 DROP，新 commit-model 表可写 ──
            val revisionDao = migrated.localRevisionDao()
            // 旧 outbox 行不应存在（表被重建）
            // （无法用旧字段查询，直接验证新表从空开始）
            assertNull("revisions should be empty after table rebuild", revisionDao.getLatestRevision("rain-city"))

            // 写 commit-model 行
            revisionDao.insert(
                LocalRevisionEntity(
                    id = "rev-local-1", workId = "rain-city",
                    parentRevisionId = null,
                    contentHash = "sha256-abc",
                    sourcePath = "bundles/bundle-uuid-1/revisions/rev-local-1.kmd",
                    storageMode = "full",
                    message = "首次导入",
                    syncState = "local",
                    remoteRevisionId = null,
                    createdAt = 100L
                )
            )
            revisionDao.insert(
                LocalRevisionEntity(
                    id = "rev-local-2", workId = "rain-city",
                    parentRevisionId = "rev-local-1",
                    contentHash = "sha256-def",
                    sourcePath = "bundles/bundle-uuid-1/revisions/rev-local-2.kmd",
                    storageMode = "full",
                    message = "修正标题",
                    syncState = "local",
                    remoteRevisionId = null,
                    createdAt = 200L
                )
            )

            val latest = requireNotNull(revisionDao.getLatestRevision("rain-city"))
            assertEquals("rev-local-2", latest.id)
            assertEquals("sha256-def", latest.contentHash)
            assertEquals("rev-local-1", latest.parentRevisionId)

            val byHash = revisionDao.findByContentHash("rain-city", "sha256-abc")
            assertNotNull("findByContentHash should find rev-local-1", byHash)
            assertEquals("rev-local-1", byHash?.id)

            val allRevs = revisionDao.getRevisionsForWork("rain-city")
            assertEquals("should have 2 revisions", 2, allRevs.size)
            // 按 createdAt DESC 排序
            assertEquals("rev-local-2", allRevs.first().id)

            // ── local_drafts 不受影响 ──
            val draftDao = migrated.localDraftDao()
            assertEquals("local_drafts must survive", 1, draftDao.getByWorkId("rain-city").size)

            // ── FK CASCADE 仍生效：删 library entry → revisions 消失 ──
            libraryDao.deleteByWorkId("rain-city")
            assertNull("revision must cascade-delete with library entry", revisionDao.getLatestRevision("rain-city"))
            assertTrue("draft must cascade-delete with library entry", draftDao.getByWorkId("rain-city").isEmpty())
        } finally {
            migrated.close()
        }
    }

    @After
    fun cleanup() {
        // 删除遗留的 db 文件，避免 Robolectric 会话间污染。
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(DB_NAME_V4)
    }

    private companion object {
        const val DB_NAME = "kmd-reader-migration-test.db"
        const val DB_NAME_V4 = "kmd-reader-migration-test-v4.db"

        /**
         * 用纯 SQL 建一个 v2 库：只有 works + script_issues 两张表。
         * 列定义对齐 v2 时代的 [WorkEntity] / [ScriptIssueEntity] 映射。
         */
        fun createV2Database(context: Context): SQLiteDatabase {
            val helper = object : SQLiteOpenHelper(
                context, DB_NAME, null, 2
            ) {
                override fun onCreate(db: SQLiteDatabase) {
                    // 与 v2 时代 WorkEntity 的 Room 映射一致。
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `works` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `authorName` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `tags` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `sourceType` TEXT NOT NULL,
                            `lifecycleStatus` TEXT NOT NULL,
                            `presentationMode` TEXT NOT NULL,
                            `orientationHint` TEXT NOT NULL,
                            `aspectRatio` TEXT NOT NULL,
                            `interactionLevel` TEXT NOT NULL,
                            `previewMode` TEXT NOT NULL,
                            `contentUri` TEXT NOT NULL,
                            `previewUri` TEXT,
                            `activeRevisionId` TEXT NOT NULL,
                            `scriptRevisionLabel` TEXT NOT NULL,
                            `scriptSourceUrl` TEXT NOT NULL,
                            `scriptMimeType` TEXT NOT NULL,
                            `scriptKmdVersion` TEXT NOT NULL,
                            `scriptRuntimeVersion` TEXT NOT NULL,
                            `scriptRevisionCreatedAt` TEXT NOT NULL,
                            `scriptContentHash` TEXT,
                            `assetManifestBaseUrl` TEXT,
                            `assetManifestAssets` TEXT NOT NULL,
                            `estimatedDurationSec` INTEGER NOT NULL,
                            `effectIntensity` TEXT NOT NULL,
                            `commandCount` INTEGER NOT NULL,
                            `externalAssetCount` INTEGER NOT NULL,
                            `complexityLevel` TEXT NOT NULL,
                            `runtimeVersion` TEXT NOT NULL,
                            `commentSummary` TEXT NOT NULL,
                            `commentHighlights` TEXT NOT NULL,
                            `commentConcerns` TEXT NOT NULL,
                            `syncedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `script_issues` (
                            `id` TEXT NOT NULL,
                            `workId` TEXT NOT NULL,
                            `severity` TEXT NOT NULL,
                            `source` TEXT NOT NULL,
                            `location` TEXT NOT NULL,
                            `message` TEXT NOT NULL,
                            `suggestion` TEXT NOT NULL,
                            `syncedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_script_issues_workId` ON `script_issues` (`workId`)")
                    // user_version = 2，模拟 v2 状态。
                    db.execSQL("PRAGMA user_version = 2")
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    // 迁移交给 Room 的 MIGRATION_2_3，这里不动。
                }
            }
            return helper.writableDatabase
        }

        fun insertV2Work(db: SQLiteDatabase, id: String, title: String) {
            val cv = ContentValues().apply {
                put("id", id)
                put("title", title)
                put("authorName", "Mira")
                put("description", "")
                put("tags", "")
                put("category", "")
                put("sourceType", "Mock")
                put("lifecycleStatus", "Published")
                put("presentationMode", "Stage")
                put("orientationHint", "Auto")
                put("aspectRatio", "9:16")
                put("interactionLevel", "Passive")
                put("previewMode", "Auto")
                put("contentUri", "mock/$id.kmd")
                putNull("previewUri")
                put("activeRevisionId", "rev-0")
                put("scriptRevisionLabel", "")
                put("scriptSourceUrl", "")
                put("scriptMimeType", "text/kmd")
                put("scriptKmdVersion", "1")
                put("scriptRuntimeVersion", "1")
                put("scriptRevisionCreatedAt", "")
                putNull("scriptContentHash")
                putNull("assetManifestBaseUrl")
                put("assetManifestAssets", "[]")
                put("estimatedDurationSec", 0)
                put("effectIntensity", "Auto")
                put("commandCount", 0)
                put("externalAssetCount", 0)
                put("complexityLevel", "Simple")
                put("runtimeVersion", "1")
                put("commentSummary", "")
                put("commentHighlights", "")
                put("commentConcerns", "")
                put("syncedAt", 1L)
            }
            db.insert("works", null, cv)
        }

        fun insertV2Issue(
            db: SQLiteDatabase,
            id: String,
            workId: String,
            message: String
        ) {
            val cv = ContentValues().apply {
                put("id", id)
                put("workId", workId)
                put("severity", "Warning")
                put("source", "")
                put("location", "")
                put("message", message)
                put("suggestion", "")
                put("syncedAt", 1L)
            }
            db.insert("script_issues", null, cv)
        }

        // ── v3 库构造（用于 3→4 迁移测试）──

        /**
         * 用纯 SQL 建一个 v3 库：works + script_issues + local_library（旧 schema，无指针列）
         * + local_revisions（旧 outbox schema）+ local_drafts。
         * 列定义对齐 MIGRATION_2_3 建表 SQL。
         */
        fun createV3Database(context: Context): SQLiteDatabase {
            val helper = object : SQLiteOpenHelper(
                context, DB_NAME_V4, null, 3
            ) {
                override fun onCreate(db: SQLiteDatabase) {
                    // works（与 v2 相同）
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `works` (
                            `id` TEXT NOT NULL, `title` TEXT NOT NULL, `authorName` TEXT NOT NULL,
                            `description` TEXT NOT NULL, `tags` TEXT NOT NULL, `category` TEXT NOT NULL,
                            `sourceType` TEXT NOT NULL, `lifecycleStatus` TEXT NOT NULL,
                            `presentationMode` TEXT NOT NULL, `orientationHint` TEXT NOT NULL,
                            `aspectRatio` TEXT NOT NULL, `interactionLevel` TEXT NOT NULL,
                            `previewMode` TEXT NOT NULL, `contentUri` TEXT NOT NULL, `previewUri` TEXT,
                            `activeRevisionId` TEXT NOT NULL, `scriptRevisionLabel` TEXT NOT NULL,
                            `scriptSourceUrl` TEXT NOT NULL, `scriptMimeType` TEXT NOT NULL,
                            `scriptKmdVersion` TEXT NOT NULL, `scriptRuntimeVersion` TEXT NOT NULL,
                            `scriptRevisionCreatedAt` TEXT NOT NULL, `scriptContentHash` TEXT,
                            `assetManifestBaseUrl` TEXT, `assetManifestAssets` TEXT NOT NULL,
                            `estimatedDurationSec` INTEGER NOT NULL, `effectIntensity` TEXT NOT NULL,
                            `commandCount` INTEGER NOT NULL, `externalAssetCount` INTEGER NOT NULL,
                            `complexityLevel` TEXT NOT NULL, `runtimeVersion` TEXT NOT NULL,
                            `commentSummary` TEXT NOT NULL, `commentHighlights` TEXT NOT NULL,
                            `commentConcerns` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `script_issues` (
                            `id` TEXT NOT NULL, `workId` TEXT NOT NULL, `severity` TEXT NOT NULL,
                            `source` TEXT NOT NULL, `location` TEXT NOT NULL, `message` TEXT NOT NULL,
                            `suggestion` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_script_issues_workId` ON `script_issues` (`workId`)")
                    // local_library（v3 旧 schema：无指针列）
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `local_library` (
                            `workId` TEXT NOT NULL, `source` TEXT NOT NULL, `onShelf` INTEGER NOT NULL,
                            `title` TEXT NOT NULL, `authorName` TEXT NOT NULL, `presentationMode` TEXT NOT NULL,
                            `aspectRatio` TEXT NOT NULL, `kmdSource` TEXT, `contentUri` TEXT NOT NULL,
                            `readingProgress` REAL NOT NULL, `readingTimeMs` INTEGER, `readingDurationMs` INTEGER,
                            `lastReadAt` INTEGER, `importedAt` INTEGER, `cachedAt` INTEGER,
                            PRIMARY KEY(`workId`)
                        )
                        """.trimIndent()
                    )
                    // local_revisions（v3 旧 outbox schema）
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `local_revisions` (
                            `id` TEXT NOT NULL, `workId` TEXT NOT NULL, `baseRevisionId` TEXT NOT NULL,
                            `source` TEXT NOT NULL, `label` TEXT, `synced` INTEGER NOT NULL,
                            `cloudRevisionId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`workId`) REFERENCES `local_library`(`workId`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_revisions_workId` ON `local_revisions` (`workId`)")
                    // local_drafts
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `local_drafts` (
                            `id` TEXT NOT NULL, `workId` TEXT NOT NULL, `type` TEXT NOT NULL,
                            `payload` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`workId`) REFERENCES `local_library`(`workId`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_drafts_workId` ON `local_drafts` (`workId`)")
                    db.execSQL("PRAGMA user_version = 3")
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // 迁移交给 Room 的 MIGRATION_3_4，这里不动。
                }
            }
            return helper.writableDatabase
        }

        fun insertV3Work(db: SQLiteDatabase, id: String, title: String) {
            val cv = ContentValues().apply {
                put("id", id)
                put("title", title)
                put("authorName", "Mira")
                put("description", "")
                put("tags", "")
                put("category", "")
                put("sourceType", "Mock")
                put("lifecycleStatus", "Published")
                put("presentationMode", "Stage")
                put("orientationHint", "Auto")
                put("aspectRatio", "9:16")
                put("interactionLevel", "Passive")
                put("previewMode", "Auto")
                put("contentUri", "mock/$id.kmd")
                putNull("previewUri")
                put("activeRevisionId", "rev-0")
                put("scriptRevisionLabel", "")
                put("scriptSourceUrl", "")
                put("scriptMimeType", "text/kmd")
                put("scriptKmdVersion", "1")
                put("scriptRuntimeVersion", "1")
                put("scriptRevisionCreatedAt", "")
                putNull("scriptContentHash")
                putNull("assetManifestBaseUrl")
                put("assetManifestAssets", "[]")
                put("estimatedDurationSec", 0)
                put("effectIntensity", "Auto")
                put("commandCount", 0)
                put("externalAssetCount", 0)
                put("complexityLevel", "Simple")
                put("runtimeVersion", "1")
                put("commentSummary", "")
                put("commentHighlights", "")
                put("commentConcerns", "")
                put("syncedAt", 1L)
            }
            db.insert("works", null, cv)
        }

        fun insertV3Issue(db: SQLiteDatabase, id: String, workId: String, message: String) {
            val cv = ContentValues().apply {
                put("id", id)
                put("workId", workId)
                put("severity", "Warning")
                put("source", "")
                put("location", "")
                put("message", message)
                put("suggestion", "")
                put("syncedAt", 1L)
            }
            db.insert("script_issues", null, cv)
        }

        fun insertV3LibraryEntry(db: SQLiteDatabase, workId: String, title: String) {
            val cv = ContentValues().apply {
                put("workId", workId)
                put("source", "Mock")
                put("onShelf", 1)
                put("title", title)
                put("authorName", "Mira")
                put("presentationMode", "Stage")
                put("aspectRatio", "9:16")
                putNull("kmdSource")
                put("contentUri", "mock/$workId.kmd")
                put("readingProgress", 0f)
                putNull("readingTimeMs")
                putNull("readingDurationMs")
                putNull("lastReadAt")
                put("importedAt", 10L)
                putNull("cachedAt")
            }
            db.insert("local_library", null, cv)
        }

        fun insertV3OutboxRevision(db: SQLiteDatabase, id: String, workId: String) {
            val cv = ContentValues().apply {
                put("id", id)
                put("workId", workId)
                put("baseRevisionId", "base")
                put("source", "---\nmode: stage\n---\nold")
                putNull("label")
                put("synced", 0)
                putNull("cloudRevisionId")
                put("createdAt", 1L)
                put("updatedAt", 1L)
            }
            db.insert("local_revisions", null, cv)
        }

        fun insertV3Draft(db: SQLiteDatabase, id: String, workId: String) {
            val cv = ContentValues().apply {
                put("id", id)
                put("workId", workId)
                put("type", "issue")
                put("payload", "{}")
                put("updatedAt", 1L)
            }
            db.insert("local_drafts", null, cv)
        }
    }
}
