package com.example.kmd_reader.ui.component.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.KmdSourceHighlight
import com.example.kmd_reader.domain.model.KmdSourceLine
import com.example.kmd_reader.domain.model.KmdSourceRange
import com.example.kmd_reader.domain.model.KmdSourceSnapshot
import com.example.kmd_reader.domain.model.KmdSourceSnippet
import kotlinx.coroutines.flow.filter

enum class KmdSourceContextMode {
    Snippet,
    Full
}

@Composable
fun KmdSourceContextView(
    snapshot: KmdSourceSnapshot?,
    emptyText: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    mode: KmdSourceContextMode = KmdSourceContextMode.Snippet,
    snippet: KmdSourceSnippet? = null,
    focusLine: Int? = snippet?.focusedLine,
    scrollRequestKey: Any? = null,
    highlights: List<KmdSourceHighlight> = emptyList(),
    badges: List<KmdSourceLineBadge> = emptyList(),
    maxHeight: Dp? = if (mode == KmdSourceContextMode.Full) 320.dp else 220.dp,
    showSurfaceChrome: Boolean = true,
    onLineClick: (Int) -> Unit = {},
    onBadgeClick: (String) -> Unit = {},
    onUserScroll: (() -> Unit)? = null,
    lineContext: @Composable (Int) -> Unit = {}
) {
    val lines = remember(snapshot, snippet, mode) {
        when (mode) {
            KmdSourceContextMode.Snippet -> snippet?.lines.orEmpty()
            KmdSourceContextMode.Full -> snapshot?.toSourceLines().orEmpty()
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(mode, focusLine, lines, scrollRequestKey) {
        if (mode != KmdSourceContextMode.Full || focusLine == null) {
            return@LaunchedEffect
        }
        val index = lines.indexOfFirst { it.number == focusLine }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    // 用户主动拖动时通知上层（用于停止「正播放跟随」）。
    // 编程式 scrollToItem 是瞬时无动画的，不会触发 isScrollInProgress，
    // 因此不会误判为用户滑动。
    if (onUserScroll != null) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .filter { it }
                .collect { onUserScroll.invoke() }
        }
    }

    @Composable
    fun SourceContent(contentModifier: Modifier) {
        Column(
            modifier = if (maxHeight == null) {
                contentModifier.fillMaxSize()
            } else {
                contentModifier
            },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (snapshot == null || lines.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (mode == KmdSourceContextMode.Full) {
                LazyColumn(
                    modifier = if (maxHeight == null) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                    },
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        items = lines,
                        key = { it.number }
                    ) { line ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            KmdSourceLineRow(
                                lineNumber = line.number,
                                text = line.text,
                                focused = line.number == focusLine,
                                highlights = highlights.forLine(line.number),
                                badges = badges.badgesForLine(line.number),
                                onLineClick = onLineClick,
                                onBadgeClick = onBadgeClick
                            )
                            lineContext(line.number)
                        }
                    }
                }
            } else {
                Column(
                    modifier = if (maxHeight == null) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                    },
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    lines.forEach { line ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            KmdSourceLineRow(
                                lineNumber = line.number,
                                text = line.text,
                                focused = line.isFocused || line.number == focusLine,
                                highlights = highlights.forLine(line.number),
                                badges = badges.badgesForLine(line.number),
                                onLineClick = onLineClick,
                                onBadgeClick = onBadgeClick
                            )
                            lineContext(line.number)
                        }
                    }
                }
            }
        }
    }

    if (showSurfaceChrome) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            SourceContent(Modifier.padding(10.dp))
        }
    } else {
        SourceContent(modifier.fillMaxWidth())
    }
}

private fun KmdSourceSnapshot.toSourceLines(): List<KmdSourceLine> =
    (1..lineCount).mapNotNull { lineNumber ->
        lineAt(lineNumber)?.let { text ->
            KmdSourceLine(
                number = lineNumber,
                text = text,
                isFocused = false
            )
        }
    }

private fun List<KmdSourceHighlight>.forLine(lineNumber: Int): List<KmdSourceHighlight> =
    filter { highlight -> highlight.range.containsLine(lineNumber) }

private fun List<KmdSourceLineBadge>.badgesForLine(lineNumber: Int): List<KmdSourceLineBadge> =
    filter { badge -> badge.lineNumber == lineNumber }

private fun KmdSourceRange.containsLine(lineNumber: Int): Boolean =
    lineNumber in startLine..endLine
