package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.domain.model.KmdPresentationHints
import com.example.kmd_reader.domain.model.OrientationHint
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.presentation.ReaderViewportState
import com.example.kmd_reader.runtime.ReaderRuntimeViewport
import com.example.kmd_reader.runtime.ReaderSettings
import kotlin.math.abs
import kotlin.math.roundToInt

object ReaderViewportPolicy {
    private const val PortraitWidth = 390
    private const val PortraitHeight = 720
    private const val LandscapeWidth = 960
    private const val DefaultLandscapeRatio = 16f / 9f
    private const val LetterboxAspectTolerance = 0.08f

    fun resolve(
        work: Work,
        hostWidthPx: Int = 0,
        hostHeightPx: Int = 0,
        sourceHints: KmdPresentationHints? = null
    ): ReaderViewportState {
        val host = effectiveHost(hostWidthPx, hostHeightPx)
        val effectiveSourceHints = sourceHints?.takeIf { it.hasAnyHint }
        val presentationMode = effectiveSourceHints?.presentationMode ?: work.presentation.mode
        val stageLike = presentationMode == PresentationMode.Stage ||
            presentationMode == PresentationMode.Interactive
        val sourceDesignHints = effectiveSourceHints?.takeIf {
            stageLike && it.hasDesignViewport
        }
        val orientationHint = sourceDesignHints?.orientationHint ?: work.presentation.orientationHint
        val aspectRatio = sourceDesignHints?.aspectRatio ?: work.presentation.aspectRatio
        val runtimeViewport = sourceDesignViewport(sourceDesignHints)
            ?: runtimeViewportFor(
                presentationMode = presentationMode,
                orientationHint = orientationHint,
                aspectRatio = aspectRatio,
                host = host
            )
        val letterboxed = stageLike && shouldLetterbox(
            hostRatio = host.width.toFloat() / host.height.toFloat(),
            viewportRatio = runtimeViewport.width.toFloat() / runtimeViewport.height.toFloat()
        )

        return ReaderViewportState(
            presentationMode = presentationMode,
            orientationHint = orientationHint,
            aspectRatio = aspectRatio,
            runtimeViewport = runtimeViewport,
            hostWidthPx = hostWidthPx,
            hostHeightPx = hostHeightPx,
            letterboxed = letterboxed,
            sourceHints = effectiveSourceHints
        )
    }

    fun settingsFor(state: ReaderViewportState): ReaderSettings =
        ReaderSettings(
            assetBaseUrl = "./",
            presentationMode = runtimeModeFor(state.presentationMode),
            viewport = state.runtimeViewport
        )

    private fun runtimeViewportFor(
        presentationMode: PresentationMode,
        orientationHint: OrientationHint,
        aspectRatio: String,
        host: HostSize
    ): ReaderRuntimeViewport =
        when (presentationMode) {
            PresentationMode.Scroll,
            PresentationMode.Paged -> portraitViewportFor(
                orientationHint = orientationHint,
                aspectRatio = aspectRatio
            )

            PresentationMode.Stage,
            PresentationMode.Interactive -> stageViewportFor(
                orientationHint = orientationHint,
                aspectRatio = aspectRatio,
                host = host
            )
        }

    private fun sourceDesignViewport(sourceHints: KmdPresentationHints?): ReaderRuntimeViewport? {
        if (sourceHints?.hasDesignViewport != true) {
            return null
        }
        val width = sourceHints.designWidth ?: return null
        val height = sourceHints.designHeight ?: return null
        return ReaderRuntimeViewport(width = width, height = height)
    }

    private fun portraitViewportFor(
        orientationHint: OrientationHint,
        aspectRatio: String
    ): ReaderRuntimeViewport =
        if (orientationHint == OrientationHint.Landscape) {
            landscapeViewport(aspectRatio)
        } else {
            portraitViewport(aspectRatio)
        }

    private fun stageViewportFor(
        orientationHint: OrientationHint,
        aspectRatio: String,
        host: HostSize
    ): ReaderRuntimeViewport =
        when (orientationHint) {
            OrientationHint.Portrait -> portraitViewport(aspectRatio)
            OrientationHint.Landscape -> landscapeViewport(aspectRatio)
            OrientationHint.Adaptive -> adaptiveViewport(aspectRatio, host)
        }

    private fun adaptiveViewport(
        aspectRatio: String,
        host: HostSize
    ): ReaderRuntimeViewport {
        val ratio = parseAspectRatio(aspectRatio)
        return when {
            ratio == null -> if (host.width >= host.height) {
                landscapeViewportForRatio(null)
            } else {
                portraitViewportForRatio(null)
            }
            ratio >= 1f -> landscapeViewportForRatio(ratio)
            else -> portraitViewportForRatio(ratio)
        }
    }

    private fun portraitViewport(aspectRatio: String?): ReaderRuntimeViewport =
        portraitViewportForRatio(parseAspectRatio(aspectRatio))

    private fun portraitViewportForRatio(aspectRatio: Float?): ReaderRuntimeViewport {
        if (aspectRatio == null || aspectRatio <= 0f || aspectRatio >= 1f) {
            return ReaderRuntimeViewport(width = PortraitWidth, height = PortraitHeight)
        }
        val height = PortraitHeight
        val width = (height * aspectRatio).roundToInt().coerceIn(320, 540)
        return ReaderRuntimeViewport(width = width, height = height)
    }

    private fun landscapeViewport(aspectRatio: String?): ReaderRuntimeViewport =
        landscapeViewportForRatio(parseAspectRatio(aspectRatio))

    private fun landscapeViewportForRatio(aspectRatio: Float?): ReaderRuntimeViewport {
        val ratio = aspectRatio?.takeIf { it > 1f } ?: DefaultLandscapeRatio
        val width = LandscapeWidth
        val height = (width / ratio).roundToInt().coerceIn(360, 720)
        return ReaderRuntimeViewport(width = width, height = height)
    }

    private fun runtimeModeFor(mode: PresentationMode): String =
        when (mode) {
            PresentationMode.Scroll -> "scroll"
            PresentationMode.Paged -> "page"
            PresentationMode.Stage,
            PresentationMode.Interactive -> "stage"
        }

    private fun shouldLetterbox(
        hostRatio: Float,
        viewportRatio: Float
    ): Boolean =
        abs(hostRatio - viewportRatio) > LetterboxAspectTolerance

    private fun parseAspectRatio(raw: String?): Float? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = value.split(":")
        if (parts.size != 2) return null
        val width = parts[0].toFloatOrNull() ?: return null
        val height = parts[1].toFloatOrNull() ?: return null
        if (width <= 0f || height <= 0f) return null
        return width / height
    }

    private fun effectiveHost(
        widthPx: Int,
        heightPx: Int
    ): HostSize =
        if (widthPx > 0 && heightPx > 0) {
            HostSize(width = widthPx, height = heightPx)
        } else {
            HostSize(width = PortraitWidth, height = PortraitHeight)
        }

    private data class HostSize(
        val width: Int,
        val height: Int
    )
}
