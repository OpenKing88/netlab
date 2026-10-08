# netlab

一个**零代码接入**的 Android 调试工具库，提供两件事：

- **动态域名切换** —— 在设备上随时把请求切到另一个环境，立即生效、重启仍保留
- **网络抓包** —— 自动记录请求/响应，带可视化列表与详情

两条链路都覆盖：**OkHttp / Retrofit** 与 **WebView 加载**。整个库**按渠道生效**，
生产渠道里连类都不存在；移除依赖即完全还原，宿主源码一行不用改。

---

## 它解决什么问题

测试环境切域名，通常的做法是改 `BASE_URL` 再重新打包 —— 一轮下来十分钟，
换个环境再重来一遍。抓包则更麻烦：要么在代码里加拦截器并手动拼日志，
要么引一个把整套 UI 栈（Compose + Room + Paging）都拖进宿主的库。

这个库把两件事都做成"**加一行插件配置**"：

```kotlin
plugins { id("io.github.openking88.netlab") }

domainSwitch {
    flavors.set(setOf("devTest", "preProduct"))   // 只有这两个渠道生效
}
```

没有依赖改动、没有源码改动、没有初始化调用。

---

## 三个制品

| 制品 | 内容 | 依赖 | 体积 |
|---|---|---|---|
| `io.github.openking88:netlab` | 核心：切换规则、抓包录制与存储、插桩目标 | **零运行时依赖**（纯 Java） | 31 KB |
| `io.github.openking88:netlab-ui` | 可视化面板：域名切换页 + 抓包列表/详情 | 核心 + kotlin-stdlib（Compose 走 `compileOnly`） | 109 KB |
| Gradle 插件 `io.github.openking88.netlab` | 按渠道加依赖 + 字节码插桩 | — | — |

```
         ┌─────────────────────────────┐
         │  Gradle 插件                 │  构建期
         │  · 渠道白名单判定             │
         │  · 按渠道注入依赖             │
         │  · 字节码插桩 + 域名清单生成    │
         └──────────────┬──────────────┘
                        │ 生成/注入
         ┌──────────────▼──────────────┐
         │  netlab（核心，纯 Java）│  运行期
         │  · 切换规则 + 持久化          │
         │  · 抓包录制 + SQLite 存储      │
         │  · DomainSwitchAgent（插桩目标）│
         └──────────────┬──────────────┘
                        │ 可选
         ┌──────────────▼──────────────┐
         │  netlab-ui（Compose）  │
         │  · 通知栏入口                 │
         │  · 域名切换面板 / 抓包列表 / 详情│
         └─────────────────────────────┘
```

**只想要能力不要界面？** `includeUi.set(false)`，只带核心即可。

---

## 接入

### 1. 声明插件仓库

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        // 正式形态：换成你们的私有 Maven 或内部发布源
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()      // 同上
        google()
        mavenCentral()
    }
}
```

### 2. app 模块应用插件

```kotlin
plugins {
    id("com.android.application")
    // ...
    id("io.github.openking88.netlab")
}
```

发布到 Maven Central 后，也可以写成带版本号的形式
`id("io.github.openking88.netlab") version "1.0.0"`（见 `docs/publishing.md`）。

### 3. 配置渠道白名单

```kotlin
domainSwitch {
    flavors.set(setOf("devTest", "preProduct"))
}
```

运行时的依赖由插件自己按渠道补进去，**不需要写任何 `dependencies`**。

### 4. 完成

不需要写初始化、不需要 `addInterceptor`、不需要改 Manifest。
库自己的 `ContentProvider` 会在应用启动时把上下文接好。

---

## 它自动做了什么

| 动作 | 说明 |
|---|---|
| **识别域名** | 从该渠道的 `buildConfigField` 里扫出所有 URL 常量，取出 host 作为候选清单，不需要人工维护第二份列表 |
| **初判链路** | 按字段名归类：`BASE_URL` / 含 `API` → 网络请求；`URL_*` / 含 `WEB`、`H5` → WebView。运行期还会用实际观测纠正 |
| **挂拦截器** | 在宿主的每个 `OkHttpClient` 上挂两个 application 拦截器：域名改写 + 抓包 |
| **接管 WebView** | 改写 `loadUrl(...)` 的入参，覆盖 H5 页面加载 |
| **挂入口** | 通知栏常驻入口，点开进面板 |

---

## 使用

### 打开面板

通知栏点一下即可。通知被禁用时可以用 adb：

```bash
adb shell am start -n <applicationId>/io.github.openking88.netlab.ui.DomainSwitchPanelActivity
```

### 域名切换页

按链路分组展示，避免误导：

- **网络请求（OkHttp）** —— 由拦截器改写
- **WebView / H5 页面** —— 由加载入口改写
- **未知链路** —— 切换可能无效

点任意一行选目标域名，可选"自动识别的域名 / 自己加过的域名 / 临时输入一个"。
"未切换"即还原。

> 为什么不把两类域名混在一起展示：自动识别只能看到"配置里有哪些 URL 字符串"，
> 看不到它最后被谁消费。如果把 WebView 的域名当成普通域名列出来，用户切了之后
> 不会有任何效果，还查不出原因。

### 抓包页

每条记录显示方法、路径、状态码、耗时、大小；**发生域名改写时会直接标出 `原域名 → 实际域名`**，
一眼就能确认切换有没有生效。点进详情可以看到完整 URL、协议/TLS、请求头、请求体、
响应头、响应体（JSON 会自动美化并高亮）。

### 代码方式（可选）

```java
DomainSwitch.setTarget("api.example.com", "api.pre-test.internal"); // 切换
DomainSwitch.setTarget("api.example.com", null);                     // 还原

