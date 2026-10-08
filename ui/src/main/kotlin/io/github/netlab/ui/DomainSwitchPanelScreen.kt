package io.github.netlab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.netlab.DomainLink
import io.github.netlab.DomainSwitch
import io.github.netlab.ui.capture.CaptureTab

private val HintColor = Color(0xFF888888)

/** 当前生效的域名用绿色标出来（和抓包列表里 2xx 的绿保持一致）。 */
private val ActiveHostColor = Color(0xFF2E7D32)

/**
 * 域名切换面板。
 *
 * <p>**按链路分组**是最关键的一点：自动识别只能看到"配置里有哪些 URL 字符串"，
 * 看不到它最后被谁消费。如果把 OkHttp 域名和 WebView 域名并列展示，
 * 用户切了后者会以为生效了、实际什么都没发生，还查不出原因。
 */
@Composable
internal fun DomainSwitchPanelScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("抓包") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("域名切换") },
            )
        }
        when (tab) {
            0 -> CaptureTab()
            else -> DomainSwitchTab()
        }
    }
}

@Composable
private fun DomainSwitchTab() {
    var revision by remember { mutableStateOf(0) }
    var editingItem by remember { mutableStateOf<HostItem?>(null) }

    // 一次性把界面要渲染的内容快照出来，而不是让叶子组件自己去读 DomainSwitch 这个全局单例。
    // 后者 Compose 观察不到它的变化，会跳过重组 —— 表现就是"选了域名但列表不刷新，
    // 切个 tab 回来才生效"。快照之后，叶子组件只吃不可变数据，谁变了谁就会重组。
    val sections = remember(revision) {
        val hosts = DomainSwitch.configuredHosts()
        listOf(
            "网络请求（OkHttp）" to DomainLink.OKHTTP,
            "WebView / H5 页面" to DomainLink.WEBVIEW,
            "未知链路（切换可能无效）" to DomainLink.UNKNOWN,
        ).mapNotNull { (title, link) ->
            val items = hosts
                .filter { DomainSwitch.linkOf(it) == link }
                .map { host ->
                    HostItem(host = host, target = DomainSwitch.targetOf(host), link = link)
                }
            if (items.isEmpty()) null else HostSection(title = title, items = items)
        }
    }
    // 带上"是不是用户自己加的"标记：只有自定义域名可删，
    // 自动从渠道配置里识别出来的默认域名不给删
    val options = remember(revision) {
        val customHosts = DomainSwitch.customTargets().toSet()
        DomainSwitch.targetOptions().map { host ->
            TargetOption(host = host, custom = customHosts.contains(host))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "按链路分组显示。OkHttp 由拦截器改写，WebView 由加载入口改写，未知链路切换可能无效。",
            style = MaterialTheme.typography.bodySmall,
            color = HintColor,
        )

        if (sections.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "没有识别到基线域名。请检查该渠道是否配置了 BASE_URL 一类的地址。",
                style = MaterialTheme.typography.bodyMedium,
                color = HintColor,
            )
        }

        sections.forEach { section ->
            HostGroup(section = section, onPick = { editingItem = it })
        }

        Spacer(Modifier.height(20.dp))
        CustomTargetInput(onAdded = { revision++ })
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                DomainSwitch.clear()
                revision++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("全部还原")
        }
    }

    editingItem?.let { item ->
        TargetPickerSheet(
            item = item,
            options = options,
            onDismiss = { editingItem = null },
            onChanged = { revision++ },
        )
    }
}

@Composable
private fun HostGroup(section: HostSection, onPick: (HostItem) -> Unit) {
    Text(
        text = section.title,
        style = MaterialTheme.typography.labelSmall,
        color = HintColor,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
    section.items.forEach { item ->
        HostRow(item = item, onPick = onPick)
    }
}

@Composable
private fun HostRow(item: HostItem, onPick: (HostItem) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(item) }
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = item.host,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (item.target == null) "未切换" else "${item.target}（已切换）",
            style = MaterialTheme.typography.bodySmall,
            color = if (item.target == null) HintColor else MaterialTheme.colorScheme.primary,
        )
    }
}

