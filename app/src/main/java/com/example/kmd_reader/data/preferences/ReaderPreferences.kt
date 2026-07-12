package com.example.kmd_reader.data.preferences

enum class ThemeMode { System, Light, Dark }

data class ReaderPreferences(
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val themeMode: ThemeMode = ThemeMode.System,
    val autoSaveProgress: Boolean = true,
    val reducedMotion: Boolean = false
) {
    companion object {
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.30f
        const val DEFAULT_FONT_SCALE = 1f

        fun normalizedFontScale(value: Float): Float =
            value.takeIf { it.isFinite() }?.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                ?: DEFAULT_FONT_SCALE
    }
}
