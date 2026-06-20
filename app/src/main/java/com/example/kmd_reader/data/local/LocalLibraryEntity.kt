package com.example.kmd_reader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 作品在本地是什么状态：进度、收藏、导入元数据。
 * 自包含（无外键指向 works），避免 mock 作品不在 Room 的崩溃陷阱。
 * 稀疏表：只存用户碰过的作品（导入/收藏/首次阅读触发创建）。
 */
@Entity(tableName = "local_library")
data class LocalLibraryEntity(
    @PrimaryKey val workId: String,
    val source: String,
    val onShelf: Boolean,
    val title: String,
    val authorName: String,
    val presentationMode: String,
    val aspectRatio: String,
    val kmdSource: String?,
    val contentUri: String,
    val readingProgress: Float,
    val readingTimeMs: Long?,
    val readingDurationMs: Long?,
    val lastReadAt: Long?,
    val importedAt: Long?,
    val cachedAt: Long?
)
