package io.github.openking88.netlab.ui.capture

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.openking88.netlab.capture.CaptureStore

/**
 * 抓包 Tab：列表 ↔ 详情 的内部导航，以及和 [CaptureStore] 的订阅。
 *
 * <p>订阅走 [CaptureStore.Listener]，回调已经在主线程，这里只负责让组合失效重读一次快照。
 */
@Composable
internal fun CaptureTab() {
    var revision by remember { mutableStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val store = CaptureStore.peek()

    DisposableEffect(store) {
        val listener = CaptureStore.Listener { revision++ }
        store?.addListener(listener)
        onDispose { store?.removeListener(listener) }
    }

    val records = remember(revision) { store?.records().orEmpty() }
    val selected = records.firstOrNull { it.id == selectedId }

    if (selected != null) {
        BackHandler { selectedId = null }
        CaptureDetailScreen(
            record = selected,
            onBack = { selectedId = null },
        )
    } else {
        CaptureListScreen(
            records = records,
            onOpen = { selectedId = it.id },
            onClear = { store?.clear() },
        )
    }
}
