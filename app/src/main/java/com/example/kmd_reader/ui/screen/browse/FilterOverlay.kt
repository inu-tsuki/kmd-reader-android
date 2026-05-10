package com.example.kmd_reader.ui.screen.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.ui.component.KmdPill
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun FilterOverlay(
    query: String,
    selectedMode: PresentationMode?,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onToggleMode: (PresentationMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(18.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionTitle("搜索/筛选", "关闭后保留当前筛选条件。")
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("标题、作者或标签") }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresentationMode.entries.forEach { mode ->
                        KmdPill(
                            text = mode.label,
                            selected = selectedMode == mode,
                            onClick = { onToggleMode(mode) }
                        )
                    }
                }
                Text(
                    text = "当前结果：$resultCount 个作品",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onClose) {
                    Text("收起筛选")
                }
            }
        }
    }
}
