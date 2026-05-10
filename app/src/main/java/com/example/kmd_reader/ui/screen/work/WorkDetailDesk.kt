package com.example.kmd_reader.ui.screen.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.PreviewFrame
import com.example.kmd_reader.ui.component.SectionTitle
import com.example.kmd_reader.ui.component.StatRow
import com.example.kmd_reader.ui.component.TagRow
import com.example.kmd_reader.ui.component.WorkMetaChips

@Composable
fun WorkDetailDesk(
    work: Work?,
    onOpenReader: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (work == null) {
        InfoCard(
            title = "没有选中脚本",
            body = "从浏览或我的页面打开一个脚本后，这里会显示详情。",
            modifier = modifier.padding(16.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle(work.title, "by ${work.authorName}")
        PreviewFrame(work)
        WorkMetaChips(work)
        TagRow(work.tags)
        Text(work.description)
        InfoCard(
            title = "评论摘要",
            body = work.commentSummary.summary
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("作品属性", fontWeight = FontWeight.Bold)
            StatRow("动效强度", work.attributes.effectIntensity.label)
            StatRow("指令数量", "${work.attributes.commandCount}")
            StatRow("外部资源", "${work.attributes.externalAssetCount}")
            StatRow("复杂度", work.attributes.complexityLevel.label)
            StatRow("Runtime", work.attributes.runtimeVersion)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenReader) {
                Text("开始阅读")
            }
            OutlinedButton(onClick = onOpenReview) {
                Text("审阅脚本")
            }
        }
        OutlinedButton(onClick = onOpenImport) {
            Text("替换为本地导入")
        }
    }
}
