package com.example.kmd_reader.presentation

data class ReaderCompanionState(
    val active: ReaderCompanionType? = null,
    val placement: ReaderCompanionPlacement = ReaderCompanionPlacement.Overlay,
    val expanded: Boolean = false
)

enum class ReaderCompanionType {
    Review,
    Issues,
    CommunitySummary,
    WorkInfo,
    Diagnostics
}

enum class ReaderCompanionPlacement {
    BottomSheet,
    SidePanel,
    TopBottomBands,
    Overlay
}

data class ReaderCompanionLayout(
    val placement: ReaderCompanionPlacement,
    val dismissPolicy: ReaderCompanionDismissPolicy = ReaderCompanionDismissPolicy()
)

data class ReaderCompanionDismissPolicy(
    val closeOnBack: Boolean = true,
    val closeOnTwoFingerTap: Boolean = true,
    val closeOnOutsideDoubleTap: Boolean = true,
    val closeOnOutsideSingleTap: Boolean = false,
    val hasModalScrim: Boolean = false
)

fun ReaderCompanionState.open(
    type: ReaderCompanionType,
    placement: ReaderCompanionPlacement? = null
): ReaderCompanionState =
    copy(
        active = type,
        placement = placement ?: this.placement,
        expanded = false
    )

fun ReaderCompanionState.close(): ReaderCompanionState =
    copy(active = null, expanded = false)

fun ReaderCompanionState.toggleExpanded(): ReaderCompanionState =
    if (active == null) {
        this
    } else {
        copy(expanded = !expanded)
    }
