import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `kotlin-dsl`
    `maven-publish`
    `signing`
}

group = "io.github.openking88"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 只声明 AGP 的 public API，并且刻意编译在**支持范围内最旧的版本**上（8.13.2），
    // 这样同一个插件制品既能跑 AGP 8.13，也能跑 AGP 9.x。
    // 注意：gradle-api 的 pom 里已经把 org.ow2.asm:asm 作为 compile 作用域依赖带进来了，
    // 所以这里刻意不再单独声明 ASM，避免插件自带一份 ASM 与 AGP 自带的版本冲突
    // （ClassVisitor 的类来自不同 ClassLoader 会直接抛 ClassCastException）。
    compileOnly("com.android.tools.build:gradle-api:8.13.2")
}

gradlePlugin {
    plugins {
        register("domainSwitch") {
            id = "io.github.openking88.netlab"
            implementationClass = "io.github.openking88.netlab.plugin.DomainSwitchPlugin"
        }
    }
}

// ─────────── Maven Central 发布支持（与 core / ui 同一套约定）───────────
val netlabRepoUrl: String? = providers.gradleProperty("netlab.repo.url").orNull
val netlabRepoUser: String? = providers.gradleProperty("netlab.repo.user").orNull
val netlabRepoPassword: String? = providers.gradleProperty("netlab.repo.password").orNull
val netlabSigningKey: String? = providers.gradleProperty("signingKey").orNull
val netlabSigningPassword: String? = providers.gradleProperty("signingPassword").orNull

val netlabJavadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    // 默认取项目名（这里是 build-logic），产物文件名和坐标对不上
    archiveBaseName.set("netlab-gradle-plugin")
}

// java-gradle-plugin 会为「插件本体」和「plugin marker」各创建一个 publication。
// Central 对**每一个**制品都要求 POM 元数据完整，所以这里统一补。
publishing {
    publications.withType<MavenPublication>().configureEach {
        // 插件本体的默认 artifactId 取自项目名，也就是 "plugin" —— 太泛了。
        // 改成 netlab-gradle-plugin；marker 制品会跟着指向新坐标。
        if (name == "pluginMaven") {
            artifactId = "netlab-gradle-plugin"
            // javadoc 制品只挂给插件本体。
            //
            // 两个 publication 共用同一个 jar 文件时，两个 Sign 任务会往同一个 .asc
            // 路径写，Gradle 会以 "uses this output of task X without declaring an
            // explicit or implicit dependency" 直接判失败（真踩到过）。
            // marker 是 packaging=pom 的坐标转发件，Central 的"非 pom 制品必须提供
            // sources/javadoc"要求不适用于它，不挂也不会被拒。
            artifact(netlabJavadocJar)
        }
        pom {
            name.set(artifactId)
            description.set("netlab 的 Gradle 插件：按渠道注入依赖并插桩 OkHttp / WebView 的调用点")
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
