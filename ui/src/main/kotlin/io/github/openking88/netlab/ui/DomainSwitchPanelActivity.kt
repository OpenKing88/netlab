package io.github.openking88.netlab.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.openking88.netlab.ui.theme.DomainSwitchTheme

/**
 * 域名切换与抓包面板的宿主 Activity。
 *
 * <p>必须继承 [ComponentActivity]：Compose 依赖 ViewTreeLifecycleOwner 来建立 recomposer，
 * 普通 Activity 上挂 ComposeView 会直接崩在 createLifecycleAwareWindowRecomposer。
 */
class DomainSwitchPanelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DomainSwitchTheme {
                DomainSwitchPanelScreen()
            }
        }
    }

    companion object {

        /** 供宿主或 adb 打开面板。 */
        @JvmStatic
        fun show(context: Context) {
            val intent = Intent(context, DomainSwitchPanelActivity::class.java)
            if (context !is ComponentActivity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
