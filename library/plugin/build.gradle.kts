import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "io.github.netlab"

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
            id = "io.github.netlab"
            implementationClass = "io.github.netlab.plugin.DomainSwitchPlugin"
        }
    }
}
