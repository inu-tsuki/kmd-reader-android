package com.example.kmd_reader.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kmd_reader.data.mock.MockWorks
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
    fun revisionGetActiveReturnsLatestUnsynced() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        revisionDao.upsert(
            LocalRevisionEntity(
                id = "rev-1", workId = "rain-city", baseRevisionId = "base",
                source = "---\nmode: stage\n---\nold", label = null,
                synced = true, cloudRevisionId = "cloud-1", createdAt = 1L, updatedAt = 1L
            )
        )
        revisionDao.upsert(
            LocalRevisionEntity(
                id = "rev-2", workId = "rain-city", baseRevisionId = "base",
                source = "---\nmode: stage\n---\nnew", label = null,
                synced = false, cloudRevisionId = null, createdAt = 2L, updatedAt = 5L
            )
        )

        val active = requireNotNull(revisionDao.getActiveLocalRevision("rain-city"))
        assertEquals("rev-2", active.id)
        assertEquals(false, active.synced)
    }

    @Test
    fun revisionCascadeDeleteWhenLibraryEntryRemoved() = runTest {
        libraryDao.upsert(libraryEntry("rain-city"))
        revisionDao.upsert(
            LocalRevisionEntity(
                id = "rev-1", workId = "rain-city", baseRevisionId = "base",
                source = "src", label = null, synced = false, cloudRevisionId = null,
                createdAt = 1L, updatedAt = 1L
            )
        )

        libraryDao.deleteByWorkId("rain-city")

        assertNull(revisionDao.getActiveLocalRevision("rain-city"))
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
        revisionDao.upsert(
            LocalRevisionEntity(
                id = "rev-1", workId = "rain-city", baseRevisionId = "base",
                source = "src", label = null, synced = false, cloudRevisionId = null,
                createdAt = 1L, updatedAt = 1L
            )
        )

        // 模拟 updateProgress：read-modify-upsert 路径
        val existing = requireNotNull(libraryDao.getByWorkId("rain-city"))
        libraryDao.upsert(existing.copy(readingProgress = 0.7f, lastReadAt = 999L))

        val savedRevision = revisionDao.getActiveLocalRevision("rain-city")
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
        revisionDao.upsert(
            LocalRevisionEntity(
                id = "rev-1", workId = "rain-city", baseRevisionId = "base",
                source = "src", label = "本地改", synced = false, cloudRevisionId = null,
                createdAt = 1L, updatedAt = 1L
            )
        )
        assertNotNull(revisionDao.getActiveLocalRevision("rain-city"))

        revisionDao.clearForWork("rain-city")

        assertNull(revisionDao.getActiveLocalRevision("rain-city"))
    }
}
