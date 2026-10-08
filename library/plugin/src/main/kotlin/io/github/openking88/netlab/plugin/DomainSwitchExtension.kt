package io.github.openking88.netlab.plugin

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

/**
 * `domainSwitch { ... }` 的配置项。
 *
 * 设计原则：只有"渠道白名单"是必填语义，其余都有安全默认值。
 */
abstract class DomainSwitchExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * 需要接入域名切换的渠道（flavor）名白名单，命中之外的渠道：
     * 不插桩 + 不加依赖，产物里零痕迹。
     *
     * 留空表示对所有 variant 生效（会打警告，仅建议用于没有渠道维度的工程）。
     */
    val flavors: SetProperty<String> = objects.setProperty(String::class.java).convention(emptySet())

    /**
     * 运行时库坐标。插件会按命中的渠道自动加到对应 configuration，
     * 宿主因此不需要手写任何 dependencies。
     */
    val runtimeDependency: Property<String> = objects.property(String::class.java)
        .convention("io.github.openking88:netlab:1.0.0")

    /** 是否由插件自动补充运行时依赖。关掉后需要宿主自己按渠道声明依赖。 */
    val autoAddDependency: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /**
     * 是否同时引入可视化面板（Compose）。
     *
     * <p>面板依赖宿主的 Compose（本库对 Compose 是 compileOnly），所以纯 View 的宿主
     * 应当关掉它，只用核心能力。
     */
    val includeUi: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /** 面板制品坐标。 */
    val uiDependency: Property<String> = objects.property(String::class.java)
        .convention("io.github.openking88:netlab-ui:1.0.0")

    /** 是否录制网络请求。关掉后抓包拦截器直接放行，零开销。 */
    val capture: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /** 内存与落库保留的最大记录条数。 */
    val maxRecords: Property<Int> = objects.property(Int::class.java)
        .convention(500)

    /** 单条 body 的捕获上限（字节）。超过就只标记状态、不占内存。 */
    val maxBodyBytes: Property<Int> = objects.property(Int::class.java)
        .convention(512 * 1024)
}