/** 界面渲染所需的不可变快照：叶子组件只吃它，不自己去读全局单例。 */
private data class HostItem(val host: String, val target: String?, val link: DomainLink)

private data class HostSection(val title: String, val items: List<HostItem>)

/**
 * 选择器里的一项。
 *
 * [custom] 为 true 表示是用户自己加的候选 —— 只有这种能删；
 * 从渠道配置里自动识别出来的默认域名不给删（删了也没意义，下次构建还会出现）。
 */
private data class TargetOption(val host: String, val custom: Boolean)

/**
 * 弹窗标题：直接说清"在切哪条链路的域名"。
 *
 * 用业务语言（App / H5）而不是技术语言（OkHttp / WebView）——
 * 打开这个弹窗的人想的是"我要把 App 的接口切到测试环境"，而不是"我要改 application interceptor 的 host"。
 */
private fun pickerTitle(link: DomainLink): String = when (link) {
    DomainLink.OKHTTP -> "App 域名切换"
    DomainLink.WEBVIEW -> "H5 域名切换"
    DomainLink.UNKNOWN -> "域名切换"
}

@Composable
private fun CustomTargetInput(onAdded: () -> Unit) {
    var input by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("自定义域名，如 test.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                val host = DomainSwitch.normalizeHost(input)
                if (host != null && DomainSwitch.addCustomTarget(host)) {
                    input = ""
                    onAdded()
                }
            },
        ) {
            Text("添加候选")
        }
    }
}

/**
 * 目标域名选择器，用底部弹窗而不是对话框。
 *
 * 选 material3 的 [ModalBottomSheet] 之前先核对过它的字节码签名：
 * material3 1.3.0 与 1.4.0 上 `ModalBottomSheet-dYc4hso` 的参数表逐字节一致，
 * 不会重演 `weight$default` 那种"编译正常、真机 NoSuchMethodError"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPickerSheet(
    item: HostItem,
    options: List<TargetOption>,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }

    // skipPartiallyExpanded：默认的"半展开"会把下面的选项和按钮裁掉，还得用户手动往上拖。
    // 这是个固定几项的选单，直接全展开更合适；内容超高时它自己会滚动。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                // 底部弹窗贴着屏幕底部，不额外让开导航栏的话最后一个按钮会被压住
                .navigationBarsPadding(),
        ) {
            Text(
                text = pickerTitle(item.link),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 基线域名：同一类链路下可能有多个，说清楚现在改的是哪一个
            Text(
                text = item.host,
                style = MaterialTheme.typography.labelSmall,
                color = HintColor,
            )
            Text(
                text = item.target?.let { "已切换到 $it" } ?: "未切换",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (item.target == null) HintColor else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            // 这里刻意不放"还原"之类的动作项：
            // 原始域名本来就在下面的列表里（自动从渠道配置识别出来的），
            // 选它就是不切换 —— core 里 target == source 会直接删掉那条映射。
            options.forEach { option ->
                // 没切换时生效的就是基线域名本身，所以拿 "target ?: 基线" 去比 ——
                // 这样无论切没切，列表里总有一个绿的，一眼就知道现在实际打的是哪个域名。
                val isActive = option.host.equals(item.target ?: item.host, ignoreCase = true)
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.host,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) ActiveHostColor else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .clickable {
                                DomainSwitch.setTarget(item.host, option.host)
                                    onChanged()
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                        )
                        if (option.custom) {
                            TextButton(
                                onClick = {
                                    // 删候选；如果它正被某个映射使用，core 里会连带把映射一并还原
                                    DomainSwitch.removeCustomTarget(option.host)
                                    onChanged()
                                },
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("自定义域名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        val host = DomainSwitch.normalizeHost(custom)
                        if (host != null) {
                            DomainSwitch.addCustomTarget(host)
                        DomainSwitch.setTarget(item.host, host)
                            onChanged()
                            onDismiss()
                        }
                    },
                ) {
                    Text("使用这个域名")
                }
        }
    }
}

@Composable
private fun PickerItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
