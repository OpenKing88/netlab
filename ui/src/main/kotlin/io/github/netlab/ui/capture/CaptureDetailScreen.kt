package io.github.netlab.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.netlab.capture.CaptureRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HintColor = Color(0xFF888888)

@Composable
internal fun CaptureDetailScreen(record: CaptureRecord, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x0A000000))
                .padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                text = record.pathWithQuery.ifEmpty { "/" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = true),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle("概览")
            InfoRow("接口", record.apiTitle)
            InfoRow("方法", record.method)
            InfoRow("地址", record.url)
            record.rewriteDescription?.let { InfoRow("域名改写", it) }
            InfoRow("状态", statusText(record))
            InfoRow("耗时", formatDuration(record.durationMillis()))
            InfoRow("协议", listOf(record.protocol, record.tlsVersion).filter { it.isNotEmpty() }.joinToString(" / "))
            InfoRow("时间", formatTime(record.startedAtMillis))
            record.error?.let { InfoRow("错误", it) }

            SectionTitle("请求")
            InfoRow("Content-Type", record.requestContentType)
            InfoRow("长度", formatBytes(record.requestContentLength))
            BodyBlock(
                body = record.requestBody,
                state = record.requestBodyState,
                emptyHint = "无请求体",
            )

            SectionTitle("请求头")
            Text(
                text = record.requestHeaders.ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )

            SectionTitle("响应")
            InfoRow("Content-Type", record.responseContentType)
            InfoRow("长度", formatBytes(record.responseContentLength))
            BodyBlock(
                body = record.responseBody,
                state = record.responseBodyState,
                emptyHint = "无响应体",
            )

            SectionTitle("响应头")
            Text(
                text = record.responseHeaders.ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isEmpty()) {
        return
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = HintColor,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = true),
        )
    }
}

/**
 * body 展示块。
 *
 * <p>关键点：美化与高亮都在**后台线程**算，算完再交给 UI —— Monitor 是在组合期同步跑的，
 * 大响应必然卡主线程。
 */
@Composable
private fun BodyBlock(body: String, state: String, emptyHint: String) {
    val fallback = when (state) {
        CaptureRecord.BODY_OMITTED -> "未捕获（一次性 / 流式 body，或未知编码）"
        CaptureRecord.BODY_TOO_LARGE -> "体积超限，未捕获"
        else -> emptyHint
    }

    if (body.isEmpty()) {
        Text(text = fallback, style = MaterialTheme.typography.bodySmall, color = HintColor)
        return
    }

    val keyColor = MaterialTheme.colorScheme.primary
    val valueColor = MaterialTheme.colorScheme.onSurface
    val literalColor = MaterialTheme.colorScheme.tertiary
    val punctuationColor = MaterialTheme.colorScheme.onSurfaceVariant

    var highlighted by remember(body) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(body) {
        highlighted = withContext(Dispatchers.Default) {
            JsonHighlighter.highlight(
                json = JsonHighlighter.prettyPrint(body),
                keyColor = keyColor,
                valueColor = valueColor,
                literalColor = literalColor,
                punctuationColor = punctuationColor,
            )
        }
    }

    val text = highlighted
    if (text == null) {
        Text(text = "渲染中…", style = MaterialTheme.typography.bodySmall, color = HintColor)
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x08000000))
                .padding(8.dp),
        )
    }
}

private fun statusText(record: CaptureRecord): String = when (record.state) {
    CaptureRecord.STATE_REQUESTING -> "请求中"
    CaptureRecord.STATE_FAILED -> "失败"
    else -> "${record.responseCode} ${record.responseMessage}".trim()
}
