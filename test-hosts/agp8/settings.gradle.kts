pluginManagement {
    // 复用同一份插件源码，证明一个插件同时支持 AGP 8.13 与 AGP 9
    includeBuild("../../library/plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "DomainSwitchAgp8Regression"
include(":probe")
include(":kotlin-probe")
include(":compose-probe")
