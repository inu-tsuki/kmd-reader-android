package com.example.kmd_reader.ui.screen.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.presentation.ImportState
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.KmdPill
import com.example.kmd_reader.ui.component.SectionTitle

/** R3-K1 阅读资产首页：投影先行，单个网格滚动容器负责所有书架内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineDesk(
    shelfState: ShelfState,
    importState: ImportState,
    selectedView: BookshelfView,
    nowMillis: Long,
    onBookshelfViewChange: (BookshelfView) -> Unit,
    onOpenImport: () -> Unit,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bookshelf = shelfState.toBookshelfUiModel(selectedView, nowMillis)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BookshelfToolbar(onOpenImport = onOpenImport, onOpenSettings = onOpenSettings)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                BookshelfView.entries.forEachIndexed { index, view ->
                    SegmentedButton(
                        selected = selectedView == view,
                        onClick = { onBookshelfViewChange(view) },
                        shape = SegmentedButtonDefaults.itemShape(index, BookshelfView.entries.size),
                        modifier = Modifier.testTag("bookshelf-view-${view.name}")
                    ) {
                        Text(if (view == BookshelfView.Library) "书架" else "历史")
                    }
                }
            }
        }
        importStateContent(importState, onOpenImport)

        when (bookshelf.view) {
            BookshelfView.Library -> libraryContent(
                bookshelf = bookshelf,
                onOpenImport = onOpenImport,
                onOpenWork = onOpenWork,
                onContinueReading = onContinueReading
            )
            BookshelfView.History -> historyContent(
                bookshelf = bookshelf,
                onOpenWork = onOpenWork,
                onContinueReading = onContinueReading
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.importStateContent(
    importState: ImportState,
    onOpenImport: () -> Unit
) {
    when (importState) {
        ImportState.Idle -> Unit
        ImportState.Importing -> item(span = { GridItemSpan(maxLineSpan) }) {
            ImportStatus(title = "正在导入作品…", body = "正在处理所选文件，请稍候。")
        }
        is ImportState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            ImportStatus(
                title = "导入失败",
                body = importState.message,
                actionLabel = "重新选择文件",
                onAction = onOpenImport
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.libraryContent(
    bookshelf: BookshelfUiModel,
    onOpenImport: () -> Unit,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit
) {
    section(
        cards = bookshelf.continueReading,
        key = "continue",
        title = "继续阅读",
        subtitle = "最近读到一半的作品",
        onOpenWork = onOpenWork,
        onContinueReading = onContinueReading
    )
    if (bookshelf.emptyState == BookshelfEmptyState.Library) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            InfoCard(
                title = "还没有作品",
                body = "导入 .kmd 或 .kmdwork 文件开始阅读。",
                actionLabel = "导入作品",
                onAction = onOpenImport
            )
        }
        return
    }
    section(
        cards = bookshelf.localShelf,
        key = "local",
        title = "本地可读",
        subtitle = "已在此设备保存 source 的作品",
        onOpenWork = onOpenWork,
        onContinueReading = onContinueReading
    )
    section(
        cards = bookshelf.networkShelf,
        key = "network",
        title = "需联网",
        subtitle = "仅保存作品信息，阅读时需要远端 source",
        onOpenWork = onOpenWork,
        onContinueReading = onContinueReading
    )
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.historyContent(
    bookshelf: BookshelfUiModel,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit
) {
    if (bookshelf.emptyState == BookshelfEmptyState.History) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            InfoCard(title = "还没有阅读记录", body = "开始阅读后，作品会出现在这里。")
        }
    } else {
        section(
            cards = bookshelf.history,
            key = "history",
            title = "阅读历史",
            subtitle = "最近阅读过但未放入书架的作品",
            onOpenWork = onOpenWork,
            onContinueReading = onContinueReading
        )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.section(
    cards: List<BookshelfCardModel>,
    key: String,
    title: String,
    subtitle: String,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit
) {
    if (cards.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }) {
        SectionTitle(title = title, subtitle = subtitle)
    }
    items(cards, key = { "$key-${it.workId}" }) { item ->
        ShelfCard(item, "$key-${item.workId}", onOpenWork, onContinueReading)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfToolbar(onOpenImport: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "书架",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "继续阅读与已保存的作品",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row {
            TooltipBox(
                positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("导入作品") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onOpenImport, modifier = Modifier.semantics { contentDescription = "导入作品" }) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null)
                }
            }
            TooltipBox(
                positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("打开阅读设置") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = "打开阅读设置" }) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ImportStatus(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfCard(
    item: BookshelfCardModel,
    cardKey: String,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenWork(item.workId) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (item.authorName.isBlank()) "作者未知" else "by ${item.authorName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KmdPill(item.modeLabel)
                    KmdPill(item.sourceLabel)
                }
                item.progress?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                item.timeLabel?.let { timeLabel ->
                    Text(timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (item.canContinue) {
                    Button(
                        onClick = { onContinueReading(item.workId) },
                        modifier = Modifier.testTag("continue-$cardKey")
                    ) { Text("继续阅读") }
                }
                OutlinedButton(
                    onClick = { onOpenWork(item.workId) },
                    modifier = Modifier.testTag("details-$cardKey")
                ) { Text("打开详情") }
            }
        }
    }
}
