package com.example.kmd_reader.domain.model

import kotlin.math.abs

data class KmdPresentationHints(
    val presentationMode: PresentationMode? = null,
    val designWidth: Int? = null,
    val designHeight: Int? = null
) {
    val hasAnyHint: Boolean
        get() = presentationMode != null || hasDesignViewport

    val hasDesignViewport: Boolean
        get() = designWidth != null && designHeight != null && designWidth > 0 && designHeight > 0

    val orientationHint: OrientationHint?
        get() {
            val width = designWidth?.takeIf { it > 0 } ?: return null
            val height = designHeight?.takeIf { it > 0 } ?: return null
            return if (width > height) {
                OrientationHint.Landscape
            } else if (width < height) {
                OrientationHint.Portrait
            } else {
                OrientationHint.Adaptive
            }
        }

    val aspectRatio: String?
        get() {
            val width = designWidth?.takeIf { it > 0 } ?: return null
            val height = designHeight?.takeIf { it > 0 } ?: return null
            val divisor = gcd(abs(width), abs(height))
            return "${width / divisor}:${height / divisor}"
        }
}

private tailrec fun gcd(a: Int, b: Int): Int =
    if (b == 0) {
        a.coerceAtLeast(1)
    } else {
        gcd(b, a % b)
    }
