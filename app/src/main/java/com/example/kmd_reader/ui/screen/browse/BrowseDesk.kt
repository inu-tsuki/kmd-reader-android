package com.example.kmd_reader.ui.screen.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle
import com.example.kmd_reader.ui.component.WorkCard

@Composable
fun BrowseDesk(
    works: List<Work>,
    resultCount: Int,
    onOpenSearch: () -> Unit,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(
                    title = "浏览",
                    subtitle = "用作品形态和预览卡片判断是否进入详情。"
                )
                InfoCard(
                    title = "筛选结果",
                    body = "当前显示 $resultCount 个作品。搜索浮层不会改变当前页面结构。",
                    actionLabel = "打开搜索/筛选",
                    onAction = onOpenSearch
                )
                Button(onClick = onOpenSearch) {
                    Text("搜索与筛选")
                }
            }
        }
        items(works, key = { it.id }) { work ->
            WorkCard(work = work, onOpen = { onOpenWork(work.id) })
        }
    }
}
