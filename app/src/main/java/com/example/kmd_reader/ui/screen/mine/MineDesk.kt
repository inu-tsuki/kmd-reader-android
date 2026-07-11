package com.example.kmd_reader.ui.screen.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.KmdPill
import com.example.kmd_reader.ui.component.SectionTitle
import com.example.kmd_reader.ui.format.formatRelativeReadTime

/**
 * R3-F：书架桌面（page-architecture §7.1）。
 *
 * 分两组展示本地作品：
 * - 书架（onShelf=true）：本地导入、收藏、离线可读的作品。
 * - 阅读历史（lastReadAt!=null 且 onShelf=false）：读过但未收藏的社区/mock 作品。
 *
 * 卡片显示标题、进度条、时间，并有「继续阅读」/「打开详情」按钮。
 * 底部提供导入和设置/关于入口。
 */
@Composable
fun MineDesk(
    shelfState: ShelfState,
    onOpenImport: () -> Unit,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle(
            title = "书架",
            subtitle = "本地导入、收藏和离线可读的作品。"
        )
        if (shelfState.shelf.isEmpty()) {
            InfoCard(
                title = "还没有作品",
                body = "导入 .kmd 或 .kmdwork 文件开始阅读。",
                actionLabel = "导入",
                onAction = onOpenImport
            )
        } else {
            shelfState.shelf.forEach { item ->
                ShelfCard(
                    item = item,
                    onOpenWork = onOpenWork,
                    onContinueReading = onContinueReading
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        SectionTitle(
            title = "阅读历史",
            subtitle = "最近阅读过的作品。"
        )
        if (shelfState.history.isEmpty()) {
            Text(
                text = "还没有阅读记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            shelfState.history.forEach { item ->
                ShelfCard(
                    item = item,
                    onOpenWork = onOpenWork,
                    onContinueReading = onContinueReading
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = onOpenImport) {
                Text("导入脚本")
            }
            OutlinedButton(onClick = onOpenSettings) {
                Text("设置 / 关于")
            }
        }
    }
}

/**
 * 书架/历史卡片。显示标题、作者、形态 pill、进度条、时间，
 * 以及「继续阅读」或「打开详情」按钮。
 */
@Composable
private fun ShelfCard(
    item: ShelfItem,
    onOpenWork: (String) -> Unit,
    onContinueReading: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenWork(item.workId) }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "by ${item.authorName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            KmdPill(text = item.modeLabel)
            // 进度条：0% 时隐藏，避免无进度作品显示空条。
            if (item.readingProgress > 0f && item.readingProgress < 1f) {
                LinearProgressIndicator(
                    progress = { item.readingProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 时间文本：优先 lastReadAt，其次 importedAt；都没有不显示。
            formatTimeLabel(item)?.let { timeLabel ->
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 有进度且有本地源 → 继续阅读（直达 Reader）；否则打开详情。
                if (item.readingProgress > 0f && item.hasLocalSource) {
                    Button(onClick = { onContinueReading(item.workId) }) {
                        Text("继续阅读")
                    }
                }
                OutlinedButton(onClick = { onOpenWork(item.workId) }) {
                    Text("打开详情")
                }
            }
        }
    }
}

/**
 * 格式化时间标签：有 lastReadAt → "上次阅读 X 天前"；
 * 否则有 importedAt → "导入于 X 天前"；都没有 → null（不显示）。
 * 用 now（调用时取 System.currentTimeMillis）做差值，粗粒度天数格式化。
 */
private fun formatTimeLabel(item: ShelfItem): String? {
    val now = System.currentTimeMillis()
    item.lastReadAt?.let { last ->
        return "上次阅读 ${formatRelativeReadTime(now, last)}"
    }
    item.importedAt?.let { imported ->
        return "导入于 ${formatRelativeReadTime(now, imported)}"
    }
    return null
}
