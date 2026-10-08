package io.github.netlab.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI

/**
 * 把宿主该渠道里配置的域名抽取出来，生成 `res/values/domain_switch_hosts.xml`。
 *
 * 为什么用独立 Task 而不是在 `onVariants` 里直接读 `buildConfigFields`：
 * 配置阶段拿不到最终值，AGP 会直接报
 * "Cannot query the value of property 'buildConfigFields' because configuration ... has not completed yet"。
 * 通过 Provider 把读取推迟到执行期才是正确姿势，同时天然兼容 configuration cache。
 */
abstract class GenerateDomainSwitchHostsTask : DefaultTask() {

    /** 该 variant 生效的全部 buildConfigField（name → 原始字面量）。 */
    @get:Input
    abstract val configuredFields: MapProperty<String, String>

    @get:Input
    abstract val captureEnabled: Property<Boolean>

    @get:Input
    abstract val maxRecords: Property<Int>

    @get:Input
    abstract val maxBodyBytes: Property<Int>

    /**
     * 参与接口说明提取的源码。
     *
     * <p>为什么需要它：拦截器能靠 Retrofit 的 Invocation 定位到「哪个接口的哪个方法」，
     * 但 KDoc 不会被编译进字节码 —— 只能在编译期从源码里读出来，生成映射打进包。
     */
    @get:InputFiles
    abstract val apiSources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val links = sortedMapOf<String, String>()
        configuredFields.get().forEach { (fieldName, rawValue) ->
            val host = extractHost(rawValue) ?: return@forEach
            val link = classify(fieldName = fieldName)
            val current = links[host]
            // 同一个 host 可能出现在多个字段里：可切换的链路优先级更高
            if (current == null || priorityOf(link) > priorityOf(current)) {
                links[host] = link
            }
        }

