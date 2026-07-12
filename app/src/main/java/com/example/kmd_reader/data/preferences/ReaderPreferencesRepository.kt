package com.example.kmd_reader.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

interface ReaderPreferencesRepository {
    val preferences: Flow<ReaderPreferences>
    suspend fun setFontScale(fontScale: Float)
    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setAutoSaveProgress(enabled: Boolean)
    suspend fun setReducedMotion(enabled: Boolean)
}

private val Context.readerPreferencesDataStore by preferencesDataStore("reader_preferences")

class DataStoreReaderPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) : ReaderPreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.readerPreferencesDataStore)

    override val preferences: Flow<ReaderPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map(::decode)

    override suspend fun setFontScale(fontScale: Float) {
        dataStore.edit { it[FONT_SCALE] = ReaderPreferences.normalizedFontScale(fontScale) }
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = themeMode.name }
    }

    override suspend fun setAutoSaveProgress(enabled: Boolean) {
        dataStore.edit { it[AUTO_SAVE_PROGRESS] = enabled }
    }

    override suspend fun setReducedMotion(enabled: Boolean) {
        dataStore.edit { it[REDUCED_MOTION] = enabled }
    }

    private fun decode(values: Preferences): ReaderPreferences = decodePreferences(values)

    companion object {
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_SAVE_PROGRESS = booleanPreferencesKey("auto_save_progress")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")

        internal fun decodePreferences(values: Preferences): ReaderPreferences = ReaderPreferences(
            fontScale = ReaderPreferences.normalizedFontScale(values[FONT_SCALE] ?: ReaderPreferences.DEFAULT_FONT_SCALE),
            themeMode = values[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System,
            autoSaveProgress = values[AUTO_SAVE_PROGRESS] ?: true,
            reducedMotion = values[REDUCED_MOTION] ?: false
        )
    }
}

/** Lightweight default for previews and JVM ViewModel tests; production injects DataStore. */
class InMemoryReaderPreferencesRepository : ReaderPreferencesRepository {
    private val state = MutableStateFlow(ReaderPreferences())
    override val preferences: Flow<ReaderPreferences> = state
    override suspend fun setFontScale(fontScale: Float) = state.update {
        it.copy(fontScale = ReaderPreferences.normalizedFontScale(fontScale))
    }
    override suspend fun setThemeMode(themeMode: ThemeMode) = state.update { it.copy(themeMode = themeMode) }
    override suspend fun setAutoSaveProgress(enabled: Boolean) = state.update { it.copy(autoSaveProgress = enabled) }
    override suspend fun setReducedMotion(enabled: Boolean) = state.update { it.copy(reducedMotion = enabled) }
}
