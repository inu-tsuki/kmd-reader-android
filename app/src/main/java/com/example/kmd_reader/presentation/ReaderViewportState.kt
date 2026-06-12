package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.KmdPresentationHints
import com.example.kmd_reader.domain.model.OrientationHint
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.runtime.ReaderRuntimeViewport

data class ReaderViewportState(
    val presentationMode: PresentationMode = PresentationMode.Scroll,
    val orientationHint: OrientationHint = OrientationHint.Portrait,
    val aspectRatio: String = "9:16",
    val runtimeViewport: ReaderRuntimeViewport = ReaderRuntimeViewport(width = 390, height = 720),
    val hostWidthPx: Int = 0,
    val hostHeightPx: Int = 0,
    val letterboxed: Boolean = false,
    val sourceHints: KmdPresentationHints? = null
) {
    val isHostLandscape: Boolean
        get() = hostWidthPx > hostHeightPx && hostHeightPx > 0

    val isRuntimeLandscape: Boolean
        get() = runtimeViewport.width > runtimeViewport.height
}
