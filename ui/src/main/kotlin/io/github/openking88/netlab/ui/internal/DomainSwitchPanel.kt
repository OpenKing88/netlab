package io.github.openking88.netlab.ui.internal

/**
 * 面板可用性判定。
 *
 * <p>面板 Activity 继承宿主的 androidx.activity.ComponentActivity，UI 内容又依赖 Compose
 * （本库对这两者都是 compileOnly）。纯 View 宿主没接这些依赖时：
 *
 * <ul>
 *   <li>加载这个类会因为父类缺失直接抛 NoClassDefFoundError；</li>
 *   <li>而且它发生在 ContentProvider 的创建阶段（进程启动期），不拦就是"接上库 App 一启动就崩"；</li>
 *   <li>就算拦住了启动崩溃，通知和桌面入口点下去仍然会在实例化 Activity 时崩。</li>
 * </ul>
 *
 * <p>所以这里的结论是：**面板不可用时，整个 UI 模块静默退出** —— 不崩宿主，也不露出
 * "点了就崩"的入口。正确的用法仍然是纯 View 宿主显式关掉面板（domainSwitch 里把
 * includeUi 设为 false）。
 */
internal object DomainSwitchPanel {

    /** 必须与 AndroidManifest 里声明的类名一致。 */
    private const val ACTIVITY_CLASS = "io.github.openking88.netlab.ui.DomainSwitchPanelActivity"

    /**
     * 面板能否真正启动。用 Class.forName 提前探一次：父类缺失时这里就会失败，
     * 而不是等到用户点开入口才崩。
     */
    fun isAvailable(): Boolean = runCatching {
        Class.forName(ACTIVITY_CLASS, false, DomainSwitchPanel::class.java.classLoader)
    }.isSuccess
}
