# 更新日志

本文件记录 netlab 的每个版本，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

## [1.0.1] - 2026-10-15

### 修复

- **`shrinkResources` 会把插件生成的资源删掉，导致 release 包面板显示「没有识别到基线域名」**。
  域名清单 / 配置 / 接口说明这几个字符串是插件在编译期生成的，库（预编译 AAR）拿不到宿主的
  R 常量，只能通过 `getIdentifier` 按名字反射读取；资源压缩器看不到静态引用，就把它们当死代码删掉。
  AGP 8 时代偶尔能侥幸留住，**AGP 9 新增的资源优化（`optimizeReleaseResources`）删得更彻底**，
  于是同一个 APK 在 debug 包正常、release 包能力全失，且现场没有任何报错，极难倒查。

  现在插件会在生成这些资源的同时，额外生成一份 `res/raw/domain_switch_keep.xml` 保留清单，
  宿主不需要自己写 `tools:keep`。文件名刻意不叫 `keep.xml`，避免与宿主自己的
  `res/raw/keep.xml` 撞名导致规则被静默覆盖。

- 插件模块发布时两个 publication 共用同一个 javadoc jar，两个 Sign 任务写同一个 `.asc`
  路径导致 Gradle 判定隐式依赖冲突、发布失败（仅在开启签名时暴露）。

### 新增

- **通知不可用时的桌面兜底入口**：通知栏是主入口，但 Android 13+ 需要
  `POST_NOTIFICATIONS` 运行时权限；宿主没申请或用户关掉通知时，通知既发不出去也不报错，
  面板就彻底进不去了。现在库会在通知不可用时自动启用一个默认隐藏的桌面入口
  （`DomainSwitchLauncherAlias`），通知恢复后自动收回。
  新 DSL：`domainSwitch { launcherEntry.set(false) }` 可关掉该行为。

### 质量门禁

- 新增 `make check-shrink`：在开着 `isMinifyEnabled + isShrinkResources` 的宿主上构建 release，
  逐个核对 6 个反射读取的资源是否存活。测试宿主（`test-hosts/agp8/probe`）也相应开启了
  压缩配置，否则这个回归永远测不出来。

## [1.0.0] - 2026-10-08

首个公开版本，发布到 Maven Central。

### 新增

- **动态域名切换**
  - OkHttp / Retrofit 链路：通过字节码插桩在每次请求前改写请求域名，切换后立即生效。
  - WebView 链路：接管 `loadUrl`，H5 加载域名一并替换。
  - 切换结果本地持久化，进程重启后仍然保留。
- **网络抓包**
  - 自动记录请求 / 响应，落库到 SQLite，带列表与详情视图。
  - 支持按接口说明（编译期从接口定义注释提取）检索定位，接口一多也能快速找到目标请求。
- **按链路分组**：抓包与切换面板按 OkHttp / WebView 分组展示。
- **可视化面板**（`netlab-ui`，Compose）
  - 抓包列表 / 详情，含请求头、响应体等内容的查看。
  - 域名切换面板：底部弹窗形态，按「App 域名切换」「H5 域名切换」等链路分组；
    显示「已切换到 xxxx 域名」或「未切换」，当前生效域名绿色高亮，且不支持删除。
  - 备选域名支持手动新增与删除；默认域名始终保留在列表中，不提供「还原」项。
  - 通知栏入口展示最近 5 条请求，点击进入面板。
- **零代码接入的 Gradle 插件**（`io.github.openking88.netlab`）
  - 按渠道白名单生效；白名单外的渠道既不插桩也不加依赖，产物零痕迹。
  - 自动发现项目中的域名配置入口与 WebView 加载点，无需改造业务源码。
- **可插拔**：移除依赖即完全还原，宿主源码一行不用改。

### 已知限制

- 仅覆盖 OkHttp / Retrofit 与 WebView 两条链路。
- 多模块工程暂不支持（已在示例工程中复现并记录）。
- 生产渠道不支持（这正是设计前提）。
- 切换 WebView 域名可能影响登录态。

[未发布]: https://github.com/OpenKing88/netlab/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/OpenKing88/netlab/releases/tag/v1.0.1
[1.0.0]: https://github.com/OpenKing88/netlab/releases/tag/v1.0.0
