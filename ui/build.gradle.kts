import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

plugins {
    id("com.android.library") version "8.13.2"
    // ⚠️ 刻意锁在 Kotlin 2.0.21：这是我们要支持的宿主最低版本。
    // Kotlin 元数据版本 = 编译器版本，用更高版本编译会让更老的宿主编译直接失败，
    // 即使用户源码一行都没引用本库。详见 docs/clean-architecture.md 的兼容性实验。
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    `maven-publish`
    `signing`
}

android {
    namespace = "io.github.openking88.netlab.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 让 release 组件带上 sources jar（Central 强制要求这个制品）
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 依赖 core 的运行时 API（DomainSwitch / CaptureStore）
    api("io.github.openking88:netlab:1.0.0")

    // Compose 全部走 compileOnly：库只在编译期需要它，运行期由宿主自己的 Compose 提供。
    // 这样 POM 里不会出现 Compose，既不抬宿主的版本，也不会给纯 View 宿主塞进整套 Compose。
    // 刻意用较老的 BOM（Compose 1.7），对应"库编译在支持范围内最老版本"的策略。
    compileOnly(platform("androidx.compose:compose-bom:2024.09.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    // Compose 要靠 ViewTreeLifecycleOwner 才能建立 recomposer，普通 Activity 没有，
    // 所以必须用 ComponentActivity（任何 Compose 宿主都必然有 activity-compose）。
    compileOnly("androidx.activity:activity-compose:1.9.2")
}

group = "io.github.openking88"
version = "1.0.0"

// ─────────── Maven Central 发布支持（与 core 同一套约定）───────────
val netlabRepoUrl: String? = providers.gradleProperty("netlab.repo.url").orNull
val netlabRepoUser: String? = providers.gradleProperty("netlab.repo.user").orNull
val netlabRepoPassword: String? = providers.gradleProperty("netlab.repo.password").orNull
val netlabSigningKey: String? = providers.gradleProperty("signingKey").orNull
val netlabSigningPassword: String? = providers.gradleProperty("signingPassword").orNull

val netlabJavadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    archiveBaseName.set("netlab-ui")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("ui") {
                // 与 core 不同，UI 模块确实有运行时依赖（core + kotlin-stdlib），
                // 所以走组件发布把依赖声明出来；Compose 是 compileOnly，不会出现在这里。
                from(components["release"])
                artifact(netlabJavadocJar)
                groupId = "io.github.openking88"
                artifactId = "netlab-ui"
                version = "1.0.0"
                pom {
                    name.set("netlab-ui")
                    description.set("netlab 的可视化面板：域名切换 + 抓包列表与详情")
                    url.set("https://github.com/OpenKing88/netlab")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("OpenKing88")
                            name.set("OpenKing88")
                            url.set("https://github.com/OpenKing88")
                        }
                    }
                    scm {
                        url.set("https://github.com/OpenKing88/netlab")
                        connection.set("scm:git:git://github.com/OpenKing88/netlab.git")
                        developerConnection.set("scm:git:ssh://git@github.com/OpenKing88/netlab.git")
                    }
                }
            }
        }
        repositories {
            if (netlabRepoUrl != null) {
                maven {
                    name = "netlab"
                    url = uri(netlabRepoUrl)
                    if (netlabRepoUser != null) {
                        credentials {
                            username = netlabRepoUser
                            password = netlabRepoPassword
                        }
                    }
                }
            }
        }
    }
    if (netlabSigningKey != null && netlabSigningPassword != null) {
        signing {
            useInMemoryPgpKeys(netlabSigningKey, netlabSigningPassword)
            sign(publishing.publications)
        }
    }
}

/**
 * 防复发门禁：扫描编译产物，禁止调用 Compose **接口作用域**里的默认参数桥接。
 *
 * 背景（真机踩出来的）：`Modifier.weight(1f)` 这种接口成员 + 默认参数的写法，Kotlin 会生成
 * 静态桥接 `RowScope.weight$default(...)`。新版 Compose 换掉了这套桥接，于是出现
 * "编译期一切正常、真机 `NoSuchMethodError`" —— 在该项目的 Compose 1.10.4 上就是这么崩的，
 * 同样代码在 Compose 1.10.6 上却没事，极难排查。
 *
 * 顶层函数的 `$default`（SizeKt / PaddingKt 之类）不受影响，所以只拦接口作用域这一类。
 * 修法很简单：把默认参数显式写全，例如 `Modifier.weight(1f, fill = true)`。
 */
val verifyComposeBridges by tasks.registering {
    group = "verification"
    description = "检查是否调用了 Compose 接口作用域里跨版本不稳定的 \$default 桥接"

    val aarDirectory = layout.buildDirectory.dir("outputs/aar")
    val workDirectory = layout.buildDirectory.dir("composeBridgeCheck")

    dependsOn("assembleRelease")
    inputs.dir(aarDirectory)
    outputs.upToDateWhen { false }

    doLast {
        val aar = aarDirectory.get().asFile
            .listFiles()
            ?.firstOrNull { it.name.endsWith(".aar") }
            ?: error("没有找到 AAR 产物，请先执行 assembleRelease")

        val work = workDirectory.get().asFile
        work.deleteRecursively()
        work.mkdirs()
        // 手工解包而不是用 copy {}：后者会捕获 Gradle 脚本对象，configuration cache 无法序列化
        val classesJar = File(work, "classes.jar")
        ZipInputStream(aar.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "classes.jar") {
                    classesJar.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val classesDirectory = File(work, "classes")
        classesDirectory.mkdirs()
        ZipInputStream(classesJar.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val target = File(classesDirectory, entry.name)
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val javap = File(System.getProperty("java.home"), "bin/javap").absolutePath
        // 普通字符串而不是原始字符串：原始字符串里 \$ 不会被转义，$default 会被当成模板引用
        val scopeBridge = Regex(
            "(RowScope|ColumnScope|LazyItemScope|LazyListScope|LazyGridScope)[^\\s]*\\\$default"
        )

        val offenders = mutableListOf<String>()
        classesDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
                val className = file.relativeTo(classesDirectory).path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                val output = ByteArrayOutputStream()
                val process = ProcessBuilder(
                    javap, "-c", "-p", "-cp", classesDirectory.absolutePath, className
                ).redirectErrorStream(true).start()
                process.inputStream.copyTo(output)
                process.waitFor()
                output.toString("UTF-8").lineSequence()
                    .filter { scopeBridge.containsMatchIn(it) }
                    .forEach { offenders += "$className → ${it.trim()}" }
            }

        if (offenders.isNotEmpty()) {
            error(
                "发现 Compose 接口作用域的默认参数桥接调用，这在较老的 Compose 宿主上会 NoSuchMethodError。\n" +
                        "把默认参数显式写全即可（例如 Modifier.weight(1f, fill = true)）：\n" +
                        offenders.joinToString(separator = "\n")
            )
        }
        logger.lifecycle("[netlab-ui] Compose 桥接检查通过：没有接口作用域的 \$default 调用")
    }
}

tasks.named("check") {
    dependsOn(verifyComposeBridges)
}
