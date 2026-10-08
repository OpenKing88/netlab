package io.github.openking88.netlab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 极简主题。
 *
 * <p>刻意不引入动态取色 / 自定义字体等宿主级配置：这是个调试面板，
 * 宿主可能自己就设了 MaterialTheme，这里只需要保证控件能正常渲染。
 */
@Composable
fun DomainSwitchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2962FF),
        ),
        content = content,
    )
}
