package com.example.kmd_reader.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通用草稿缓冲：issue/discussion/review 写到一半的内容。
 * payload 是 JSON 透传，R3 不关心内部结构，各类型自己序列化。
 */
@Entity(
    tableName = "local_drafts",
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
data class LocalDraftEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val type: String,
    val payload: String,
    val updatedAt: Long
)
