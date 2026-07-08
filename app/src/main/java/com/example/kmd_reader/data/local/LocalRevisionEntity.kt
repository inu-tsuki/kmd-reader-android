package com.example.kmd_reader.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地提交（commit 模型）：每次确认的修改产生一个不可变提交；同步 = 推送本地领先的提交到云端。
 * 云端保存作品完整提交历史，仍是权威。R3 只留存储接口 + 播放优先读取最新提交。
 *
 * schema 修订（2026-07-07 决策，r3-local-reader-plan.md §2.7）：
 * 旧 outbox（baseRevisionId/source/label/synced/cloudRevisionId/createdAt/updatedAt）
 * → commit（parentRevisionId/contentHash/sourcePath/storageMode/message/syncState/remoteRevisionId/createdAt）。
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
    val parentRevisionId: String?,    // 父提交 id 或 origin 云端 revisionId；首个提交可为 null
    val contentHash: String,          // source 内容哈希；去重与本地/云端关联用，不作唯一身份
    val sourcePath: String,            // 指向 filesDir/bundles/<bundleId>/revisions/<revId>.kmd 的全量快照
    val storageMode: String,           // 恒 "full"；diff 模型预留位，未来切换不破坏 schema
    val message: String?,              // 提交说明
    val syncState: String,             // "local" = 本地领先 / "synced" = 已推送云端
    val remoteRevisionId: String?,     // 推送成功后云端 revisionId，origin mapping
    val createdAt: Long                 // 提交不可变，无 updatedAt
)