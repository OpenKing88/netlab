package io.github.openking88.netlab.ui.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 面板模块的零代码初始化入口：只负责把通知栏入口挂上。
 *
 * <p>核心能力（域名规则、抓包存储）的初始化在 core 模块自己的 provider 里，两者互不依赖。
 */
class DomainSwitchUiInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        // ⚠️ 这里必须整体兜住 Throwable，而不是只 catch Exception。
        //
        // 面板依赖宿主的 androidx.activity + Compose（本库对它们是 compileOnly）。
        // 纯 View 宿主没接这些依赖时，加载 DomainSwitchPanelActivity 会因为父类缺失直接抛
        // NoClassDefFoundError —— 而且它发生在 **ContentProvider 的创建阶段，也就是进程启动阶段**，
        // 不拦的话表现为"接上库以后 App 一启动就崩"，比"没有入口"糟得多。
        //
        // 取舍：面板不可用时整个 UI 模块静默退出 —— 不崩宿主，也不露出"点了就崩"的入口。
        // 正确的用法仍然是纯 View 宿主显式关掉面板：domainSwitch { includeUi.set(false) }
        runCatching {
            if (DomainSwitchPanel.isAvailable()) {
                DomainSwitchUiNotification.start(ctx)
                // 通知不可用时把桌面兜底入口亮出来（通知可用时自动收回）
                DomainSwitchLauncherEntry.sync(ctx)
            } else {
                // 顺手收掉可能残留的桌面入口（组件状态是持久化的）
                DomainSwitchLauncherEntry.hide(ctx)
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