String rewritten = DomainSwitch.rewriteUrl("https://api.example.com/v1/user");
// 供 WebView / 图片加载等不走 OkHttp 的场景复用同一套规则
```

---

## DSL 配置项

| 配置 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `flavors` | `Set<String>` | 空（全部生效并告警） | 渠道白名单，**生产渠道务必排除在外** |
| `capture` | `Boolean` | `true` | 是否录制网络请求；关掉后拦截器直接放行 |
| `maxRecords` | `Int` | `500` | 内存与落库保留的最大记录数 |
| `maxBodyBytes` | `Int` | `524288` | 单条 body 捕获上限，超限只标记状态、不占内存 |
| `includeUi` | `Boolean` | `true` | 是否带 Compose 面板；纯 View 宿主设为 `false` |
| `autoAddDependency` | `Boolean` | `true` | 是否由插件自动补依赖 |
| `runtimeDependency` | `String` | `io.github.openking88:netlab:0.6.1` | 核心制品坐标 |
| `uiDependency` | `String` | `io.github.openking88:netlab-ui:0.9.6` | 面板制品坐标 |

---

## 版本要求

| 维度 | 要求 | 备注 |
|---|---|---|
| Kotlin（宿主） | **≥ 2.0.21** | 仅 `-ui` 模块需要；核心是纯 Java，不挑 Kotlin 版本 |
| Compose（宿主） | **≥ 1.7** | 仅 `-ui` 模块需要，且需有 `activity-compose` |
| AGP | 8.13.2 / 9.0.1 已验证 | 插件编译在 8.13.2 的公共 API 上，一份制品同时支持两者 |
| Gradle | 8.13 / 9.1 已验证 | Gradle 8.13 不支持 Java 25，跑 AGP 8 需指定 JDK 21 |
| minSdk | 21 | |
| 纯 View / 纯 Java 宿主 | 只带核心 | 带 UI 会因缺 Compose 而运行期报错 |

> **为什么核心坚持纯 Java**：Kotlin 编译产物带元数据版本，用高版本编译的库会让低版本
> Kotlin 宿主**连编译都过不去**（哪怕宿主一行都没引用它）。核心作为基础设施，
> "任何项目都能塞进去"就是它的全部价值，不值得为此绑定宿主的 Kotlin 下限。
> UI 层则没有这个包袱，可以放心用 Compose。

---

## 已知限制

按重要程度排序，每条都说明**边界在哪、为什么**：

### 1. 多模块不支持（已验证）

网络层如果写在**独立的 library 模块**里，插件只应用在 app 模块时**不会被接管**。
`networklib` 模块是这个问题的复现器。

不能简单让"每个模块各自应用插件" —— library 模块没有渠道维度，插桩会同时进入所有
app 渠道（含生产渠道），运行时却没有核心类，直接 `NoClassDefFoundError`。

用 `InstrumentationScope.ALL` 连带插桩依赖也不行：会把 okhttp 自身也插桩，
而我们改写的正是 okhttp 的调用点，dex 阶段就崩。

**接入前请确认：你的网络层是不是写在 app 模块里。**

### 2. 只覆盖 OkHttp 与 WebView 两条链路

- 自研 / 第三方网络栈（自定义 `Call.Factory`）不接管
- 第三方 SDK 自带的域名不接管（保持宿主行为不变，这是刻意的）
- WebView 只覆盖宿主主动 `loadUrl`；页面内跳转、以及 H5 内部写死的绝对域名不覆盖

> 关于 H5：页面内同域相对路径的请求会跟着已改写的主域名走；如果 H5 把接口域名
> 写死成绝对地址，客户端侧无解，只能靠 H5 自己支持多环境。

### 3. WebView 换域名有登录态影响

Cookie / localStorage 按域名隔离，换个域名加载同一个 H5，登录态不共享，
页面可能直接变未登录。切之前请知悉。

### 4. 抓包位置带来的取舍

抓包拦截器挂在链路靠前的位置，好处是**请求体拿到加密前的明文、响应体拿到解密后的明文**；
代价是请求头要在响应回来时用 `response.request()` 补一次（已实现）。

另外，长度未知的流式请求体（chunked 上传）不做缓冲，会标记为"未捕获" ——
这是为了避免把大文件上传读进内存。

### 5. 生产渠道不支持

这是设计目标而非缺陷：白名单外的渠道既不加依赖也不插桩，产物里零痕迹。

---

## 质量门禁

```bash
make check
```

| 门禁 | 挡的是什么 |
|---|---|
| 核心：发布 POM 不能有 `<dependencies>` | 防 AGP 自动挂上 `kotlin-stdlib`，导致宿主解析到更新的版本后**编译失败** |
| 核心：字节码不能引用 `kotlin/` | 上一条的物理佐证；核心必须保持纯 Java |
| UI：Compose 桥接扫描 | 防接口作用域的默认参数（如 `weight(1f)`）生成跨版本不稳定的 `$default` 桥接，在部分 Compose 宿主上运行期 `NoSuchMethodError` |

最后一个门禁是真机踩出来的 —— 同一份 UI 产物在 Compose 1.10.4 上崩、1.10.6 上正常。
规则很简单：**库代码里调用 Compose 接口作用域的扩展函数时，把默认参数写全**。

---

## 验证状态

| 项 | 状态 |
|---|---|
| 真机运行 | ✅ SM-A5260 / Android 14 |
| 域名切换（OkHttp） | ✅ 拦截器在第 0 位，请求实际打到切换后的域名 |
| 域名切换（WebView） | ✅ 宿主写原域名，WebView 实际请求改写后的域名 |
| 面板 / 抓包列表 / 详情 | ✅ 已在真机验证（截图见仓库历史或自建） |
| 真实项目接入 | ✅ 该项目（AGP 8.13.2 / Kotlin 2.0.21），自动识别出两个真实域名并正确分类 |
| 真实业务流量抓包 | ✅ 抓到该项目开屏页的真实接口请求，**响应体为解密后的明文 JSON**，请求头完整 |
| 生产渠道零痕迹 | ✅ dex 符号 0 / 资源 0 / manifest 节点 0 |
| 单元测试 | ✅ 18 个 |
| 多宿主矩阵 | ❌ 未做（Kotlin × Compose × AGP 三维） |

---

## 目录

```
netlab/
├── library/          ← 库自身的开发构建（AGP 9 / Kotlin 2.3）
│   ├── core/           核心 AAR：规则、抓包、存储、插桩目标（纯 Java）
│   ├── plugin/         Gradle 插件：按渠道加依赖 + 字节码插桩
│   ├── sample/         示例 App（消费 core + ui + plugin）
│   └── networklib/     多模块限制的复现器（故意不应用插件）
├── ui/               ← Compose 面板（独立构建：Kotlin 2.0.21）
├── test-hosts/
│   └── agp8/           AGP 8.13.2 宿主验证 + Kotlin/Compose 兼容性探针
├── integration/      ← 宿主接入补丁（本地验证形态）
├── docs/             ← 设计与发布档案
│   ├── clean-architecture.md   设计与验证记录
│   └── publishing.md           发布到 Maven Central 的流程
├── LICENSE
└── CHANGELOG.md
```

**为什么 `ui` 是独立构建**：UI 模块必须编译在 Kotlin 2.0.21 上（这是我们要支持的宿主最低
版本，也是避免"高版本 Kotlin 元数据打挂低版本宿主编译"的关键），而 `library/` 跑在
AGP 9 + Kotlin 2.3 上。两个工具链没法合并成一个 Gradle 构建，所以物理上分开。
详见 `docs/clean-architecture.md` 的兼容性实验章节。

### 构建命令

仓库根目录提供统一的 Makefile 入口：

```bash
make check         # core + ui 的全部检查
make check-hosts   # AGP 8.13.2 宿主验证工程
make publish       # 把 core 与 ui 发布到 mavenLocal（供宿主验证）
make bundle        # 构建 Maven Central 上传用的 bundle zip（需签名凭据）
```

> `ui/` 与 `test-hosts/agp8/` 跑 Gradle 8.13，**不支持 JDK 25**，必须用 JDK 21 ——
> Makefile 里已经处理好了（`JAVA21` 变量，可用环境变量或命令行覆盖）。

想了解"为什么这么设计"，看 `docs/clean-architecture.md`。
想发布新版本，看 `docs/publishing.md`。

---

## License

Apache License 2.0，见 [`LICENSE`](LICENSE)。
版本变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。
