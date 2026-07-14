package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.local.LocalDraftDao
import com.example.kmd_reader.data.local.LocalDraftEntity
import com.example.kmd_reader.data.local.LocalLibraryDao
import com.example.kmd_reader.data.local.LocalLibraryEntity
import com.example.kmd_reader.data.local.LocalRevisionDao
import com.example.kmd_reader.data.local.LocalRevisionEntity
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.WorkSourceType

/**
 * 作品在本地是什么状态：进度、收藏、导入元数据。书架和阅读历史的唯一数据载体。
 */
data class LocalLibraryEntry(
    val workId: String,
    val source: WorkSourceType,
    val onShelf: Boolean,
    val title: String,
    val authorName: String,
    val presentationMode: PresentationMode,
    val aspectRatio: String,
    val kmdSource: String?,
    val contentUri: String,
    val readingProgress: Float,
    val readingTimeMs: Long?,
    val readingDurationMs: Long?,
    val lastReadAt: Long?,
    val importedAt: Long?,
    val cachedAt: Long?,
    // R3-D1 指针字段（spike §2.5；只加指针/快照，不内联全量——C4 红线）：
    val bundleId: String? = null,
    val activeRevisionId: String? = null,
    val contentHash: String? = null,
    val originWorkId: String? = null
)

/**
 * 本地提交（commit 模型，r3-local-reader-plan.md §2.7）。
 * 提交不可变：修改即新提交，不原地更新。同步 = 推送本地领先的提交到云端。
 */
data class LocalRevision(
    val id: String,
    val workId: String,
    val parentRevisionId: String?,
    val contentHash: String,
    val sourcePath: String,
    val storageMode: String,
    val message: String?,
    val syncState: String,
    val remoteRevisionId: String?,
    val createdAt: Long
)

/** 通用草稿（issue/discussion/review 写到一半的内容）。 */
data class LocalDraft(
    val id: String,
    val workId: String,
    val type: String,
    val payload: String,
    val updatedAt: Long
)

/** 草稿类型常量，避免裸字符串散落。R3-C 消费 ISSUE；R4 消费 DISCUSSION/REVIEW。 */
object LocalDraftTypes {
    const val ISSUE = "issue"
    const val DISCUSSION = "discussion"
    const val REVIEW = "review"
}

/** 提交同步状态常量。 */
object RevisionSyncState {
    const val LOCAL = "local"
    const val SYNCED = "synced"
}

/** 提交存储模式常量。R3 恒 "full"；diff 模型预留位。 */
object RevisionStorageMode {
    const val FULL = "full"
}

/** Persistence boundary for the local library, immutable revisions, and draft buffers. */
interface LocalLibraryRepository {
    // 作品级
    suspend fun getEntry(workId: String): LocalLibraryEntry?
    suspend fun getShelf(): List<LocalLibraryEntry>
    suspend fun getHistory(): List<LocalLibraryEntry>
    suspend fun upsertEntry(entry: LocalLibraryEntry)
    suspend fun updateProgress(workId: String, progress: Float, timeMs: Long?, durationMs: Long?, now: Long, revisionId: String? = null)
    suspend fun setOnShelf(workId: String, onShelf: Boolean)
    suspend fun removeEntry(workId: String)

    // 提交级（commit 模型，§2.7）。append-only：调用方负责生成新 id，不覆写已有提交。
    suspend fun getLatestRevision(workId: String): LocalRevision?
    suspend fun findRevisionByContentHash(workId: String, contentHash: String): LocalRevision?
    suspend fun getRevisionsForWork(workId: String): List<LocalRevision>
    suspend fun saveRevision(revision: LocalRevision)
    suspend fun clearRevisionsForWork(workId: String)

    // 草稿级
    suspend fun getDrafts(workId: String): List<LocalDraft>
    suspend fun getDraftsByType(workId: String, type: String): List<LocalDraft>
    suspend fun saveDraft(draft: LocalDraft)
    suspend fun deleteDraft(id: String)
}

