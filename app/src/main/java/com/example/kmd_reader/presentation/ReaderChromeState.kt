package com.example.kmd_reader.presentation

data class ReaderChromeState(
    val visibility: ReaderChromeVisibility = ReaderChromeVisibility.Visible,
    val isPinned: Boolean = false,
    val mode: ReaderChromeMode = ReaderChromeMode.Reading,
    val lastInteractionAtMillis: Long = 0L
)

enum class ReaderChromeVisibility {
    Visible,
    Dimmed,
    Hidden
}

enum class ReaderChromeMode {
    Reading,
    Reviewing,
    Error
}

fun ReaderChromeState.show(
    mode: ReaderChromeMode = this.mode,
    atMillis: Long = lastInteractionAtMillis
): ReaderChromeState =
    copy(
        visibility = ReaderChromeVisibility.Visible,
        mode = mode,
        lastInteractionAtMillis = atMillis
    )

fun ReaderChromeState.dim(): ReaderChromeState =
    if (isPinned || mode != ReaderChromeMode.Reading) {
        this
    } else {
        copy(visibility = ReaderChromeVisibility.Dimmed)
    }

fun ReaderChromeState.hide(): ReaderChromeState =
    if (isPinned || mode != ReaderChromeMode.Reading) {
        this
    } else {
        copy(visibility = ReaderChromeVisibility.Hidden)
    }

fun ReaderChromeState.toggle(atMillis: Long = lastInteractionAtMillis): ReaderChromeState =
    when (visibility) {
        ReaderChromeVisibility.Visible -> hide()
        ReaderChromeVisibility.Dimmed,
        ReaderChromeVisibility.Hidden -> show(atMillis = atMillis)
    }
