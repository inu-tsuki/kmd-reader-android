package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.presentation.ReaderCompanionPlacement
import com.example.kmd_reader.presentation.ReaderCompanionState
import com.example.kmd_reader.presentation.ReaderCompanionType
import com.example.kmd_reader.presentation.ReaderViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCompanionPlacementPolicyTest {
    @Test
    fun portraitReviewUsesBottomSheetWithUnifiedDismissPolicy() {
        val layout = ReaderCompanionPlacementPolicy.resolve(
            viewport = ReaderViewportState(
                presentationMode = PresentationMode.Scroll,
                hostWidthPx = 1080,
                hostHeightPx = 2274
            ),
            companion = ReaderCompanionState(active = ReaderCompanionType.Review),
            type = ReaderCompanionType.Review
        )

        assertEquals(ReaderCompanionPlacement.BottomSheet, layout.placement)
        assertUnifiedDismissPolicy(layout.dismissPolicy)
    }

    @Test
    fun portraitLetterboxedStageStillUsesBottomSheet() {
        val layout = ReaderCompanionPlacementPolicy.resolve(
            viewport = ReaderViewportState(
                presentationMode = PresentationMode.Stage,
                hostWidthPx = 1080,
                hostHeightPx = 2274,
                letterboxed = true
            ),
            companion = ReaderCompanionState(active = ReaderCompanionType.Review),
            type = ReaderCompanionType.Review
        )

        assertEquals(ReaderCompanionPlacement.BottomSheet, layout.placement)
        assertUnifiedDismissPolicy(layout.dismissPolicy)
    }

    @Test
    fun landscapeReviewUsesSidePanelWithUnifiedDismissPolicy() {
        val layout = ReaderCompanionPlacementPolicy.resolve(
            viewport = ReaderViewportState(
                presentationMode = PresentationMode.Stage,
                hostWidthPx = 2274,
                hostHeightPx = 1080
            ),
            companion = ReaderCompanionState(active = ReaderCompanionType.Review),
            type = ReaderCompanionType.Review
        )

        assertEquals(ReaderCompanionPlacement.SidePanel, layout.placement)
        assertUnifiedDismissPolicy(layout.dismissPolicy)
    }

    @Test
    fun issuesCompanionSharesReviewPlacementRules() {
        val layout = ReaderCompanionPlacementPolicy.resolve(
            viewport = ReaderViewportState(
                presentationMode = PresentationMode.Scroll,
                hostWidthPx = 2274,
                hostHeightPx = 1080
            ),
            companion = ReaderCompanionState(active = ReaderCompanionType.Issues),
            type = ReaderCompanionType.Issues
        )

        assertEquals(ReaderCompanionPlacement.SidePanel, layout.placement)
        assertUnifiedDismissPolicy(layout.dismissPolicy)
    }

    @Test
    fun diagnosticsUseOverlayOnNarrowHostAndSidePanelOnWideHost() {
        val narrow = ReaderCompanionPlacementPolicy.resolve(
            viewport = ReaderViewportState(
                presentationMode = PresentationMode.Scroll,
                hostWidthPx = 1080,
                hostHeightPx = 2274
            ),
            companion = ReaderCompanionState(active = ReaderCompanionType.Diagnostics),
            type = ReaderCompanionType.Diagnostics
        )
        val wide = ReaderCompanionPlacementPolicy.resolve(
            viewport = ReaderViewportState(
                presentationMode = PresentationMode.Scroll,
                hostWidthPx = 2274,
                hostHeightPx = 1080
            ),
            companion = ReaderCompanionState(active = ReaderCompanionType.Diagnostics),
            type = ReaderCompanionType.Diagnostics
        )

        assertEquals(ReaderCompanionPlacement.Overlay, narrow.placement)
        assertUnifiedDismissPolicy(narrow.dismissPolicy)
        assertEquals(ReaderCompanionPlacement.SidePanel, wide.placement)
        assertUnifiedDismissPolicy(wide.dismissPolicy)
    }

    private fun assertUnifiedDismissPolicy(
        policy: com.example.kmd_reader.presentation.ReaderCompanionDismissPolicy
    ) {
        assertTrue(policy.closeOnBack)
        assertTrue(policy.closeOnTwoFingerTap)
        assertTrue(policy.closeOnOutsideDoubleTap)
        assertFalse(policy.closeOnOutsideSingleTap)
        assertFalse(policy.hasModalScrim)
    }
}
