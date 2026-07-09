package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.bundle.BundleStore
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