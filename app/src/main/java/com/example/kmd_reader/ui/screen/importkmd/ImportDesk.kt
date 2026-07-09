package com.example.kmd_reader.ui.screen.importkmd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.presentation.ImportState
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun ImportDesk(
    importState: ImportState,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("导入", "选择本地 .kmdwork 或 .kmd 文件导入到书架。")

        InfoCard(
            title = "支持的格式",
            body = ".kmdwork：包含脚本、资产和 manifest 的 zip 作品包。\n.kmd：纯文本脚本，导入后直接可读。"
        )

        when (importState) {
            ImportState.Idle -> {
                Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Text("选择文件导入")
                }
            }
            ImportState.Importing -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text("正在导入…", textAlign = TextAlign.Center)
                }
            }
            is ImportState.Failed -> {
                Text(
                    text = "导入失败：${importState.message}",
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    textAlign = TextAlign.Center
                )
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Text("重新选择文件")
                }
            }
        }
    }
}