package com.example.kmd_reader.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.data.repository.RoomLocalLibraryRepository
import com.example.kmd_reader.data.repository.toEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class KmdReaderDatabaseTest {
    private lateinit var database: KmdReaderDatabase
    private lateinit var workDao: WorkDao
    private lateinit var issueDao: ScriptIssueDao
    private lateinit var libraryDao: LocalLibraryDao
    private lateinit var revisionDao: LocalRevisionDao
    private lateinit var draftDao: LocalDraftDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KmdReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workDao = database.workDao()
        issueDao = database.scriptIssueDao()
        libraryDao = database.localLibraryDao()
        revisionDao = database.localRevisionDao()
        draftDao = database.localDraftDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertWorksThenQueryAllReturnsInsertedWorks() = runTest {
        val now = 1_700_000_000_000L

        workDao.upsertAll(MockWorks.works.take(2).map { it.toEntity(now) })

        val works = workDao.getAll()
        assertEquals(2, works.size)
        assertNotNull(workDao.getById("rain-city"))
    }

    @Test
    fun upsertWorkWithSameIdReplacesOldValue() = runTest {
        val work = MockWorks.works.first { it.id == "rain-city" }
        val oldEntity = work.toEntity(syncedAt = 1L)
        val newEntity = oldEntity.copy(title = "雨城慢镜 Revised", syncedAt = 2L)

        workDao.upsert(oldEntity)
        workDao.upsert(newEntity)

        val saved = requireNotNull(workDao.getById("rain-city"))
        assertEquals("雨城慢镜 Revised", saved.title)
        assertEquals(2L, saved.syncedAt)
    }

    @Test
    fun replaceIssuesForWorkClearsOldIssues() = runTest {
        val work = MockWorks.works.first { it.id == "glass-rail" }
        val issues = requireNotNull(MockWorks.issues["glass-rail"])
        workDao.upsert(work.toEntity(syncedAt = 1L))
        issueDao.upsertAll(issues.map { it.toEntity(syncedAt = 1L) })

        issueDao.replaceForWork(
            workId = "glass-rail",
            issues = issues.take(1).map { it.toEntity(syncedAt = 2L) }
        )

        val saved = issueDao.getByWorkId("glass-rail")
        assertEquals(1, saved.size)
        assertEquals(2L, saved.single().syncedAt)
    }

    // ── WorkDao 级联回归（与 LocalLibraryDao Finding 1 同型）──
    // works 是 script_issues 的父表（ON DELETE CASCADE）。旧 WorkDao.upsert 用
    // @Insert(REPLACE)（先删后插），任何 work 字段更新（刷新 syncedAt、改
    // activeRevisionId）都会把该 work 的全部 script_issues 级联抹掉。
    // 改成 @Upsert 后原地更新，work 写入不能抹掉 issue 子表数据。

    @Test
    fun workUpsertDoesNotCascadeDeleteIssues() = runTest {
        val work = MockWorks.works.first { it.id == "glass-rail" }
        val issues = requireNotNull(MockWorks.issues["glass-rail"])
        workDao.upsert(work.toEntity(syncedAt = 1L))
        issueDao.upsertAll(issues.map { it.toEntity(syncedAt = 1L) })

        // 模拟刷新 work 元数据：read-modify-upsert 路径
        val existing = requireNotNull(workDao.getById("glass-rail"))
        workDao.upsert(existing.copy(syncedAt = 2L, title = "玻璃轨道 (已更新)"))

        val savedIssues = issueDao.getByWorkId("glass-rail")
        assertEquals(
            "issues must survive a work upsert; REPLACE would have cascade-deleted them",
            issues.size,
            savedIssues.size
        )
        val savedWork = requireNotNull(workDao.getById("glass-rail"))
        assertEquals(2L, savedWork.syncedAt)
        assertEquals("玻璃轨道 (已更新)", savedWork.title)
    }

    @Test
    fun workUpsertAllDoesNotCascadeDeleteIssues() = runTest {
        val work = MockWorks.works.first { it.id == "glass-rail" }
        val issues = requireNotNull(MockWorks.issues["glass-rail"])
        workDao.upsert(work.toEntity(syncedAt = 1L))
        issueDao.upsertAll(issues.map { it.toEntity(syncedAt = 1L) })

        // 批量刷新：upsertAll 走同样路径
        val existing = requireNotNull(workDao.getById("glass-rail"))
        workDao.upsertAll(listOf(existing.copy(syncedAt = 3L)))

        val savedIssues = issueDao.getByWorkId("glass-rail")
        assertEquals(
            "issues must survive a work upsertAll",
            issues.size,
            savedIssues.size
        )
    }

    // ── local_library ──

    private fun libraryEntry(
        workId: String = "rain-city",
        onShelf: Boolean = true,
        progress: Float = 0f,
        lastReadAt: Long? = null,
        importedAt: Long? = null
    ) = LocalLibraryEntity(
        workId = workId,
        source = "Mock",
        onShelf = onShelf,
        title = "雨城慢镜",
        authorName = "Mira",
        presentationMode = "Stage",
        aspectRatio = "9:16",
        kmdSource = null,
        contentUri = "mock/rain-city.kmd",
        readingProgress = progress,
        readingTimeMs = null,
        readingDurationMs = null,
        lastReadAt = lastReadAt,
        importedAt = importedAt,
        cachedAt = null
    )

    // commit-model revision 构造 helper（§2.7）
    private fun revisionEntity(
        id: String,
        workId: String,
        parentRevisionId: String? = null,
        contentHash: String = "sha256-$id",
        createdAt: Long = 1L,
        syncState: String = "local",
        message: String? = null
    ) = LocalRevisionEntity(
        id = id, workId = workId, parentRevisionId = parentRevisionId,
        contentHash = contentHash,
        sourcePath = "bundles/bundle-uuid-1/revisions/$id.kmd",
        storageMode = "full", message = message, syncState = syncState,
        remoteRevisionId = null, createdAt = createdAt
    )

    @Test
    fun libraryShelfReturnsOnlyOnShelfEntries() = runTest {
        libraryDao.upsert(libraryEntry("rain-city", onShelf = true, importedAt = 100L))
        libraryDao.upsert(libraryEntry("glass-rail", onShelf = false, lastReadAt = 200L))

        val shelf = libraryDao.getShelf()
        assertEquals(1, shelf.size)
        assertEquals("rain-city", shelf.single().workId)
    }

    @Test
    fun libraryHistoryReturnsOnlyReadEntriesOrderedByLastReadAt() = runTest {
        libraryDao.upsert(libraryEntry("rain-city", onShelf = false, lastReadAt = 100L))
        libraryDao.upsert(libraryEntry("glass-rail", onShelf = false, lastReadAt = 300L))
        libraryDao.upsert(libraryEntry("star-manual", onShelf = false, lastReadAt = null))

        val history = libraryDao.getHistory()
        assertEquals(2, history.size)
        assertEquals("glass-rail", history.first().workId)
    }

    @Test
    fun libraryUpdateProgressReplacesExistingEntry() = runTest {
        libraryDao.upsert(libraryEntry("rain-city", progress = 0f))
        val existing = requireNotNull(libraryDao.getByWorkId("rain-city"))
        libraryDao.upsert(existing.copy(readingProgress = 0.5f, readingTimeMs = 2500L, lastReadAt = 999L))

        val saved = requireNotNull(libraryDao.getByWorkId("rain-city"))
        assertEquals(0.5f, saved.readingProgress)
        assertEquals(999L, saved.lastReadAt)
    }

    // ── local_revisions ──

    @Test
    fun revisionGetLatestReturnsNewestCommit() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        revisionDao.insert(revisionEntity(id = "rev-1", workId = "rain-city", createdAt = 1L))
        revisionDao.insert(revisionEntity(id = "rev-2", workId = "rain-city", createdAt = 5L))

        val active = requireNotNull(revisionDao.getLatestRevision("rain-city"))
        assertEquals("rev-2", active.id)
    }

    @Test
    fun revisionCascadeDeleteWhenLibraryEntryRemoved() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        revisionDao.insert(revisionEntity(id = "rev-1", workId = "rain-city"))

        libraryDao.deleteByWorkId("rain-city")

        assertNull(revisionDao.getLatestRevision("rain-city"))
    }

    // ── local_drafts ──

    @Test
    fun draftGetByWorkIdAndTypeFiltersCorrectly() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        draftDao.upsert(LocalDraftEntity(id = "d1", workId = "rain-city", type = "issue", payload = "{}", updatedAt = 1L))
        draftDao.upsert(LocalDraftEntity(id = "d2", workId = "rain-city", type = "discussion", payload = "{}", updatedAt = 2L))

        val issues = draftDao.getByWorkIdAndType("rain-city", "issue")
        assertEquals(1, issues.size)
        assertEquals("d1", issues.single().id)
    }

    @Test
    fun draftCascadeDeleteWhenLibraryEntryRemoved() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        draftDao.upsert(LocalDraftEntity(id = "d1", workId = "rain-city", type = "issue", payload = "{}", updatedAt = 1L))

        libraryDao.deleteByWorkId("rain-city")

        assertEquals(0, draftDao.getByWorkId("rain-city").size)
    }

    // ── Finding 1 回归：upsert 不能级联删子表 ──
    // 旧实现用 @Insert(REPLACE)，是先删后插，会触发 local_revisions / local_drafts 的
    // ON DELETE CASCADE。改成 @Upsert 后原地更新，progress/shelf 写入不能抹掉子表数据。

    @Test
    fun libraryUpsertDoesNotCascadeDeleteRevisions() = runTest {
        libraryDao.upsert(libraryEntry("rain-city", progress = 0f))
        revisionDao.insert(revisionEntity(id = "rev-1", workId = "rain-city"))

        // 模拟 updateProgress：read-modify-upsert 路径
        val existing = requireNotNull(libraryDao.getByWorkId("rain-city"))
        libraryDao.upsert(existing.copy(readingProgress = 0.7f, lastReadAt = 999L))

        val savedRevision = revisionDao.getLatestRevision("rain-city")
        assertNotNull("revision must survive a library upsert", savedRevision)
        assertEquals("rev-1", savedRevision?.id)
    }

    @Test
    fun libraryUpsertDoesNotCascadeDeleteDrafts() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        draftDao.upsert(LocalDraftEntity(id = "d1", workId = "rain-city", type = "issue", payload = "{}", updatedAt = 1L))

        // 模拟 setOnShelf：read-modify-upsert 路径
        val existing = requireNotNull(libraryDao.getByWorkId("rain-city"))
        libraryDao.upsert(existing.copy(onShelf = true))

        val savedDrafts = draftDao.getByWorkId("rain-city")
        assertEquals("draft must survive a library upsert", 1, savedDrafts.size)
    }

    // ── Finding 2：revision 写入/清除契约（DAO 层，Repository 同型不重复测） ──

    @Test
    fun revisionUpsertThenClearForWorkEmptiesRevisions() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        revisionDao.insert(revisionEntity(id = "rev-1", workId = "rain-city", message = "本地改"))
        assertNotNull(revisionDao.getLatestRevision("rain-city"))

        revisionDao.clearForWork("rain-city")

        assertNull(revisionDao.getLatestRevision("rain-city"))
    }

    // append-only 语义（§2.7）：同 id 重复 insert 抛异常，不静默覆写。
    @Test(expected = Exception::class)
    fun revisionInsertDuplicateIdThrows() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        revisionDao.insert(revisionEntity(id = "rev-1", workId = "rain-city", createdAt = 1L))
        // 同 id 再 insert → ABORT，抛 SQLite constraint 异常
        revisionDao.insert(revisionEntity(id = "rev-1", workId = "rain-city", createdAt = 5L))
    }

    // ── F4-rev Room 路径回归：updateProgress(durationMs=null, revisionId=null) 保留已有值 ──
    // 审查指出 F4 只覆盖 InMemory 仓储；此处用真实 Room DB 验证同样的保留语义。

    @Test
    fun roomUpdateProgressPreservesDurationAndRevisionWhenIncomingIsNull() = runTest {
        val repo = RoomLocalLibraryRepository(libraryDao, revisionDao, draftDao)

        // 初始 entry 带 durationMs=2400, activeRevisionId="rev-1"
        libraryDao.upsert(
            libraryEntry("rain-city", progress = 0.3f).copy(
                readingDurationMs = 2400,
                activeRevisionId = "rev-1"
            )
        )
        // 第一次 updateProgress 带 durationMs=2400, revisionId="rev-1"（正常）
        repo.updateProgress("rain-city", 0.5f, 1200, 2400, 1000, "rev-1")
        val after1 = requireNotNull(libraryDao.getByWorkId("rain-city"))
        assertEquals(2400L, after1.readingDurationMs)
        assertEquals("rev-1", after1.activeRevisionId)
        // 第二次 updateProgress 带 durationMs=null, revisionId=null（runtime 未上报）→ 不应覆盖
        repo.updateProgress("rain-city", 0.6f, 1440, null, 2000, null)
        val after2 = requireNotNull(libraryDao.getByWorkId("rain-city"))
        assertEquals(
            "Room updateProgress must preserve readingDurationMs when incoming is null",
            2400L,
            after2.readingDurationMs
        )
        assertEquals(
            "Room updateProgress must preserve activeRevisionId when incoming is null",
            "rev-1",
            after2.activeRevisionId
        )
        assertEquals(0.6f, after2.readingProgress, 0.001f)
    }

    // ── R3-G-rev3：字段级原子 UPDATE，setOnShelf 和 updateProgress 不互相覆盖 ──

    @Test
    fun roomSetOnShelfUpdatesOnlyShelfColumn() = runTest {
        val repo = RoomLocalLibraryRepository(libraryDao, revisionDao, draftDao)
        libraryDao.upsert(libraryEntry("rain-city", onShelf = false, progress = 0f))

        repo.updateProgress("rain-city", 0.5f, null, 2400, 1000, null)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TEMP TRIGGER reject_shelf_progress_columns
            BEFORE UPDATE OF readingProgress, readingTimeMs, readingDurationMs, activeRevisionId, lastReadAt
            ON local_library
            BEGIN
                SELECT RAISE(ABORT, 'setOnShelf touched a progress column');
            END
        """.trimIndent())

        // UPDATE OF checks the SQL SET-column set, even when a value is unchanged.
        // A stale entity copy + upsert therefore trips the trigger; the field-level UPDATE passes.
        repo.setOnShelf("rain-city", true)

        val saved = requireNotNull(libraryDao.getByWorkId("rain-city"))
        assertEquals("setOnShelf must not overwrite progress (atomic field UPDATE)", 0.5f, saved.readingProgress, 0.001f)
        assertNull("readingTimeMs=null is an explicit value and must be persisted", saved.readingTimeMs)
        assertEquals(1000L, saved.lastReadAt)
        assertEquals(true, saved.onShelf)
    }

    @Test
    fun roomUpdateProgressDoesNotTouchShelfColumn() = runTest {
        val repo = RoomLocalLibraryRepository(libraryDao, revisionDao, draftDao)
        libraryDao.upsert(libraryEntry("rain-city", onShelf = false, progress = 0f))

        repo.setOnShelf("rain-city", true)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TEMP TRIGGER reject_progress_shelf_column
            BEFORE UPDATE OF onShelf
            ON local_library
            BEGIN
                SELECT RAISE(ABORT, 'updateProgress touched onShelf');
            END
        """.trimIndent())

        // The old entity-copy upsert includes onShelf in its SET list and must fail here.
        repo.updateProgress("rain-city", 0.5f, 1200, 2400, 1000, null)

        val saved = requireNotNull(libraryDao.getByWorkId("rain-city"))
        assertEquals("updateProgress must not overwrite onShelf (atomic field UPDATE)", true, saved.onShelf)
        assertEquals(0.5f, saved.readingProgress, 0.001f)
    }

    @Test
    fun fieldIsolationTriggerRejectsWholeEntityUpsert() = runTest {
        val existing = libraryEntry("rain-city", onShelf = false, progress = 0f)
        libraryDao.upsert(existing)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TEMP TRIGGER reject_whole_entity_progress_columns
            BEFORE UPDATE OF readingProgress, readingTimeMs, readingDurationMs, activeRevisionId, lastReadAt
            ON local_library
            BEGIN
                SELECT RAISE(ABORT, 'whole-entity update touched a progress column');
            END
        """.trimIndent())

        // Calibrates the UPDATE OF probe against the retired implementation shape.
        val failure = runCatching {
            libraryDao.upsert(existing.copy(onShelf = true))
        }.exceptionOrNull()
        assertNotNull("whole-entity copy + upsert must trip the field-isolation trigger", failure)
    }

    // R3-G-rev3 DAO 层：updateProgress 和 setOnShelf 的 affected-row count
    @Test
    fun daoUpdateProgressReturnsAffectedRowCount() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        val rows = libraryDao.updateProgress("rain-city", 0.5f, 1000, 2000, 500, null)
        assertEquals("updateProgress should return 1 for existing row", 1, rows)
    }

    @Test
    fun daoUpdateProgressReturnsZeroForMissingRow() = runTest {
        val rows = libraryDao.updateProgress("nonexistent", 0.5f, 1000, 2000, 500, null)
        assertEquals("updateProgress should return 0 for missing row", 0, rows)
    }

    @Test
    fun daoSetOnShelfReturnsAffectedRowCount() = runTest {
        libraryDao.upsert(libraryEntry("rain-city", onShelf = false))
        val rows = libraryDao.setOnShelf("rain-city", true)
        assertEquals("setOnShelf should return 1 for existing row", 1, rows)
    }

    @Test
    fun daoSetOnShelfReturnsZeroForMissingRow() = runTest {
        val rows = libraryDao.setOnShelf("nonexistent", true)
        assertEquals("setOnShelf should return 0 for missing row", 0, rows)
    }
}
