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

        // 2) 以同样的 db 文件为底，跑真实 MIGRATION_2_3，再以 v3 Room 库打开。
        //    不用 .use{}：Room DAO 是 suspend，use 的 lambda 非挂起，无法直接调用。
        val migrated = Room.databaseBuilder(
            context.applicationContext,
            KmdReaderDatabase::class.java,
            DB_NAME
        )
            .addMigrations(KmdReaderDatabase.MIGRATION_2_3)
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
            revisionDao.upsert(
                LocalRevisionEntity(
                    id = "rev-1", workId = "rain-city", baseRevisionId = "base",
                    source = "src", label = null, synced = false, cloudRevisionId = null,
                    createdAt = 1L, updatedAt = 1L
                )
            )
            draftDao.upsert(LocalDraftEntity(id = "d1", workId = "rain-city", type = "issue", payload = "{}", updatedAt = 1L))

            assertEquals("rain-city", libraryDao.getByWorkId("rain-city")?.workId)
            assertNotNull(revisionDao.getActiveLocalRevision("rain-city"))
            assertEquals(1, draftDao.getByWorkId("rain-city").size)

            // ── 新表的外键级联在新库上仍然生效 ──
            libraryDao.deleteByWorkId("rain-city")
            assertNull(revisionDao.getActiveLocalRevision("rain-city"))
            assertTrue(draftDao.getByWorkId("rain-city").isEmpty())
        } finally {
            migrated.close()
        }
    }

    @After
    fun cleanup() {
        // 删除遗留的 db 文件，避免 Robolectric 会话间污染。
        context.deleteDatabase(DB_NAME)
    }

    private companion object {
        const val DB_NAME = "kmd-reader-migration-test.db"

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
    }
}
