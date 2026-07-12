package com.example.kmd_reader.data.preferences

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreferencesTest {
    @Test
    fun invalidFontScaleFallsBackOrClampsToSupportedRange() {
        assertEquals(ReaderPreferences.DEFAULT_FONT_SCALE, ReaderPreferences.normalizedFontScale(Float.NaN), 0f)
        assertEquals(ReaderPreferences.MIN_FONT_SCALE, ReaderPreferences.normalizedFontScale(0.1f), 0f)
        assertEquals(ReaderPreferences.MAX_FONT_SCALE, ReaderPreferences.normalizedFontScale(2f), 0f)
    }

    @Test
    fun inMemoryRepositoryPersistsEachFieldIndependently() = runTest {
        val repository = InMemoryReaderPreferencesRepository()
        repository.setFontScale(1.2f)
        repository.setThemeMode(ThemeMode.Dark)
        repository.setAutoSaveProgress(false)
        repository.setReducedMotion(true)

        val value = repository.preferences.first()
        assertEquals(1.2f, value.fontScale, 0f)
        assertEquals(ThemeMode.Dark, value.themeMode)
        assertFalse(value.autoSaveProgress)
        assertTrue(value.reducedMotion)
    }

    @Test
    fun dataStoreAdapterDecodesDefaultsUnknownThemeAndInvalidFont() {
        val defaults = DataStoreReaderPreferencesRepository.decodePreferences(preferencesOf())
        assertEquals(ReaderPreferences(), defaults)

        val malformed = preferencesOf(
            DataStoreReaderPreferencesRepository.THEME_MODE to "Sepia",
            DataStoreReaderPreferencesRepository.FONT_SCALE to 9f,
            DataStoreReaderPreferencesRepository.AUTO_SAVE_PROGRESS to false,
            DataStoreReaderPreferencesRepository.REDUCED_MOTION to true
        )
        val decoded = DataStoreReaderPreferencesRepository.decodePreferences(malformed)
        assertEquals(ThemeMode.System, decoded.themeMode)
        assertEquals(ReaderPreferences.MAX_FONT_SCALE, decoded.fontScale, 0f)
        assertFalse(decoded.autoSaveProgress)
        assertTrue(decoded.reducedMotion)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun dataStoreAdapterPersistsIndependentFieldsAcrossRecreation() = runTest {
        val file = File.createTempFile("reader-preferences-test-", ".preferences_pb")
        file.delete()
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        try {
            val firstRepository = DataStoreReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
            )
            firstRepository.setFontScale(1.2f)
            firstRepository.setThemeMode(ThemeMode.Dark)
            firstRepository.setAutoSaveProgress(false)
            firstRepository.setReducedMotion(true)

            assertEquals(
                ReaderPreferences(1.2f, ThemeMode.Dark, false, true),
                firstRepository.preferences.first()
            )
            firstScope.cancel()

            val secondScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
            try {
                val recreatedRepository = DataStoreReaderPreferencesRepository(
                    PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file })
                )
                assertEquals(
                    "each edit must survive a new DataStore instance without overwriting other keys",
                    ReaderPreferences(1.2f, ThemeMode.Dark, false, true),
                    recreatedRepository.preferences.first()
                )
            } finally {
                secondScope.cancel()
            }
        } finally {
            firstScope.cancel()
            file.delete()
        }
    }

    @Test
    fun dataStoreAdapterFallsBackToDefaultsOnIOException() = runTest {
        val repository = DataStoreReaderPreferencesRepository(IOExceptionPreferencesDataStore())

        assertEquals(ReaderPreferences(), repository.preferences.first())
    }
}

private class IOExceptionPreferencesDataStore : DataStore<Preferences> {
    override val data = flow<Preferences> { throw IOException("simulated read failure") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        error("updateData is not used by the IOException fallback test")
    }
}
