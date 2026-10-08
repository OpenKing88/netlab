import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 版本由根工程统一声明为 2.0.21（对齐目标宿主）
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

group = "io.github.netlab"
version = "3.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // stdlib 只编译期可见，不进 POM
    compileOnly(kotlin("stdlib"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("probe") {
                artifact(tasks.named("jar"))
                groupId = "io.github.netlab"
                artifactId = "kotlin-probe"
                version = "3.0"
            }
        }
    }
}
