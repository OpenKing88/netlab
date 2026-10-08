package io.github.netlab.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.netlab.capture.CaptureRecord

private val HintColor = Color(0xFF888888)

@Composable
internal fun CaptureListScreen(
    records: List<CaptureRecord>,
    onOpen: (CaptureRecord) -> Unit,
    onClear: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var onlyFailed by remember { mutableStateOf(false) }

    // 记录在内存里本来就有上限（默认 500 条），所以过滤直接在这里做，
    // 不需要查库、也不需要给 SQLite 建索引。
    val filtered = remember(records, query, onlyFailed) {
        records.filter { it.matchesQuery(query) && (!onlyFailed || it.isFailure) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onlyFailed = onlyFailed,
            onOnlyFailedChange = { onlyFailed = it },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (filtered.size == records.size) {
                    "${records.size} 条记录"
                } else {
                    "${filtered.size} / ${records.size} 条"
                },
                style = MaterialTheme.typography.labelMedium,
                color = HintColor,
            )
            Spacer(Modifier.weight(1f, fill = true))
            TextButton(onClick = onClear) { Text("清空") }
        }

        if (filtered.isEmpty()) {
            Text(
                text = if (records.isEmpty()) {
                    "还没有抓到请求。发出网络请求后再回到这里。"
                } else {
                    "没有匹配的记录。换个关键词，或关掉筛选试试。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = HintColor,
                modifier = Modifier.padding(16.dp),
            )
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = filtered, key = { it.id }) { record ->
                CaptureRow(record = record, onOpen = onOpen)
                HorizontalDivider(color = Color(0x14000000))
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onlyFailed: Boolean,
    onOnlyFailedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜接口名 / 路径 / 状态码") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) { Text("清除") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = onlyFailed,
                onClick = { onOnlyFailedChange(!onlyFailed) },
                label = { Text("只看失败") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "多个词之间是「与」，例如：订单 post",
                style = MaterialTheme.typography.labelSmall,
                color = HintColor,
            )
        }
    }
}

/**
 * 一行记录是否命中搜索词。
 *
 * 把接口说明、接口名、方法名、路径、HTTP 方法、状态码拼成一个串再匹配，
 * 用户不需要知道该搜哪个字段，想到什么打什么。
 */
private fun CaptureRecord.matchesQuery(rawQuery: String): Boolean {
    val terms = rawQuery.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) {
        return true
    }
    val haystack = buildString {
        append(apiTitle).append(' ')
        append(retrofitService).append(' ')
        append(retrofitMethod).append(' ')
        append(method).append(' ')
        append(pathWithQuery).append(' ')
        append(url).append(' ')
        append(responseCode).append(' ')
        append(state)
    }.lowercase()
    return terms.all { haystack.contains(it) }
}

internal val CaptureRecord.isFailure: Boolean
    get() = state == CaptureRecord.STATE_FAILED || responseCode >= 400

@Composable
private fun CaptureRow(record: CaptureRecord, onOpen: (CaptureRecord) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(record) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.method,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                // 有接口信息就显示接口，别再让人对着混淆过的 path 猜
                text = record.apiTitle.ifEmpty { record.pathWithQuery.ifEmpty { "/" } },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = true),
                maxLines = 1,
            )
            Text(
                text = record.statusMark,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = when (record.state) {
                    CaptureRecord.STATE_FAILED -> Color(0xFFD32F2F)
                    CaptureRecord.STATE_REQUESTING -> HintColor
                    else -> if (record.responseCode in 200..299) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                },
            )
        }

        if (record.apiTitle.isNotEmpty() && record.pathWithQuery.isNotEmpty()) {
            Text(
                text = record.pathWithQuery,
                style = MaterialTheme.typography.labelSmall,
                color = HintColor,
                maxLines = 1,
            )
        }

        // 域名被改写时把「原域名 → 实际域名」直接摆在列表里，一眼能确认切换有没有生效
        record.rewriteDescription?.let { description ->
            Text(
                text = "域名改写：$description",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row {
            Text(
                text = formatTime(record.startedAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = HintColor,
            )
            val duration = formatDuration(record.durationMillis())
            if (duration.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(text = duration, style = MaterialTheme.typography.labelSmall, color = HintColor)
            }
            val size = formatBytes(record.totalSize)
            if (size.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(text = size, style = MaterialTheme.typography.labelSmall, color = HintColor)
            }
        }
    }
}
