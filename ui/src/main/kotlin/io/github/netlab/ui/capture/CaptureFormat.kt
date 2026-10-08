package io.github.netlab.ui.capture

import io.github.netlab.capture.CaptureRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 只在主线程使用，所以格式化器可以复用。 */
private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

internal fun formatTime(millis: Long): String {
    if (millis <= 0L) {
        return ""
    }
    return timeFormatter.format(Date(millis))
}

internal fun formatDuration(millis: Long): String {
    return if (millis <= 0L) "" else "$millis ms"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) {
        return ""
    }
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

/** 列表右侧的状态标记，和 Monitor 的 `!!!` / `...` 一个意思，只是写得更直白。 */
internal val CaptureRecord.statusMark: String
    get() = when (state) {
        CaptureRecord.STATE_REQUESTING -> "…"
        CaptureRecord.STATE_FAILED -> "!"
        else -> responseCode.toString()
    }

internal val CaptureRecord.totalSize: Long
    get() = (if (requestContentLength > 0) requestContentLength else 0L) +
            (if (responseContentLength > 0) responseContentLength else 0L)

/**
 * 列表主标题。
 *
 * <p>优先级：接口说明（编译期从源码注释提取） → 「接口#方法」 → 空串。
 * 之所以需要它：接口路径往往是混淆过的（`/mUG8wv5IktV/nCn9X/ucDbhXRw`），
 * 光看 path 根本不知道是什么接口。
 */
internal val CaptureRecord.apiTitle: String
    get() {
        // resolveDescription() 会在记录里没存说明时按当前映射现查一次，
        // 这样历史记录也能显示说明，而不是只对它生效之后抓的请求生效
        resolveDescription()?.takeIf { it.isNotBlank() }?.let { return it }
        val method = retrofitMethod?.takeIf { it.isNotBlank() } ?: return ""
        val service = retrofitService?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
        return if (service == null) method else "$service#$method"
    }

/** 域名被改写时返回 "原 host → 实际 host"，否则返回 null。 */
internal val CaptureRecord.rewriteDescription: String?
    get() {
        val original = originalUrl ?: return null
        val originalHost = original.substringAfter("://", "").substringBefore('/')
        if (originalHost.isEmpty() || originalHost == host) {
            return null
        }
        return "$originalHost → $host"
    }
