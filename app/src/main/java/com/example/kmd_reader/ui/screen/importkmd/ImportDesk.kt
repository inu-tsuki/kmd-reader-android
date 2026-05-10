package com.example.kmd_reader.ui.screen.importkmd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun ImportDesk(
    onMockImport: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("导入", "第二阶段先验证流程，不触发真实文件选择器。")
        InfoCard(
            title = "导入规则",
            body = "导入完成后会打开脚本详情，并替换旧详情链路。导入页本身不会成为长期标签。"
        )
        Button(onClick = { onMockImport("choice-room") }) {
            Text("模拟导入 choice-room.kmd")
        }
    }
}
