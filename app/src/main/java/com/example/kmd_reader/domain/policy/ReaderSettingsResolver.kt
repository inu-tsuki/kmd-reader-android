package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.data.preferences.ReaderPreferences
import com.example.kmd_reader.presentation.ReaderViewportState
import com.example.kmd_reader.runtime.ReaderSettings

/** The only production mapping which combines viewport facts with user preferences. */
object ReaderSettingsResolver {
    fun resolve(viewport: ReaderViewportState, preferences: ReaderPreferences): ReaderSettings =
        ReaderViewportPolicy.settingsFor(viewport).copy(
            fontScale = preferences.fontScale,
            reducedMotion = preferences.reducedMotion
        )
}
