package com.example.kmd_reader.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "script_issues",
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workId"])]
)
data class ScriptIssueEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val severity: String,
    val source: String,
    val location: String,
    val message: String,
    val suggestion: String,
    val syncedAt: Long
)
