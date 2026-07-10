package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.bundle.BundleStore
import com.example.kmd_reader.data.bundle.RevisionSourceStore
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.domain.model.WorkSourceType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-D3 回归：LocalAwareWorkRepository 的本地拦截判据只对真正本地导入的 entry 生效，
 * 远程/Mock 作品首次阅读时自动建的纯进度 entry（source=Remote/Mock、kmdSource=null、
 * bundleId=null、onShelf=false）不得覆盖远程 Work/issues（审查报告 Medium 修复）。
 *
 * R3-E 扩展：getWorkSource 播放优先级——最新本地提交 source（§2.7）优先于 kmdSource /
 * BundleStore / delegate。
 */
class LocalAwareWorkRepositoryTest {

    private val remoteWork = MockWorks.works.first { it.id == "glass-rail" }
    private val remoteIssues = MockWorks.issues["glass-rail"].orEmpty()

    // —— 远程/Mock 纯进度 entry：必须走 delegate，不被本地拦截 ——

    @Test
    fun getWork_delegatesForRemoteProgressOnlyEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // 模拟 ViewModel.toLocalLibraryEntry 写入的纯进度 entry（首次阅读远程作品）
        localLibrary.upsertEntry(
            LocalLibraryEntry(
                workId = remoteWork.id,
                source = WorkSourceType.Remote,
                onShelf = false,
                title = remoteWork.title,
                authorName = remoteWork.authorName,
                presentationMode = remoteWork.presentation.mode,
                aspectRatio = remoteWork.presentation.aspectRatio,
                kmdSource = null,
                contentUri = remoteWork.contentUri,
                readingProgress = 0f,
                readingTimeMs = null,
                readingDurationMs = null,
                lastReadAt = null,
                importedAt = null,
                cachedAt = null,
                bundleId = null
            )
        )
        val delegate = RecordingWorkRepository(works = listOf(remoteWork))
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val work = repository.getWork(remoteWork.id, refresh = true)

