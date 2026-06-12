package com.example.kmd_reader.runtime

import kotlinx.coroutines.flow.Flow

interface ReaderRuntimeBridge {
    val events: Flow<ReaderRuntimeEvent>

    suspend fun attach()

    fun prepareLoad(workId: String) = Unit

    suspend fun load(request: ReaderLoadRequest)

    suspend fun play()

    suspend fun pause()

    suspend fun seek(progress: Float)

    suspend fun setInspectionEnabled(enabled: Boolean)

    suspend fun updateSettings(settings: ReaderSettings)

    fun debugSnapshot(): String? = null

    fun dispose()
}
