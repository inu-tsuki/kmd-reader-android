package com.example.kmd_reader.ui.screen.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun MineDesk(
    recentWorks: List<Work>,
    onOpenImport: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle(
            title = "我的",
            subtitle = "本地导入、最近阅读和草稿会在这里聚合。"
        )
        InfoCard(
            title = "导入 KMD 脚本",
            body = "第二阶段先模拟导入流程，后续接入 Android 文件选择器。",
            actionLabel = "打开导入",
            onAction = onOpenImport
        )
        InfoCard(
            title = "页面切换规则",
            body = "左右滑动只切换当前页面，不销毁状态。打开另一个脚本时会替换旧详情和阅读页。"
        )
        Text(
            text = "最近阅读",
            style = MaterialTheme.typography.titleMedium
        )
        recentWorks.take(2).forEach { work ->
            OutlinedButton(onClick = { onOpenWork(work.id) }) {
                Text("${work.title} · ${work.presentation.mode.label}")
            }
        }
        Button(onClick = onOpenImport) {
            Text("模拟导入本地脚本")
        }
    }
}