        val valuesDirectory = outputDirectory.get().asFile.resolve("values").apply { mkdirs() }
        val entries = links.entries.joinToString(separator = ",") { entry ->
            "${entry.key}=${entry.value}"
        }
        File(valuesDirectory, HOSTS_RESOURCE_FILE_NAME).writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="domain_switch_hosts" translatable="false">$entries</string>
            </resources>
            """.trimIndent()
        )
        File(valuesDirectory, CONFIG_RESOURCE_FILE_NAME).writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="domain_switch_capture_enabled" translatable="false">${captureEnabled.get()}</string>
                <string name="domain_switch_max_records" translatable="false">${maxRecords.get()}</string>
                <string name="domain_switch_max_body_bytes" translatable="false">${maxBodyBytes.get()}</string>
            </resources>
            """.trimIndent()
        )

        val descriptions = scanApiDescriptions()
        File(valuesDirectory, API_DESCRIPTIONS_RESOURCE_FILE_NAME).writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="domain_switch_api_descriptions" translatable="false">${escapeForResource(descriptions)}</string>
            </resources>
            """.trimIndent()
        )
        logger.lifecycle(
            "[netlab] 自动识别到域名：$links；" +
                    "capture=${captureEnabled.get()} maxRecords=${maxRecords.get()} " +
                    "maxBodyBytes=${maxBodyBytes.get()}；" +
                    "接口说明 ${descriptions.split("\\n").count { it.isNotBlank() }} 条"
        )
    }

    // ─────────────────── 接口说明提取 ───────────────────

    /**
     * 从源码里提取「接口全名#方法名 → 注释说明」。
     *
     * 刻意用保守的逐行扫描而不是完整语法解析：KDoc/Javadoc 不会进字节码，
     * 这里只是为了拿一句人话，解析失败就跳过 —— 用 kotlin-compiler-embeddable 的 PSI
     * 准确度更高，但要背几十 MB 的插件体积和更慢的构建，不划算。
     */
    private fun scanApiDescriptions(): String {
        val entries = LinkedHashMap<String, String>()
        apiSources.files
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                runCatching { scanSourceFile(file, entries) }
            }
        return entries.entries.joinToString(separator = "\\n") { entry ->
            "${entry.key}=${entry.value}"
        }
    }

    private fun scanSourceFile(file: File, out: MutableMap<String, String>) {
        val lines = file.readLines()
        var packageName = ""
        var declarationName: String? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            when {
                line.startsWith("package ") ->
                    packageName = line.removePrefix("package ").trim().removeSuffix(";")

                DECLARATION.containsMatchIn(line) -> {
                    val found = DECLARATION.find(line)
                    // 只收 interface：Retrofit 服务只能是接口，
                    // 不筛的话会把 Activity/工具类里带注释的方法也一起收进来（实测多出 4 倍）
                    declarationName = if (found?.groupValues?.get(1) == "interface") {
                        found.groupValues[2]
                    } else {
                        null
                    }
                }

                line.startsWith("/**") -> {
                    val comment = StringBuilder()
                    var cursor: Int
                    if (line.endsWith("*/")) {
                        comment.append(line.removePrefix("/**").removeSuffix("*/"))
                        cursor = index + 1
                    } else {
                        cursor = index + 1
                        while (cursor < lines.size && !lines[cursor].trim().endsWith("*/")) {
                            comment.append(lines[cursor].trim().removePrefix("*")).append(' ')
                            cursor++
                        }
                        cursor++
                    }
                    // 跳过注解和空行，找到方法声明
                    var probe = cursor
                    while (probe < lines.size &&
                        (lines[probe].isBlank() || lines[probe].trim().startsWith("@"))
                    ) {
                        probe++
                    }
                    val match = METHOD_NAME.find(lines.getOrNull(probe)?.trim().orEmpty())
                    // 两个分支各占一个捕获组：Kotlin 的 fun 名在 1，Java 的方法名在 2
                    val methodName = match?.let { found ->
                        found.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                            ?: found.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    }
                    val summary = summarize(comment.toString())
                    if (methodName != null && summary.isNotEmpty() && !declarationName.isNullOrEmpty()) {
                        val owner = if (packageName.isEmpty()) {
                            declarationName
                        } else {
                            "$packageName.$declarationName"
                        }
                        out.putIfAbsent("$owner#$methodName", summary)
                    }
                    index = cursor
                    continue
                }
            }
            index++
        }
    }

    /** 只取注释第一句人话，并砍掉 Javadoc 的标签行。 */
    private fun summarize(comment: String): String {
        val first = comment.split(' ', '\n')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("@") }
            ?: return ""
        return if (first.length > MAX_DESCRIPTION_LENGTH) {
            first.substring(0, MAX_DESCRIPTION_LENGTH)
        } else {
            first
        }
    }

    /** 资源字符串里的换行、引号、尖括号都要转义，否则编译不过或语义被改。 */
    private fun escapeForResource(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
    }

    /**
     * 按字段名初判这个域名走哪条链路。
     *
     * 编译期拿不到数据流，只能靠命名约定给个初判；运行期拦截器观测到的 host 会覆盖这个结论
     * （见 DomainSwitch.linkOf）。
     */
    private fun classify(fieldName: String): String {
        val name = fieldName.uppercase()
        return when {
            // 接口类：BASE_URL / API_* / *_SERVER
            name == "BASE_URL" || name.contains("API") || name.contains("SERVER") -> "okhttp"
            // 网页类：URL_WEB_* / URL_MINE_* / *_H5_*
            name.contains("WEB") || name.contains("H5") || name.contains("HTML") -> "webview"
            name.startsWith("URL_") -> "webview"
            else -> "unknown"
        }
    }

    private fun priorityOf(link: String): Int = when (link) {
        "okhttp" -> 2
        "webview" -> 1
        else -> 0
    }

    private fun extractHost(rawValue: String): String? {
        // buildConfigField 存的是 Java 字面量，可能带一层引号
        val candidate = rawValue.trim().trim('"').trim()
        if (!candidate.startsWith(prefix = "https://", ignoreCase = true) &&
            !candidate.startsWith(prefix = "http://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching { URI(candidate).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
    }

    companion object {
        const val HOSTS_RESOURCE_FILE_NAME = "domain_switch_hosts.xml"
        const val CONFIG_RESOURCE_FILE_NAME = "domain_switch_config.xml"
        const val API_DESCRIPTIONS_RESOURCE_FILE_NAME = "domain_switch_api_descriptions.xml"

        private const val MAX_DESCRIPTION_LENGTH = 60

        private val DECLARATION = Regex(
            """^(?:(?:public|internal|private|abstract|open|sealed|final|data|enum)\s+)*""" +
                    """(interface|class|object)\s+([A-Za-z_$][\w$]*)"""
        )

        private val METHOD_NAME = Regex(
            """(?:^|\s)fun\s+(?:<[^>]*>\s*)?([A-Za-z_$][\w$]*)\s*\(""" +
                    """|^[A-Za-z_$][\w$<>\[\],.\s]*\s+([A-Za-z_$][\w$]*)\s*\("""
        )
    }
}
