package com.example.kmd_reader.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地轻度更改（待同步缓冲）：作者纠错 / 审阅者评审改的 .kmd 源文本。
 * 改完同步云端，权威 revision 历史在云端。R3 只留存储接口 + 播放优先读取。
 */
@Entity(
    tableName = "local_revisions",
    foreignKeys = [
        ForeignKey(
            entity = LocalLibraryEntity::class,
            parentColumns = ["workId"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workId"])]
)
data class LocalRevisionEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val baseRevisionId: String,
    val source: String,
    val label: String?,
    val synced: Boolean,
    val cloudRevisionId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
