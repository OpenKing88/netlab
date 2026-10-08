package probe.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Compose 可行性探针。
 *
 * 整个模块用 Kotlin 2.0.21 + Compose 编译器 2.0.21 编译，Compose 依赖走 compileOnly，
 * 用来验证：一个预编译的 Compose AAR 能否被宿主正常消费，以及会不会污染宿主的 Compose 版本。
 */
@Composable
fun ProbeComposeScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF102030)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "compose probe from library",
            color = Color.White,
            modifier = Modifier.padding(24.dp),
        )
    }
}
