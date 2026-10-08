import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

android {
    namespace = "io.github.openking88.netlab.probe.compose"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 关键：Compose 全部走 compileOnly。
    // 库只在编译期需要它，运行期由宿主自己的 Compose 提供 —— 这样 POM 零依赖，
    // 既不会把宿主的 Compose 抬版本，也不会给纯 View 的宿主塞进整套 Compose。
    // 刻意用较老的 BOM（Compose 1.7），验证"库编译在支持范围内最老版本"是否成立。
    compileOnly(platform("androidx.compose:compose-bom:2024.09.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
}

group = "io.github.openking88"
version = "1.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("probe") {
                artifact(tasks.named("bundleReleaseAar"))
                groupId = "io.github.openking88.netlab"
                artifactId = "compose-probe"
                version = "1.0"
            }
        }
    }
}