        assertSame(remoteWork, work)
        assertTrue("delegate.getWork 应被调用一次", delegate.getWorkCalls == 1)
    }

    @Test
    fun listIssues_delegatesForRemoteProgressOnlyEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(remoteProgressEntry())
        val delegate = RecordingWorkRepository(
            works = listOf(remoteWork),
            issues = mapOf(remoteWork.id to remoteIssues)
        )
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val issues = repository.listIssues(remoteWork.id, refresh = true)

        assertEquals(remoteIssues, issues)
        assertTrue("delegate.listIssues 应被调用一次", delegate.listIssuesCalls == 1)
    }

    @Test
    fun getWorkSource_delegatesForRemoteProgressOnlyEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(remoteProgressEntry())
        val delegate = RecordingWorkRepository(works = listOf(remoteWork), source = "remote source")
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val source = repository.getWorkSource(remoteWork.id, refresh = true)

        assertEquals("remote source", source)
        assertTrue("delegate.getWorkSource 应被调用一次", delegate.getWorkSourceCalls == 1)
    }

    // —— 真正本地导入 entry：本地拦截仍生效（回归保护）——

    @Test
    fun getWork_returnsLocalForImportedEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val delegate = RecordingWorkRepository(works = listOf(remoteWork))
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val work = repository.getWork("local-abc12345", refresh = true)

        assertEquals("local-abc12345", work?.id)
        assertEquals(WorkSourceType.Local, work?.sourceType)
        assertEquals("本地导入脚本", work?.title)
        assertEquals(0, delegate.getWorkCalls)
        assertTrue("本地 entry 命中时不应调用 delegate.getWork", delegate.getWorkCalls == 0)
    }

    @Test
    fun listIssues_emptyForImportedEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val delegate = RecordingWorkRepository(
            works = listOf(remoteWork),
            issues = mapOf("local-abc12345" to remoteIssues)
        )
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val issues = repository.listIssues("local-abc12345", refresh = true)

        assertTrue(issues.isEmpty())
        assertEquals(0, delegate.listIssuesCalls)
        assertTrue("本地 entry 命中时不应调用 delegate.listIssues", delegate.listIssuesCalls == 0)
    }

    @Test
    fun getWorkSource_readsKmdSourceForLocalEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val source = repository.getWorkSource("local-abc12345", refresh = true)

        assertEquals("title: 本地导入脚本\n---\nbody", source)
        assertEquals(0, delegate.getWorkSourceCalls)
        assertTrue("kmdSource 快捷路径不应 fallback delegate", delegate.getWorkSourceCalls == 0)
    }

    @Test
    fun getWork_returnsNullWhenNeitherLocalNorDelegate() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val work = repository.getWork("unknown-id", refresh = true)

        assertNull(work)
        assertTrue(delegate.getWorkCalls == 1)
    }

    // —— listWorks：非本地导入的 shelf 条目不得覆盖 delegate 作品 ——

    @Test
    fun listWorks_doesNotOverrideRemoteWorkWithMockShelfEntry() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // 迁移历史场景：source=Mock、onShelf=true、kmdSource=null 的旧书架条目
        // （见 KmdReaderDatabaseMigrationTest）。它能进 getShelf() 但不是本地导入，
        // 不应被 toWork() 映射成 WorkSourceType.Local 空壳覆盖 delegate 的 Mock 作品。
        localLibrary.upsertEntry(
            LocalLibraryEntry(
                workId = "rain-city",
                source = WorkSourceType.Mock,
                onShelf = true,
                title = "雨城慢镜",
                authorName = "Mira",
                presentationMode = PresentationMode.Stage,
                aspectRatio = "9:16",
                kmdSource = null,
                contentUri = "mock/rain-city.kmd",
                readingProgress = 0f,
                readingTimeMs = null,
                readingDurationMs = null,
                lastReadAt = null,
                importedAt = 10L,
                cachedAt = null
            )
        )
        val mockWork = MockWorks.works.first { it.id == "rain-city" }
        val delegate = RecordingWorkRepository(works = listOf(mockWork))
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val works = repository.listWorks(refresh = true)

        val rainCity = works.first { it.id == "rain-city" }
        // 必须仍是 delegate 的 Mock 作品，未被本地空壳覆盖
        assertSame(mockWork, rainCity)
        assertEquals(WorkSourceType.Mock, rainCity.sourceType)
        assertEquals(mockWork.title, rainCity.title)
        assertEquals(mockWork.description, rainCity.description)
        assertTrue("原始 metadata 不应被空壳覆盖", mockWork.description == rainCity.description)
    }

    @Test
    fun listWorks_includesLocalImportedEntryAlongsideDelegate() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val delegate = RecordingWorkRepository(works = listOf(remoteWork))
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val works = repository.listWorks(refresh = true)

        val ids = works.map { it.id }
        assertTrue("本地导入作品应出现", "local-abc12345" in ids)
        assertTrue("远程作品应保留", remoteWork.id in ids)
        val localWork = works.first { it.id == "local-abc12345" }
        assertEquals(WorkSourceType.Local, localWork.sourceType)
        val remoteInList = works.first { it.id == remoteWork.id }
        assertSame(remoteWork, remoteInList)
        assertTrue("远程作品未被本地覆盖", remoteWork === remoteInList)
    }

    // —— R3-D4：bundle 作品 assetManifest + fonts 透传 + baseUrl 改写 ——

    @Test
    fun getWork_bundleEntryRewritesBaseUrlAndPassesFonts() = runTest {
        val bid = "bundle-with-fonts"
        val store = buildBundleStoreWithManifest(bid)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(bundleImportedEntry(bid))
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary, bundleStore = store)

        val work = repository.getWork(bid, refresh = true)

        assertEquals(bid, work?.id)
        val manifest = work?.assetManifest
        assertTrue("bundle 作品应有 assetManifest", manifest != null)
        assertEquals("https://kmd-reader-assets.local/$bid/", manifest?.baseUrl)
        assertEquals(1, manifest?.fonts?.size)
        assertEquals("titlefont", manifest?.fonts?.first()?.family)
        assertEquals("assets/fonts/title.woff2", manifest?.fonts?.first()?.url)
        assertEquals(1, manifest?.assets?.size)
        assertEquals("assets/bg.png", manifest?.assets?.get("bg")?.url)
    }

    @Test
    fun getWork_plainKmdEntryHasNullAssetManifest() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val work = repository.getWork("local-abc12345", refresh = true)

        // 裸 .kmd：bundleId=null，无 assets
        assertEquals(null, work?.assetManifest)
    }

    // —— R3-E：getWorkSource 播放优先级（最新本地提交 → kmdSource/bundleStore → delegate）——

    @Test
    fun getWorkSource_prefersLatestRevisionOverKmdSource() = runTest {
        val filesDir = Files.createTempDirectory("lawrt-rev").toFile()
        val revStore = RevisionSourceStore(filesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        // 写一条本地提交，source 与 kmdSource 不同
        val workKey = com.example.kmd_reader.data.bundle.workKey("local-abc12345")
        val sourcePath = revStore.plainKmdRevisionPath(workKey, "rev-local-1")
        revStore.writeSource(sourcePath, "title: 提交版本\n---\ncommitted body")
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-local-1",
                workId = "local-abc12345",
                parentRevisionId = null,
                contentHash = "hash-1",
                sourcePath = sourcePath,
                storageMode = RevisionStorageMode.FULL,
                message = "首次提交",
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 10L
            )
        )
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            revisionSourceStore = revStore
        )

        val source = repository.getWorkSource("local-abc12345", refresh = true)

        assertEquals("title: 提交版本\n---\ncommitted body", source)
        assertEquals(0, delegate.getWorkSourceCalls)
        assertTrue("有提交时应读 revision source，不 fallback delegate", delegate.getWorkSourceCalls == 0)
    }

    @Test
    fun getWorkSource_fallsBackToKmdSourceWhenRevisionFileMissing() = runTest {
        val filesDir = Files.createTempDirectory("lawrt-rev2").toFile()
        val revStore = RevisionSourceStore(filesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        // 有 revision 记录但 source 文件不存在（bundle 被删/文件损坏）
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-gone",
                workId = "local-abc12345",
                parentRevisionId = null,
                contentHash = "hash-gone",
                sourcePath = revStore.plainKmdRevisionPath(
                    com.example.kmd_reader.data.bundle.workKey("local-abc12345"), "rev-gone"
                ),
                storageMode = RevisionStorageMode.FULL,
                message = null,
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 10L
            )
        )
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            revisionSourceStore = revStore
        )

        val source = repository.getWorkSource("local-abc12345", refresh = true)

        // revision 文件丢失 → fallback 到 kmdSource 快捷路径
        assertEquals("title: 本地导入脚本\n---\nbody", source)
        assertEquals(0, delegate.getWorkSourceCalls)
    }

    @Test
    fun getWorkSource_prefersRevisionOverBundleStoreSource() = runTest {
        val bid = "bundle-rev-test"
        val store = buildBundleStoreWithManifest(bid)
        val revFilesDir = Files.createTempDirectory("lawrt-bundlerev").toFile()
        val revStore = RevisionSourceStore(revFilesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(bundleImportedEntry(bid))
        // 写一条本地提交，source 与 bundle entry source 不同
        val sourcePath = revStore.bundleRevisionPath(bid, "rev-bundle-1")
        revStore.writeSource(sourcePath, "title: bundle 提交版本\n---\ncommitted")
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-bundle-1",
                workId = bid,
                parentRevisionId = null,
                contentHash = "hash-b-1",
                sourcePath = sourcePath,
                storageMode = RevisionStorageMode.FULL,
                message = "bundle 提交",
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 10L
            )
        )
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            bundleStore = store, revisionSourceStore = revStore
        )

        val source = repository.getWorkSource(bid, refresh = true)

        assertEquals("title: bundle 提交版本\n---\ncommitted", source)
        assertEquals(0, delegate.getWorkSourceCalls)
    }

    // —— R3-E 审查修复：版本身份一致（toWork 投影 latest revision）——

    @Test
    fun getWork_activeRevisionIdMatchesLatestRevision() = runTest {
        val filesDir = Files.createTempDirectory("lawrt-identity").toFile()
        val revStore = RevisionSourceStore(filesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        // 写一条本地提交——toWork 应把 revision.id 投影到 Work.script
        val workKey = com.example.kmd_reader.data.bundle.workKey("local-abc12345")
        val sourcePath = revStore.plainKmdRevisionPath(workKey, "rev-identity-1")
        revStore.writeSource(sourcePath, "title: 身份一致\n---\ncommitted")
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-identity-1",
                workId = "local-abc12345",
                parentRevisionId = null,
                contentHash = "hash-identity-1",
                sourcePath = sourcePath,
                storageMode = RevisionStorageMode.FULL,
                message = "身份测试提交",
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 100L
            )
        )
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            revisionSourceStore = revStore
        )

        val work = repository.getWork("local-abc12345", refresh = true)

        assertEquals("rev-identity-1", work?.script?.activeRevisionId)
        assertEquals("rev-identity-1", work?.script?.activeRevision?.id)
        assertEquals("hash-identity-1", work?.script?.activeRevision?.contentHash)
        assertEquals("身份测试提交", work?.script?.activeRevision?.label)
        assertEquals("100", work?.script?.activeRevision?.createdAt)
    }

    @Test
    fun getWork_fallsBackToEntryRevisionIdWhenNoRevision() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(delegate = delegate, localLibrary = localLibrary)

        val work = repository.getWork("local-abc12345", refresh = true)

        // 无 revision：回退 entry.activeRevisionId ?: "local"（localImportedEntry 的 activeRevisionId=null）
        assertEquals("local", work?.script?.activeRevisionId)
        assertEquals("local", work?.script?.activeRevision?.id)
        assertEquals("deadbeef", work?.script?.activeRevision?.contentHash)
    }

    @Test
    fun listWorks_activeRevisionIdMatchesLatestRevision() = runTest {
        val filesDir = Files.createTempDirectory("lawrt-listid").toFile()
        val revStore = RevisionSourceStore(filesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val workKey = com.example.kmd_reader.data.bundle.workKey("local-abc12345")
        val sourcePath = revStore.plainKmdRevisionPath(workKey, "rev-list-1")
        revStore.writeSource(sourcePath, "title: list 测试\n---\ncommitted")
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-list-1",
                workId = "local-abc12345",
                parentRevisionId = null,
                contentHash = "hash-list-1",
                sourcePath = sourcePath,
                storageMode = RevisionStorageMode.FULL,
                message = null,
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 50L
            )
        )
        val delegate = RecordingWorkRepository(works = listOf(remoteWork))
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            revisionSourceStore = revStore
        )

        val works = repository.listWorks(refresh = true)
        val localWork = works.first { it.id == "local-abc12345" }

        assertEquals("rev-list-1", localWork.script.activeRevisionId)
        assertEquals("rev-list-1", localWork.script.activeRevision.id)
        assertEquals("hash-list-1", localWork.script.activeRevision.contentHash)
    }

    @Test
    fun getWork_getWorkSource_identityConsistency() = runTest {
        val filesDir = Files.createTempDirectory("lawrt-consistency").toFile()
        val revStore = RevisionSourceStore(filesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        val workKey = com.example.kmd_reader.data.bundle.workKey("local-abc12345")
        val sourcePath = revStore.plainKmdRevisionPath(workKey, "rev-consistency-1")
        val committedSource = "title: 一致性\n---\nthis is the played content"
        revStore.writeSource(sourcePath, committedSource)
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-consistency-1",
                workId = "local-abc12345",
                parentRevisionId = null,
                contentHash = "hash-consistency",
                sourcePath = sourcePath,
                storageMode = RevisionStorageMode.FULL,
                message = "一致性测试",
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 200L
            )
        )
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            revisionSourceStore = revStore
        )

        val work = repository.getWork("local-abc12345", refresh = true)
        val source = repository.getWorkSource("local-abc12345", refresh = true)

        // 核心断言：Work.script.activeRevisionId 必须与实际播放的 revision id 一致
        assertEquals("rev-consistency-1", work?.script?.activeRevisionId)
        // getWorkSource 返回的内容必须来自这条 revision 的 source snapshot
        assertEquals(committedSource, source)
        // contentHash 也一致
        assertEquals("hash-consistency", work?.script?.activeRevision?.contentHash)
    }

    // —— R3-E 二轮审查：missing source fallback 边界一致 ——

    @Test
    fun getWork_doesNotProjectRevisionWhenSourceFileMissing() = runTest {
        val filesDir = Files.createTempDirectory("lawrt-missing").toFile()
        val revStore = RevisionSourceStore(filesDir)
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(localImportedEntry())
        // 有 revision 记录但 source 文件不存在（bundle 被删/文件损坏）
        localLibrary.saveRevision(
            LocalRevision(
                id = "rev-missing",
                workId = "local-abc12345",
                parentRevisionId = null,
                contentHash = "hash-missing",
                sourcePath = revStore.plainKmdRevisionPath(
                    com.example.kmd_reader.data.bundle.workKey("local-abc12345"), "rev-missing"
                ),
                storageMode = RevisionStorageMode.FULL,
                message = "缺文件提交",
                syncState = RevisionSyncState.LOCAL,
                remoteRevisionId = null,
                createdAt = 10L
            )
        )
        val delegate = RecordingWorkRepository(works = emptyList())
        val repository = LocalAwareWorkRepository(
            delegate = delegate, localLibrary = localLibrary,
            revisionSourceStore = revStore
        )

        val work = repository.getWork("local-abc12345", refresh = true)
        val source = repository.getWorkSource("local-abc12345", refresh = true)

        // toWork 不投影缺文件的 revision → 回退 entry 指针（localImportedEntry activeRevisionId=null → "local"）
        assertEquals("local", work?.script?.activeRevisionId)
        assertEquals("local", work?.script?.activeRevision?.id)
        // getWorkSource fallback 到 kmdSource（localImportedEntry 的 kmdSource）
        assertEquals("title: 本地导入脚本\n---\nbody", source)
        // 核心一致：Work.script 标的 revision id 与实际播放的 source 不脱节——
        // 播放的是 kmdSource（entry 级），Work.script 也标 entry 级 "local"，而非缺文件的 "rev-missing"
        assertTrue(
            "不应标缺文件的 revision id（${work?.script?.activeRevisionId}），应回退 entry 指针",
            work?.script?.activeRevisionId != "rev-missing"
        )
    }

    // —— fixtures ——

    private fun remoteProgressEntry(workId: String = remoteWork.id) = LocalLibraryEntry(
        workId = workId,
        source = WorkSourceType.Remote,
        onShelf = false,
        title = remoteWork.title,
        authorName = remoteWork.authorName,
        presentationMode = remoteWork.presentation.mode,
        aspectRatio = remoteWork.presentation.aspectRatio,
        kmdSource = null,
        contentUri = remoteWork.contentUri,
        readingProgress = 0f,
        readingTimeMs = null,
        readingDurationMs = null,
        lastReadAt = null,
        importedAt = null,
        cachedAt = null,
        bundleId = null
    )

    /** 模拟 importPlainKmd 写入的本地导入条目：source=Local、kmdSource 非空、onShelf=true。 */
    private fun localImportedEntry() = LocalLibraryEntry(
        workId = "local-abc12345",
        source = WorkSourceType.Local,
        onShelf = true,
        title = "本地导入脚本",
        authorName = "Local Author",
        presentationMode = PresentationMode.Stage,
        aspectRatio = "16:9",
        kmdSource = "title: 本地导入脚本\n---\nbody",
        contentUri = "content://uri",
        readingProgress = 0f,
        readingTimeMs = null,
        readingDurationMs = null,
        lastReadAt = null,
        importedAt = 1L,
        cachedAt = 1L,
        bundleId = null,
        activeRevisionId = null,
        contentHash = "deadbeef",
        originWorkId = null
    )

    /** 模拟 importKmdwork 写入的 bundle 导入条目：source=Local、bundleId 非空、kmdSource=null。 */
    private fun bundleImportedEntry(bid: String) = LocalLibraryEntry(
        workId = bid,
        source = WorkSourceType.Local,
        onShelf = true,
        title = "Bundle 作品",
        authorName = "Bundle Author",
        presentationMode = PresentationMode.Stage,
        aspectRatio = "16:9",
        kmdSource = null,
        contentUri = "content://bundle",
        readingProgress = 0f,
        readingTimeMs = null,
        readingDurationMs = null,
        lastReadAt = null,
        importedAt = 1L,
        cachedAt = 1L,
        bundleId = bid,
        activeRevisionId = "rev-local",
        contentHash = "cafe1234",
        originWorkId = "origin-1"
    )
}

