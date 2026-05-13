package com.example.kmd_reader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "works")
data class WorkEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authorName: String,
    val description: String,
    val tags: String,
    val category: String,
    val sourceType: String,
    val lifecycleStatus: String,
    val presentationMode: String,
    val orientationHint: String,
    val aspectRatio: String,
    val interactionLevel: String,
    val previewMode: String,
    val contentUri: String,
    val previewUri: String?,
    val estimatedDurationSec: Int,
    val effectIntensity: String,
    val commandCount: Int,
    val externalAssetCount: Int,
    val complexityLevel: String,
    val runtimeVersion: String,
    val commentSummary: String,
    val commentHighlights: String,
    val commentConcerns: String,
    val syncedAt: Long
)
