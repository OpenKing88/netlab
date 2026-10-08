# 宿主接入示例

把 netlab 接进一个 Android 项目，**只改两个构建文件、不动任何业务源码**。

## 1. `settings.gradle.kts`

```kotlin
pluginManagement {
    // 本地验证形态：插件从源码引入。
    // 发布到 Maven 仓库后这一行可以删掉，改用
    // plugins { id("io.github.netlab") version "1.0.0" }
    includeBuild("../netlab/library/plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()      // 两个 AAR 从这里取；正式形态换成私有 Maven 仓库
        google()
        mavenCentral()
    }
}
```

## 2. app 模块的 `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    // ...你原有的插件
    id("io.github.netlab")          // ← 新增这一行
}

domainSwitch {
    // 渠道白名单：只有这些渠道会被插桩并注入依赖。
    // ⚠️ 生产渠道不要写进来 —— 白名单外是"既不插桩也不加依赖"，产物里零痕迹。
    flavors.set(setOf("devTest", "preProduct"))
}
```

## 3. 完成

不需要写 `dependencies`、不需要初始化调用、不需要 `addInterceptor`。

### 怎么确认接对了

构建日志里应该出现：

```
[netlab] 自动识别到域名：{你的接口域名=okhttp, 你的H5域名=webview}；capture=true ...
```

面板入口是通知栏常驻；通知被禁用时可以用 adb 拉起：

```bash
adb shell am start -n <你的applicationId>/io.github.netlab.ui.DomainSwitchPanelActivity
```

### 可用的配置项

```kotlin
domainSwitch {
    flavors.set(setOf("devTest", "preProduct"))  // 渠道白名单
    capture.set(true)          // 是否录制网络请求
    maxRecords.set(500)        // 保留条数上限
    maxBodyBytes.set(512 * 1024)  // 单条 body 捕获上限
    includeUi.set(true)        // 是否带可视化面板（纯 View 宿主设为 false）
}
```