/** 记录调用次数的 WorkRepository 假实现，用于断言 delegate 是否被命中。 */
private class RecordingWorkRepository(
    private val works: List<Work> = emptyList(),
    private val issues: Map<String, List<ScriptIssue>> = emptyMap(),
    private val source: String? = null
) : WorkRepository {
    var getWorkCalls = 0; private set
    var listIssuesCalls = 0; private set
    var getWorkSourceCalls = 0; private set

    override suspend fun listWorks(refresh: Boolean): List<Work> = works

    override suspend fun getWork(id: String, refresh: Boolean): Work? {
        getWorkCalls += 1
        return works.firstOrNull { it.id == id }
    }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> {
        listIssuesCalls += 1
        return issues[workId].orEmpty()
    }

    override suspend fun getWorkSource(workId: String, refresh: Boolean): String? {
        getWorkSourceCalls += 1
        return source
    }
}

/**
 * 构造真实 BundleStore（tmp dir）并写入 work.json，含 assetManifest（fonts + assets）。
 * 纯 JVM，不依赖 Android Context。readManifest 用 kotlinx.serialization + ignoreUnknownKeys。
 */
private fun buildBundleStoreWithManifest(bid: String): BundleStore {
    val bundlesDir = Files.createTempDirectory("lawrt-bundles").toFile()
    val cacheDir = Files.createTempDirectory("lawrt-cache").toFile()
    val bundleDir = File(bundlesDir, bid).apply { mkdirs() }
    // work.json 对齐 BundleManifest 序列化形状（BundleManifest.kt）。
    File(bundleDir, "work.json").writeText(
        """
        {
          "formatVersion": 1,
          "bundleId": "$bid",
          "entry": "scripts/main.kmd",
          "assetManifest": {
            "baseUrl": "original/bundle/base",
            "fonts": [
              {"family": "titlefont", "url": "assets/fonts/title.woff2", "weight": "700", "style": null}
            ],
            "assets": {
              "bg": {"url": "assets/bg.png", "type": "image"}
            }
          }
        }
        """.trimIndent()
    )
    File(bundleDir, "scripts").mkdirs()
    File(bundleDir, "scripts/main.kmd").writeText("title: test")
    return BundleStore(bundlesDir = bundlesDir, cacheDir = cacheDir)
}