class RoomLocalLibraryRepository(
    private val libraryDao: LocalLibraryDao,
    private val revisionDao: LocalRevisionDao,
    private val draftDao: LocalDraftDao,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : LocalLibraryRepository {

    override suspend fun getEntry(workId: String): LocalLibraryEntry? =
        libraryDao.getByWorkId(workId)?.toDomain()

    override suspend fun getShelf(): List<LocalLibraryEntry> =
        libraryDao.getShelf().map { it.toDomain() }

    override suspend fun getHistory(): List<LocalLibraryEntry> =
        libraryDao.getHistory().map { it.toDomain() }

    override suspend fun upsertEntry(entry: LocalLibraryEntry) {
        libraryDao.upsert(entry.toEntity())
    }

    // R3-G-rev3：字段级原子 UPDATE，不再 read-modify-write。
    // 旧实现用 getByWorkId → copy → upsert，在 toggle 和 updateProgress 并发时互相覆盖。
    // durationMs/revisionId=null 时 DAO 的 COALESCE 保留已有值（F4/F4-rev 语义）。
    override suspend fun updateProgress(
        workId: String,
        progress: Float,
        timeMs: Long?,
        durationMs: Long?,
        now: Long,
        revisionId: String?
    ) {
        // 0 rows preserves the repository contract: progress for a missing entry is a no-op.
        libraryDao.updateProgress(workId, progress, timeMs, durationMs, now, revisionId)
    }

    // R3-G-rev3：字段级原子 UPDATE，只更新 onShelf，不覆盖 progress/time 等字段。
    override suspend fun setOnShelf(workId: String, onShelf: Boolean) {
        // 0 rows preserves the repository contract: callers create missing entries explicitly.
        libraryDao.setOnShelf(workId, onShelf)
    }

    override suspend fun removeEntry(workId: String) {
        libraryDao.deleteByWorkId(workId)
    }

    override suspend fun getLatestRevision(workId: String): LocalRevision? =
        revisionDao.getLatestRevision(workId)?.toDomain()

    override suspend fun findRevisionByContentHash(workId: String, contentHash: String): LocalRevision? =
        revisionDao.findByContentHash(workId, contentHash)?.toDomain()

    override suspend fun getRevisionsForWork(workId: String): List<LocalRevision> =
        revisionDao.getRevisionsForWork(workId).map { it.toDomain() }

    // append-only：DAO ABORT 保证同 id 重复 insert 抛异常。调用方应写新 id。
    // 不覆写 createdAt——提交时间由调用方提供。
    override suspend fun saveRevision(revision: LocalRevision) {
        revisionDao.insert(revision.toEntity())
    }

    override suspend fun clearRevisionsForWork(workId: String) {
        revisionDao.clearForWork(workId)
    }

    override suspend fun getDrafts(workId: String): List<LocalDraft> =
        draftDao.getByWorkId(workId).map { it.toDomain() }

    override suspend fun getDraftsByType(workId: String, type: String): List<LocalDraft> =
        draftDao.getByWorkIdAndType(workId, type).map { it.toDomain() }

    override suspend fun saveDraft(draft: LocalDraft) {
        draftDao.upsert(draft.copy(updatedAt = nowMillis()).toEntity())
    }

    override suspend fun deleteDraft(id: String) {
        draftDao.deleteById(id)
    }
}

/** 测试与预览用：避免未注入 Room 时破坏 ViewModel 默认参数。 */
class InMemoryLocalLibraryRepository(
    private val nowMillis: () -> Long = System::currentTimeMillis
) : LocalLibraryRepository {
    private val entries = mutableMapOf<String, LocalLibraryEntry>()
    private val revisions = mutableListOf<LocalRevision>()
    private val drafts = mutableListOf<LocalDraft>()

    override suspend fun getEntry(workId: String): LocalLibraryEntry? = entries[workId]
    override suspend fun getShelf(): List<LocalLibraryEntry> =
        entries.values.filter { it.onShelf }.sortedByDescending { it.lastReadAt ?: it.importedAt ?: 0L }
    override suspend fun getHistory(): List<LocalLibraryEntry> =
        entries.values.filter { it.lastReadAt != null }.sortedByDescending { it.lastReadAt!! }

    override suspend fun upsertEntry(entry: LocalLibraryEntry) {
        entries[entry.workId] = entry
    }

    override suspend fun updateProgress(
        workId: String,
        progress: Float,
        timeMs: Long?,
        durationMs: Long?,
        now: Long,
        revisionId: String?
    ) {
        entries[workId]?.let {
            // F4：同 Room 实现——durationMs=null 不清除已有基准。
            // F4-rev：revisionId 同策略。
            entries[workId] = it.copy(
                readingProgress = progress,
                readingTimeMs = timeMs,
                readingDurationMs = durationMs ?: it.readingDurationMs,
                activeRevisionId = revisionId ?: it.activeRevisionId,
                lastReadAt = now
            )
        }
    }

    override suspend fun setOnShelf(workId: String, onShelf: Boolean) {
        entries[workId]?.let { entries[workId] = it.copy(onShelf = onShelf) }
    }

    override suspend fun removeEntry(workId: String) {
        entries.remove(workId)
        revisions.removeAll { it.workId == workId }
        drafts.removeAll { it.workId == workId }
    }

    override suspend fun getLatestRevision(workId: String): LocalRevision? =
        revisions.filter { it.workId == workId }.maxByOrNull { it.createdAt }

    override suspend fun findRevisionByContentHash(workId: String, contentHash: String): LocalRevision? =
        revisions.find { it.workId == workId && it.contentHash == contentHash }

    override suspend fun getRevisionsForWork(workId: String): List<LocalRevision> =
        revisions.filter { it.workId == workId }.sortedByDescending { it.createdAt }

    // append-only 模拟 DAO ABORT：同 id 重复写入抛异常，不静默覆写。
    // 内存层用 require 兜底（Room 层由 DB ABORT 保证），保持单条语义。
    override suspend fun saveRevision(revision: LocalRevision) {
        require(revisions.none { it.id == revision.id }) {
          "revision id conflict (append-only): ${revision.id}"
        }
        revisions.add(revision)
    }

    override suspend fun clearRevisionsForWork(workId: String) {
        revisions.removeAll { it.workId == workId }
    }

    override suspend fun getDrafts(workId: String): List<LocalDraft> =
        drafts.filter { it.workId == workId }.sortedByDescending { it.updatedAt }
    override suspend fun getDraftsByType(workId: String, type: String): List<LocalDraft> =
        drafts.filter { it.workId == workId && it.type == type }.sortedByDescending { it.updatedAt }

    override suspend fun saveDraft(draft: LocalDraft) {
        drafts.removeAll { it.id == draft.id }
        drafts.add(draft.copy(updatedAt = nowMillis()))
    }

    override suspend fun deleteDraft(id: String) {
        drafts.removeAll { it.id == id }
    }
}

//region Mappers
private fun LocalLibraryEntity.toDomain(): LocalLibraryEntry = LocalLibraryEntry(
    workId = workId,
    source = runCatching { WorkSourceType.valueOf(source) }.getOrDefault(WorkSourceType.Remote),
    onShelf = onShelf,
    title = title,
    authorName = authorName,
    presentationMode = runCatching { PresentationMode.valueOf(presentationMode) }.getOrDefault(PresentationMode.Scroll),
    aspectRatio = aspectRatio,
    kmdSource = kmdSource,
    contentUri = contentUri,
    readingProgress = readingProgress,
    readingTimeMs = readingTimeMs,
    readingDurationMs = readingDurationMs,
    lastReadAt = lastReadAt,
    importedAt = importedAt,
    cachedAt = cachedAt,
    bundleId = bundleId,
    activeRevisionId = activeRevisionId,
    contentHash = contentHash,
    originWorkId = originWorkId
)

private fun LocalLibraryEntry.toEntity(): LocalLibraryEntity = LocalLibraryEntity(
    workId = workId,
    source = source.name,
    onShelf = onShelf,
    title = title,
    authorName = authorName,
    presentationMode = presentationMode.name,
    aspectRatio = aspectRatio,
    kmdSource = kmdSource,
    contentUri = contentUri,
    readingProgress = readingProgress,
    readingTimeMs = readingTimeMs,
    readingDurationMs = readingDurationMs,
    lastReadAt = lastReadAt,
    importedAt = importedAt,
    cachedAt = cachedAt,
    bundleId = bundleId,
    activeRevisionId = activeRevisionId,
    contentHash = contentHash,
    originWorkId = originWorkId
)

private fun LocalRevisionEntity.toDomain(): LocalRevision = LocalRevision(
    id = id, workId = workId, parentRevisionId = parentRevisionId,
    contentHash = contentHash, sourcePath = sourcePath, storageMode = storageMode,
    message = message, syncState = syncState, remoteRevisionId = remoteRevisionId,
    createdAt = createdAt
)

private fun LocalRevision.toEntity(): LocalRevisionEntity = LocalRevisionEntity(
    id = id, workId = workId, parentRevisionId = parentRevisionId,
    contentHash = contentHash, sourcePath = sourcePath, storageMode = storageMode,
    message = message, syncState = syncState, remoteRevisionId = remoteRevisionId,
    createdAt = createdAt
)

private fun LocalDraftEntity.toDomain(): LocalDraft = LocalDraft(
    id = id, workId = workId, type = type, payload = payload, updatedAt = updatedAt
)

private fun LocalDraft.toEntity(): LocalDraftEntity = LocalDraftEntity(
    id = id, workId = workId, type = type, payload = payload, updatedAt = updatedAt
)
//endregion
