pluginManagement {
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
        // 本地验证：core 从这里取；正式形态应换成私有 Maven 仓库
        mavenLocal()
    }
}

rootProject.name = "NetLabUi"
