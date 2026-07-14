package com.example.kmd_reader.runtime

import kotlinx.coroutines.flow.Flow

/**
 * Host-side boundary for one KMD Web runtime session.
 * Implementations serialize commands and expose runtime callbacks without interpreting KMD semantics.
 */
interface ReaderRuntimeBridge {
    val events: Flow<ReaderRuntimeEvent>

    suspend fun attach()

    /** Declares the next work before asynchronous source loading and invalidates stale queued loads. */
    fun prepareLoad(workId: String) = Unit

    /** Loads one resolved work/source/settings snapshot into the attached runtime. */
    suspend fun load(request: ReaderLoadRequest)

    suspend fun play()

    suspend fun pause()

    suspend fun seek(progress: Float)

    suspend fun setInspectionEnabled(enabled: Boolean)

    suspend fun updateSettings(settings: ReaderSettings)

    fun debugSnapshot(): String? = null

    fun dispose()
}
