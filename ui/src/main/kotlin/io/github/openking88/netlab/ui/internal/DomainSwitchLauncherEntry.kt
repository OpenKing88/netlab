package io.github.openking88.netlab.ui.internal

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.openking88.netlab.internal.ResourceConfig

/**
 * 通知不可用时的兜底入口：按需显示/隐藏桌面图标（activity-alias）。
 *
 * <p>背景：通知栏常驻入口是主入口，但 Android 13+ 需要 POST_NOTIFICATIONS 运行时权限。
 * 宿主没申请（或用户关掉通知）时，[DomainSwitchUiNotification.refresh] 会走
 * {@code areNotificationsEnabled() == false} 的分支**静默跳过发通知** —— 不崩、不报错，
 * 表现就是"插件装上了但入口没了"，只能靠 adb 进面板。
 *
 * <p>这里给一条**不依赖任何权限**的退路：桌面图标。只在通知不可用时出现，
 * 通知恢复后自动收回，不会长期占着桌面。
 *
 * <p>开关由插件的 {@code domainSwitch { launcherEntry } } 决定，通过资源下发，
 * 因此白名单外的渠道（生产包）既不生成资源也不会有这个图标。
 */
internal object DomainSwitchLauncherEntry {

    /** 必须与 AndroidManifest 里 activity-alias 的 android:name 完全一致。 */
    private const val ALIAS_CLASS = "io.github.openking88.netlab.ui.DomainSwitchLauncherAlias"

    private const val CONFIG_RESOURCE = "domain_switch_launcher_entry"

    /**
     * 按当前状态同步桌面入口的可见性。幂等，可以在每次进程启动时调用。
     */
    fun sync(context: Context) {
        // 整个流程都不允许影响宿主的启动：alias 不存在、被 OEM 限制等都要静默降级
        runCatching {
            val enabledByHost = ResourceConfig.getBoolean(context, CONFIG_RESOURCE, true)
            val notificationsAvailable = areNotificationsAvailable(context)
            setAliasEnabled(context, enabledByHost && !notificationsAvailable)
        }
    }

    /**
     * 强制收起入口。
     *
     * <p>面板不可用时（宿主没接 androidx.activity / Compose）调用：组件状态是**持久化**的，
     * 而且可能在更早的版本里被打开过，不主动收掉就会残留一个"点了就崩"的桌面图标。
     */
    fun hide(context: Context) {
        runCatching { setAliasEnabled(context, false) }
    }

    private fun areNotificationsAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        // areNotificationsEnabled 要 API 24；更低版本没有这个开关，直接认为可用
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.areNotificationsEnabled()
        } else {
            true
        }
    }

    private fun setAliasEnabled(context: Context, enabled: Boolean) {
        val component = ComponentName(context.packageName, ALIAS_CLASS)
        val desired = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val manager = context.packageManager
        if (manager.getComponentEnabledSetting(component) == desired) {
            return
        }
        // DONT_KILL_APP：改组件状态绝不能把宿主进程杀掉
        manager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
    }
}
