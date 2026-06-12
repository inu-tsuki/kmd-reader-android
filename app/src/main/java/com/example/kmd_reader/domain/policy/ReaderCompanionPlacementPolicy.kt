package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.presentation.ReaderCompanionDismissPolicy
import com.example.kmd_reader.presentation.ReaderCompanionLayout
import com.example.kmd_reader.presentation.ReaderCompanionPlacement
import com.example.kmd_reader.presentation.ReaderCompanionState
import com.example.kmd_reader.presentation.ReaderCompanionType
import com.example.kmd_reader.presentation.ReaderViewportState

object ReaderCompanionPlacementPolicy {
    fun resolve(
        viewport: ReaderViewportState,
        companion: ReaderCompanionState,
        type: ReaderCompanionType
    ): ReaderCompanionLayout {
        val placement = resolvePlacement(
            viewport = viewport,
            type = type
        )

        return ReaderCompanionLayout(
            placement = placement,
            dismissPolicy = ReaderCompanionDismissPolicy()
        )
    }

    private fun resolvePlacement(
        viewport: ReaderViewportState,
        type: ReaderCompanionType
    ): ReaderCompanionPlacement {
        val isPortraitHost = !viewport.isHostLandscape
        val isWideHost = viewport.isHostLandscape

        return when {
            type == ReaderCompanionType.Diagnostics && isWideHost ->
                ReaderCompanionPlacement.SidePanel

            type == ReaderCompanionType.Diagnostics ->
                ReaderCompanionPlacement.Overlay

            isPortraitHost ->
                ReaderCompanionPlacement.BottomSheet

            else ->
                ReaderCompanionPlacement.SidePanel
        }
    }
}
