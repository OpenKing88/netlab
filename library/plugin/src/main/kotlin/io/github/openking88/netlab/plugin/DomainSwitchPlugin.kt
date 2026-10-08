package io.github.openking88.netlab.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * 按渠道接入的域名切换插件。
 *
 * 职责边界：
 * 1. 判定渠道是否在白名单内；
 * 2. 命中的渠道 → 补运行时依赖 + 开启字节码插桩；
 * 3. 命中的渠道 → 自动从该渠道的 buildConfigField 里提取域名，作为候选清单注入资源；
 * 3. 未命中的渠道 → 什么都不做，保证生产渠道零痕迹。
 */
class DomainSwitchPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "domainSwitch",
            DomainSwitchExtension::class.java
        )

        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error(
                "[netlab] 插件必须应用在 Android 模块上，" +
                        "未在 ${project.path} 找到 AndroidComponentsExtension"
            )

        androidComponents.onVariants { variant ->
            val flavorNames = variant.productFlavors.map { it.second }
            val targets = extension.flavors.get()
            val enabled = when {
                targets.isEmpty() -> {
                    project.logger.warn(
                        "[netlab] 未配置 domainSwitch.flavors，将对所有 variant 生效，" +
                                "生产渠道请显式配置渠道白名单"
                    )
                    true
                }

                else -> flavorNames.any { it in targets }
            }

            if (!enabled) {
                project.logger.lifecycle(
                    "[netlab] 渠道 ${flavorNames.ifEmpty { listOf("<none>") }} " +
                            "不在白名单 $targets 内，variant=${variant.name} 跳过"
                )
                return@onVariants
            }

            if (extension.autoAddDependency.get()) {
                val configurationBaseName = variant.flavorName
                    ?.takeIf { it.isNotEmpty() }
                    ?: variant.name
                addRuntimeDependency(
                    project = project,
                    extension = extension,
                    variantName = configurationBaseName
                )
            }

            registerConfiguredHosts(project = project, variant = variant, extension = extension)

            variant.instrumentation.transformClassesWith(
                DomainSwitchClassVisitorFactory::class.java,
                // ⚠️ 只处理"应用插件的那个模块"（PROJECT）。
                //
                // 曾经尝试过 ALL（连带插桩依赖，好覆盖独立 library 模块里的 OkHttpClient），
                // 直接失败：ALL 会把 okhttp 自身也插桩，而我们重写的正是 okhttp 的调用点，
                // 等于把自己的改造再改一遍，dex 阶段就崩了。
                //
                // 多模块因此是已知限制，详见 docs/clean-architecture.md 第十四节。
                InstrumentationScope.PROJECT
            ) { }
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )

            project.logger.lifecycle("[netlab] variant=${variant.name} 已开启域名切换插桩")
        }
    }

    /**
     * 从该 variant 生效的 buildConfigField 中提取所有 URL 型常量，取其 host 作为候选域名。
     *
     * 这一步是"自动检测域名配置入口"的落地：渠道里写了哪些域名（BASE_URL、各种 H5 地址……）
     * 不需要人工维护第二份清单，直接由构建期扫描得出。
     */
    private fun registerConfiguredHosts(
        project: Project,
        variant: Variant,
        extension: DomainSwitchExtension
    ) {
        val resDirectories = variant.sources.res
        if (resDirectories == null) {
            project.logger.warn(
                "[netlab] variant=${variant.name} 没有 res 源集，跳过域名清单生成"
            )
            return
        }
        val capitalizedVariantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val taskName = "generate${capitalizedVariantName}DomainSwitchHosts"
        val taskProvider = project.tasks.register<GenerateDomainSwitchHostsTask>(taskName) {
            // 关键：用 Provider 映射而不是 get()，把读取推迟到任务执行期
            val fields = variant.buildConfigFields?.map { buildConfigFields ->
                buildConfigFields
                    .filterValues { field -> field.type == "String" }
                    .mapValues { entry -> entry.value.value.toString() }
            }
            if (fields != null) {
                configuredFields.set(fields)
            }
            captureEnabled.set(extension.capture.get())
            maxRecords.set(extension.maxRecords.get())
            maxBodyBytes.set(extension.maxBodyBytes.get())
            // KDoc 不会进字节码，只能在编译期从源码里捞
            apiSources.from(
                project.fileTree(project.projectDir) {
                    include("src/**/java/**/*.kt", "src/**/java/**/*.java", "src/**/kotlin/**/*.kt")
                    exclude("**/build/**")
                }
            )
            outputDirectory.set(
                project.layout.buildDirectory.dir("generated/domainSwitch/${variant.name}/res")
            )
        }
        resDirectories.addGeneratedSourceDirectory(
            taskProvider,
            GenerateDomainSwitchHostsTask::outputDirectory
        )
    }

    private fun addRuntimeDependency(
        project: Project,
        extension: DomainSwitchExtension,
        variantName: String
    ) {
        // 渠道维度对应的 configuration 名是 <flavorName>Implementation
        val configurationName = "${variantName}Implementation"
        val exists = project.configurations.findByName(configurationName) != null
        if (!exists) {
            project.logger.warn(
                "[netlab] 未找到 configuration=$configurationName，" +
                        "请手动添加运行时依赖：${extension.runtimeDependency.get()}"
            )
            return
        }
        project.dependencies.add(configurationName, extension.runtimeDependency.get())
        if (extension.includeUi.get()) {
            project.dependencies.add(configurationName, extension.uiDependency.get())
        }
    }

}
