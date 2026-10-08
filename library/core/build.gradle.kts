import org.gradle.api.publish.maven.MavenPublication
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.netlab"
    compileSdk = 36

    defaultConfig {
        // 比宿主更低的 minSdk，避免把宿主的最低版本要求顶上去
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 要求显式声明要发布的 variant，之后才有 components["release"] 可用
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // okhttp 只编译期依赖：运行期由宿主自己的 okhttp 提供，
    // 既不会把版本强行塞给宿主，也不会出现两份 OkHttp
    compileOnly(libs.okhttp)
    // 只用来读 request.tag(Invocation.class)，运行期由宿主自己的 Retrofit 提供。
    // 宿主不装 Retrofit 时读不到 tag，会静默降级（见 RetrofitInvocationReader）。
    compileOnly(libs.retrofit)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.retrofit)
}

// 本地验证用：发布到 mavenLocal，让插件能像消费真实制品一样自动注入依赖
group = "io.github.netlab"
version = "1.0.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                // 刻意不用 from(components["release"])：
                // 那样会把 AGP 内置 Kotlin 自动挂上的 kotlin-stdlib 写进发布元数据，
                // 而本库是纯 Java 实现、运行时零依赖。把 2.3.x 的 stdlib 强塞给
                // Kotlin 2.0 的宿主会直接让宿主编译崩在
                // "Module was compiled with an incompatible version of Kotlin"。
                // 只发布 AAR 本体，不声明任何依赖，才是这个库的真实形态。
                artifact(tasks.named("bundleReleaseAar"))
                groupId = "io.github.netlab"
                artifactId = "netlab"
                version = "1.0.0"
            }
        }
    }
}

/**
 * 产物契约门禁。
 *
 * 盯的是已经真实踩过的两个坑：
 *
 * 1. **POM 不能有任何依赖**。AGP 9 的内置 Kotlin 会给库自动挂 `kotlin-stdlib`，
 *    一旦写进发布元数据，宿主解析后就会拿到比它自己更新的 stdlib，
 *    直接以 "Module was compiled with an incompatible version of Kotlin" 崩在宿主编译期
 *    （该项目就是这么挂的）。core 是纯 Java、零运行时依赖，POM 必须保持干净。
 *
 * 2. **字节码里不能引用 kotlin**。这是上一条的物理佐证：只要有一处 `kotlin/...` 引用，
 *    宿主就必然需要 stdlib，compileOnly 又没声明，运行期会 NoClassDefFoundError。
 */
val verifyArtifactContract by tasks.registering {
    group = "verification"
    description = "校验 core 的发布元数据与字节码：零依赖、纯 Java"

    val pomFile = layout.buildDirectory.file("publications/release/pom-default.xml")
    val aarDirectory = layout.buildDirectory.dir("outputs/aar")
    val workDirectory = layout.buildDirectory.dir("artifactContractCheck")

    dependsOn("generatePomFileForReleasePublication", "bundleReleaseAar")
    outputs.upToDateWhen { false }

    doLast {
        val pom = pomFile.get().asFile
        if (!pom.exists()) {
            error("找不到生成的 POM：${pom.absolutePath}")
        }
        if (pom.readText().contains("<dependencies>")) {
            error(
                "core 的 POM 里出现了依赖声明。\n" +
                        "core 必须是零运行时依赖，否则会把版本强加给宿主（kotlin-stdlib 就是这么把该项目编译打挂的）。\n" +
                        "检查 build.gradle.kts 的 publishing 块是否退化成了 from(components[\"release\"])。\n" +
                        pom.readText()
            )
        }

        val aar = aarDirectory.get().asFile
            .listFiles()
            ?.firstOrNull { it.name.endsWith(".aar") }
            ?: error("没有找到 AAR 产物")
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
        val kotlinReference = Regex("""\bkotlin[/.]""")
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
                    .filter { kotlinReference.containsMatchIn(it) }
                    .forEach { offenders += "$className → ${it.trim()}" }
            }
        if (offenders.isNotEmpty()) {
            error(
                "core 的字节码里出现了 kotlin 引用，说明它不再是纯 Java，宿主会需要 stdlib：\n" +
                        offenders.joinToString(separator = "\n")
            )
        }
        logger.lifecycle("[netlab] 产物契约检查通过：POM 零依赖，字节码纯 Java")
    }
}

tasks.named("check") {
    dependsOn(verifyArtifactContract)
}
