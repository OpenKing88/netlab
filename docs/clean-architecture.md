# domain-switch 干净架构设计

> **这份文档是「设计与验证档案」，不是接入指南。**
>
> 面向接入方的说明（怎么接、有哪些配置、版本要求、已知限制）请看仓库根目录的
> [`README.md`](../README.md)。
>
> 这里记录的是**每个决策的依据**：为什么这么做、试过什么不行的路子、真机上验证出了
> 什么。很多结论是踩坑之后才定下来的，保留了当时的现场（报错原文、字节码、日志片段），
> 目的是让后来的人能判断"这条结论还成不成立"，而不是照抄一个没有出处的规则。
>
> 阅读建议：**文档内的推断性结论未必都被验证过**。带真机日志、字节码或构建输出佐证的
> 才是实测结论，其余的按待验证看待。

> 目标：一个**依赖即生效、拔掉即还原**的 Android 动态域名切换库。
> 宿主源码零改动，接入开关以**渠道（flavor）**为单位，而不是 debug/release。

## 一、四条"干净"标准

| 标准 | 实现方式 | 本次验证结论 |
|---|---|---|
| 源码零侵入 | Gradle 插件 ASM 插桩，宿主不写 `addInterceptor`、不写初始化 | ✅ 宿主 `SampleNetwork` 里没有任何库相关代码，插桩后自动生效 |
| 按渠道开关 | 插件在 `onVariants` 读 `variant.productFlavors`，白名单外直接跳过 | ✅ `devTest`/`preProduct` 插桩，`prodSample`（占位生产渠道）被跳过 |
| 生产渠道零痕迹 | 白名单外既不插桩也不加依赖 | ✅ `prodSampleRelease` APK 中 `io/github/netlab` 符号数为 0，合并 manifest 中该库节点数为 0 |
| 不污染宿主依赖 | 运行时的 okhttp 用 `compileOnly`，不写进 POM | ✅ 发布的 POM 中无 okhttp，宿主用自己的版本 |

## 二、模块划分

```
domain-switch/                    被宿主持有的 Android Library（运行时）
   io.github.netlab
     DomainSwitch                 对外 API：apply / clear / rules / rewrite
     DomainSwitchInterceptor      域名改写拦截器（application 层，必须 index 0）
     DomainSwitchTag              改写标记，供抓包工具展示"原域名 → 实际域名"
     DomainSwitchAgent            插桩目标，方法签名永久冻结
     internal/DomainRuleSet       规则集合，单向 + 幂等
     internal/DomainSwitchInitProvider   零代码初始化（唯一 manifest 足迹）

build-logic/                      Gradle 插件（独立 included build）
   io.github.netlab.plugin
     DomainSwitchPlugin           渠道判定 / 依赖注入 / 开启插桩
     DomainSwitchExtension        domainSwitch { flavors(...) } 配置项
     DomainSwitchClassVisitorFactory   AGP 插桩入口
     DomainSwitchClassVisitor     注入字节码
```

依赖方向是单向的：**插件只在构建期存在，运行时库只在运行期存在，两者唯一的耦合点是
`DomainSwitchAgent.install(OkHttpClient.Builder)` 这一个方法签名。**

## 三、接入形态

宿主构建脚本只需要：

```kotlin
plugins { id("io.github.netlab") }

domainSwitch {
    flavors.set(setOf("devTest", "preProduct"))
}
```

没有 `dependencies` 改动，没有源码改动。插件会自动把运行时依赖加到命中渠道对应的
`<flavorName>Implementation` 配置上。

## 四、插件工作机制

1. `onVariants` 遍历每个 variant，取 `variant.productFlavors` 得到渠道名；
2. 不在白名单 → 打日志并 `return`（**不插桩、不加依赖**）；
3. 在白名单 → 往 `<flavorName>Implementation` 加运行时依赖；
4. 开启 `transformClassesWith(..., InstrumentationScope.PROJECT)`。

用 `InstrumentationScope.PROJECT` 而不是 `ALL` 是刻意的：只插桩宿主自己的类，
三方 SDK（Firebase / Adjust / 统计 SDK）内部创建的 `OkHttpClient` 不会被改写。

## 五、插桩点（三个，缺一个都会漏）

| 钩子 | 插桩点 | 作用 |
|---|---|---|
| ① | `new OkHttpClient.Builder()` → `install(builder)` | 在宿主 `addInterceptor` 之前注入，拦截器天然第 0 位 |
| ② | `new OkHttpClient()` → `installClient(client)` | 覆盖 `OkHttpClient().newBuilder()...` 这类写法 |
| ③ | `OkHttpClient$Builder.build()` → `installClient(client)` | 兜底，只要 `build()` 在宿主类里调用就一定命中 |

`OkHttpClient.Builder(existing)` 拷贝构造刻意不匹配，`newBuilder()` 不会重复注入。

### 为什么光有钩子 ① 不够

这是接入真实项目才暴露的问题，值得单独记一笔。

最初的实现只挂了钩子 ①，理由是"`OkHttpClient()` 无参构造内部就是 `this(Builder())`，
所以一个点能同时覆盖两种写法"。**这个推理是错的**：`OkHttpClient()` 内部的 `new Builder()`
那段字节码发生在 **okhttp 自己的类里**，而插件用的是 `InstrumentationScope.PROJECT`
（只处理宿主自己的类），根本扫不到。

结果就是：只要项目里写 `OkHttpClient().newBuilder()...build()`，
插桩结果里一个注入点都不会有 —— 插件"看起来跑成功了"，实际完全没生效。

修法是把钩子 ②③ 补上。`installClient(OkHttpClient)` 接收刚创建出来的实例，
若已带拦截器就原样返回，否则用 `newBuilder()` 补上再 build，因此天然幂等。

⚠️ **顺序说明**：OkHttp 没有"插入到首位"的公开 API，`installClient` 只能**追加**。
当实例本来就是刚 `new OkHttpClient()` 出来的（拦截器列表为空）时结果仍是第 0 位；
只有当实例已自带拦截器时才排到最后。两种情况下改写都在 `ConnectInterceptor` 之前生效，
差别仅限于排在前面的拦截器看到的是改写前的 URL。

⚠️ **仍未覆盖**：Retrofit 未显式设置 client 时，`build()` 内部的 `new OkHttpClient()`
发生在 retrofit 的类里，同样扫不到。要覆盖需要把 `InstrumentationScope` 放宽到 `ALL`
并加类名过滤，暂未做。

## 五点五、自动识别渠道域名 + 自定义输入

面板要能列得出域名，但人工维护第二份清单毫无意义 —— 渠道里本来就写了。

**构建期**：`GenerateDomainSwitchHostsTask` 读取该 variant 的 `buildConfigFields`，
把所有 URL 型常量取 host，生成 `res/values/domain_switch_hosts.xml`。

```kotlin
buildConfigField("String", "BASE_URL", "\"https://api.example.com\"")
buildConfigField("String", "URL_WEB_HELP", "\"https://h5.example.com/help\"")
// ↓ 构建日志：[domain-switch] 自动识别到域名：[api.example.com, h5.example.com]
```

两个实现细节值得记下来：

1. **必须在独立 Task 里读，不能直接在 `onVariants` 里读**。
   配置阶段 `buildConfigFields` 还没定格，AGP 会直接抛
   `Cannot query the value of property 'buildConfigFields' because configuration ... has not completed yet`。
   用 `Provider.map { }` 推迟到执行期读取，同时也天然兼容 configuration cache。
2. **用生成的 res 资源，不要用 manifestPlaceholder**。
   占位符在"没装插件"时找不到替换值会让 manifest 合并失败，而 res 资源只是覆盖库里的同名默认值，
   缺失时退化为空清单，不会炸。

**运行期**：`DomainSwitch.configuredHosts()` 读上面那个资源，`DomainSwitch.targetOptions()`
返回「自动识别 + 用户自定义」的并集。自定义输入支持三种写法，统一归一化成 host：

```java
DomainSwitch.addCustomTarget("https://custom.example.org/live");  // → custom.example.org
DomainSwitch.addCustomTarget("test.example.com:8080");            // → test.example.com
DomainSwitch.setTarget("api.example.com", "api.pre-test.internal"); // 切换，立即生效并持久化
DomainSwitch.setTarget("api.example.com", null);                   // 还原
```

## 五点六、两个由真机验证暴露出来的坑

这两个都不是靠读代码能发现的，必须真机跑：

1. **Provider 创建顺序不确定**。宿主的 ContentProvider 可能先于本库的 `InitProvider` 被创建，
   此时 `getSharedPreferences` / `getResources` 都拿不到 Application。最初的实现会把
   "当时读不到"的空结果缓存下来，之后就永远是空。修法：拿不到 Context 时不缓存，且初始化时让缓存失效。
2. **初始化会覆盖运行期状态**。`initialize()` 一开始用持久化数据**整体替换**内存规则，
   如果早于初始化就调用过 `setTarget`，那条规则会被悄悄抹掉。修法：改成合并语义，内存中的更新胜出。

## 五点七、切换面板

面板**刻意用 View 体系实现，不引入 Compose/Room**：宿主只是想在自己渠道包里带一个调试开关，
不该为此背上整套 UI 栈（Monitor 就是反例，它把 Compose BOM + Room3 + Paging + Gson 一起带进了宿主）。

入口是通知栏常驻，因为域名切换是低频操作，藏进某个页面反而找不到：

| 环节 | 实现 |
|---|---|
| 通知通道 | `DomainSwitchNotification`，`IMPORTANCE_LOW`、不响铃不震动 |
| 点击行为 | `PendingIntent` 打开 `DomainSwitchPanelActivity` |
| 权限 | 库 manifest 声明 `POST_NOTIFICATIONS`；未授权时静默跳过，不影响宿主 |
| 兜底入口 | `exported=true`，可用 adb 直接拉起（通知被禁用时仍然能用） |

```bash
adb shell am start -n <applicationId>/io.github.netlab.ui.DomainSwitchPanelActivity
```

面板结构：每个「基线域名」一行，显示当前指向；点击弹出选择器，选项 =
`未切换` + 自动识别的域名 + 用户自定义的域名 + `输入自定义域名…`；
底部输入框用于「添加候选」，另有「全部还原」。

### 按链路分组（必须，否则面板会骗人）

接入某真实项目之后暴露的问题：自动识别扫出来的两个域名里，一个是 Retrofit 用的接口域名，
另一个是 WebView 加载 H5 用的域名。**拦截器对后者一点用都没有** —— 如果面板把它们并列展示，
用户切了 WebView 域名会以为生效了，实际什么都没发生，还查不出原因。

根因是编译期只能看到"这是一个 URL 字符串"，看不到它最后被谁消费。所以要把它标出来：

| 分类 | 判定依据 | 由谁改写 |
|---|---|---|
| `OKHTTP` | 运行期观测到该 host 走过拦截器；否则按字段名（`BASE_URL` / 含 `API` / 含 `SERVER`）初判 | application 拦截器 |
| `WEBVIEW` | 含 `WEB` / `H5` / `HTML`，或字段名以 `URL_` 开头 | `loadUrl` 插桩 |
| `UNKNOWN` | 都不匹配 | 可能不生效 |

**运行期观测优先于编译期初判**：拦截器每处理一个请求就把 host 记进
`DomainSwitch.recordObservedHost(...)`，面板取 `linkOf(host)` 时会先用这个观测结果纠正。
这样即使某个项目把接口域名字段起名叫 `URL_API` 或别的什么，只要它真的走过 OkHttp，就会被正确归类。

生成出来的资源形如：

```
api.example.com=okhttp,h5.example.com=webview
```

## 五点八、面板的真机验证（SM-A5260 / Android 14）

### WebView 链路的接管

WebView 的请求完全不走 OkHttp，拦截器对它无效，必须单独接管。

做法是**整体替换调用**：把宿主类里的
`INVOKEVIRTUAL android/webkit/WebView.loadUrl(...)` 换成同签名的
`INVOKESTATIC DomainSwitchAgent.loadUrl(...)`，由 Agent 负责改写后再调回 WebView。
因为参数列表完全一致（receiver 变成第一个参数），操作数栈形状不变，**不需要任何栈操作指令**。

覆盖的入口：

| 方法 | 说明 |
|---|---|
| `loadUrl(String)` | 最常用 |
| `loadUrl(String, Map)` | 带额外请求头 |
| `loadDataWithBaseURL(...)` | 改写 baseUrl，页面内相对路径跟着走 |

字符串改写刻意**不用 `HttpUrl.parse`** —— 它在 OkHttp 4 是废弃 API、在 OkHttp 5 已被移除，
而这个库要同时兼容两个大版本。改成自己做 host 段替换，path / query / fragment / 端口 /
userInfo 全部原样保留，`about:blank`、`javascript:` 这类非 http(s) 字符串直接放行。

⚠️ **覆盖边界**：只覆盖"宿主自己的类里直接调用 loadUrl"这种情况。
如果宿主把 WebView 包成自定义子类、或通过 `WebViewClient.shouldOverrideUrlLoading`
在页面内跳转，这一层不覆盖；页面内同域相对路径的请求会跟着已改写的主域名走，不受影响。

---

```
（面板首屏）  api.example.com  未切换
              h5.example.com   未切换

（点第一行）  api.example.com 指向…
              未切换 / api.example.com / h5.example.com
              / custom-target.example.org / 输入自定义域名…

（选择后）    api.example.com  custom-target.example.org（已切换）
              h5.example.com   未切换
```

持久化文件（`shared_prefs/domain_switch.xml`）：

```xml
<string name="custom_targets">custom-target.example.org</string>
<string name="target:api.example.com">custom-target.example.org</string>
```

冷启动后自检日志确认配置被恢复，且不依赖面板是否打开过：

```
初始化后的生效映射 = {api.example.com=custom-target.example.org}
真实请求链路的失败信息: Unable to resolve host "self-check.internal"
```

通知入口同时存在（`dumpsys notification`）：

```
pkg=com.example.domainswitchverify id=20260929 channel=domain_switch
```

### 两个链路的真机验证（SM-A5260 / Android 14）

面板按链路分组后：



**OkHttp 链路**（宿主写 `OkHttpClient.Builder()`，见「五点」的字节码证据）。

**WebView 链路** —— 宿主代码写的是原始域名，WebView 实际请求的是改写后的：

```
I DomainSwitchWebView: 宿主调用 loadUrl("https://h5.example.com/help")
I DomainSwitchWebView: WebView 实际请求的 URL = https://h5-test.internal/help
```

对应字节码（`invokevirtual` 已被替换成 `invokestatic`）：

```
55: ldc          "https://h5.example.com/help"
57: invokestatic DomainSwitchAgent.loadUrl:(Landroid/webkit/WebView;Ljava/lang/String;)V
```

### 踩坑：描述符拼接错一个括号，D8 直接拒绝

替换调用需要自己拼静态方法描述符。第一版写成
`"(Landroid/webkit/WebView;" + descriptor`，而 `descriptor` 本身已经带开头的 `(`，
拼出来是 `(Landroid/webkit/WebView;(Ljava/lang/String;)V` —— 多了一个左括号。
ASM 不校验这个，构建一路走到 dex 才炸：

```
D8: Could not parse code: (Landroid/webkit/WebView;(Ljava/lang/String;)V
```

正确写法是去掉原描述符开头的 `(` 再插入：`"(Landroid/webkit/WebView;" + descriptor.substring(1)`。
这件事说明**插桩类改动的验证不能停在"编译通过"，必须走到 dex**。

### 为什么必须是 application interceptor

network interceptor 在 `ConnectInterceptor` 之后执行，连接目标已经选定，
在那里改 URL 没有意义。域名改写必须走 application 层且排在最前。
想同时抓包的话，抓包工具作为 network interceptor 挂在后面，通过
`DomainSwitchTag` 读取原始域名即可同时展示改写前后。

## 六、运行时设计

- **规则读取走 `AtomicReference`**，拦截器每次请求读取 → 改配置后下一个请求立即生效，
  不需要重建 Retrofit / OkHttpClient，也不需要重启 App。
- **无命中返回原对象**：`DomainSwitch.rewrite(url)` 在无规则时 `return url`，
  调用方用引用相等判断是否命中，避免无意义地重建 `Request`。
- **单向 + 幂等**：规则的 key 只允许是基线域名，目标域名不会被再次匹配，
  即使一个请求经过多个改写环节也不会被反复改写。
- **绝不抛出**：`DomainSwitchAgent.install` 内部吞掉所有 `Throwable`——
  域名切换库因自身问题让宿主请求失败是不可接受的。
- **不覆盖宿主 tag**：只在宿主没有设置 `DomainSwitchTag` 时才写，避免侵入宿主行为。

## 七、移除即还原的验收清单

1. 删掉 `plugins { id("io.github.netlab") }` 与 `domainSwitch { }`；
2. `./gradlew :app:assemble<Flavor>` 应恢复原样；
3. 校验三项零残留：
   - APK dex 中 `io/github/netlab` 符号数为 0；
   - 合并后的 manifest 中无该库的 provider；
   - 宿主源码从未出现过任何库相关引用（本次设计中天然满足）。

## 八、已知限制与下一步

| 项 | 说明 |
|---|---|
| 非 OkHttp 网络栈 | 自研/第三方 `Call.Factory` 需要追加一个插桩点包装 `Retrofit$Builder.callFactory(...)` |
| WebView / Glide | 不走 OkHttp，可用 `DomainSwitch.rewrite(HttpUrl)` 复用同一套规则接入 |
| 三方 SDK 自有域名 | 明确不接管，保持宿主行为不变 |
| 面板的扩展性 | 当前是最朴素的列表 + 对话框；若要做历史记录、配置分享等，建议再拆独立的可选 UI 模块 |
| kotlin-stdlib | 运行时是纯 Java，但 AGP 9 内置 Kotlin 仍会在 POM 里带出 kotlin-stdlib，可后续剥离 |
| 多模块 | `InstrumentationScope.PROJECT` 只覆盖当前模块；OkHttpClient 若在别的 library 模块里创建，需要该模块也应用插件 |

## 十、双 AGP 版本支持

插件**只编译在支持范围内最旧的 AGP 公共 API 上**（`gradle-api:8.13.2`），
同一个制品同时跑 AGP 8.13.2 与 AGP 9.0.1，两个版本都已实测：

| 环境 | Gradle | 插桩结果 | 渠道隔离 | 真机运行 |
|---|---|---|---|---|
| AGP 9.0.1 / Kotlin 2.3.20 / Compose | 9.1.0 | ✅ 注入在第 0 位 | ✅ | ✅ SM-A5260 |
| AGP 8.13.2 / 纯 Java 探针 | 8.13 | ✅ 注入在第 0 位 | ✅ | ✅ SM-A5260 |

> Gradle 8.13 不支持 Java 25，跑 AGP 8 回归时需要指定 JDK 21：
> `./gradlew -Dorg.gradle.java.home=$JAVA21 ...`

## 十一、真实项目接入验证

在某真实项目（AGP 8.13.2 / Kotlin 2.0.21 / Gradle 8.13，三渠道 devTest / preProduct / tansLine）
上做了接入验证。接入内容只有两处、零源码改动，已存为补丁：
`integration/host-integration.patch`

验证结果：

```
[domain-switch] variant=devTestDebug / devTestRelease / preProductRelease 已开启域名切换插桩
[domain-switch] 渠道 [tansLine] 不在白名单 [devTest, preProduct] 内，variant=tansLineRelease 跳过
[domain-switch] 自动识别到域名：[api.example.com, h5.example.com]
```

域名是插件从该项目的 `BASE_URL` 与 11 个 `URL_MINE_*` / `URL_WEB_*` 常量里去重得到的，
零人工维护。该项目的网络出口字节码（`ApiTansHelper`）插桩后：

```
 0: new           okhttp3/OkHttpClient
 3: dup
 4: invokespecial okhttp3/OkHttpClient.<init>()V
 7: invokestatic  DomainSwitchAgent.installClient   ← 钩子②
10: invokevirtual okhttp3/OkHttpClient.newBuilder()
   ...
126: invokevirtual okhttp3/OkHttpClient$Builder.build()
129: invokestatic  DomainSwitchAgent.installClient  ← 钩子③（幂等）
132: areturn
```

渠道隔离同样成立 —— 只有白名单渠道生成了域名清单任务：

```
generateDevTestDebugDomainSwitchHosts
generateDevTestReleaseDomainSwitchHosts
generatePreProductReleaseDomainSwitchHosts
（没有 generateTansLineReleaseDomainSwitchHosts）
```

### 接入真实项目时暴露的第二个问题：kotlin-stdlib 污染

第一次在某真实项目上编译直接失败：

```
e: kotlin-stdlib-2.3.20.jar!/META-INF/kotlin-stdlib.kotlin_module
   Module was compiled with an incompatible version of Kotlin.
   The binary version of its metadata is 2.3.0, expected version is 2.0.0.
> Internal compiler error.
```

原因：AGP 9 的内置 Kotlin 会给库自动挂上 `kotlin-stdlib:2.3.20`，
而本库是纯 Java 实现，根本不需要它。这个 2.3.x 的 stdlib 顺着 POM 传进
Kotlin 2.0.21 的宿主项目，直接把宿主编译打挂。

修法：发布时不用 `from(components["release"])`，改成只挂 AAR 本体
（`artifact(tasks.named("bundleReleaseAar"))`），POM 里零依赖，也不再生成 `.module`。
这也是这个库的真实形态 —— 它确实没有任何运行时依赖（okhttp 是 `compileOnly`）。

修复后的 POM：

```xml
<groupId>io.github.netlab</groupId>
<artifactId>domain-switch</artifactId>
<version>0.4.0</version>
<packaging>aar</packaging>
```

## 十二、合并抓包能力（阶段 1）

把 Monitor 的抓包能力合并进来，**核心继续保持纯 Java** —— 这是它能塞进任何项目的前提。
Compose 可行性已经单独实测过：库编译在 Kotlin 2.0.21 + Compose 依赖走 `compileOnly` 时，
该项目能正常编译、依赖零污染、真机上能正常渲染，所以 UI 层会另做 Compose 版本。

阶段 1 落地的是"录制 + 存储"，纯 Java、无 UI：

```
io.github.netlab.capture
  CaptureRecord       一条记录（可变 DTO，Kotlin 侧直接读字段）
  CapturedBytes       抢下来的原始字节 + 状态
  CaptureSnapshot     一次请求的原始素材（只装字节，不装解码结果）
  CaptureBody         请求/响应体捕获与解码
  ReplayRequestBody   用捕获字节重放请求体
  CaptureDatabase     单表 SQLiteOpenHelper
  CaptureStore        内存环形缓冲 + 单线程写库 + 保留策略
  CaptureInterceptor  抓包拦截器
```

### 相比 Monitor 修掉的四个问题

| 问题 | Monitor 的做法 | 现在的做法 |
|---|---|---|
| 全局写锁串行化 | 一个 `Mutex` 把 insert 和 update 全串起来 | 单线程 Executor 天然串行，DB 操作彻底离开 OkHttp 线程 |
| 流式请求体无界缓冲 | 长度未知时先全部读进内存再判断大小 | 长度未知直接标记未捕获、原样转发 |
| 无数据保留策略 | 只有 `deleteAll`，库无限增长 | 内存环形缓冲 + 落库后裁剪，默认保留最近 500 条 |
| 解码占着 OkHttp 线程 | gzip 解压、编码判断、字符串构造都在拦截器线程 | 拦截器只拷贝字节，解码交给写库线程 |

另外两个细节：

- **不用 Room**。Room 会把注解处理器塞进宿主构建，还有版本冲突风险；这里就是一张表。
- **不用 `RequestBody.create(...)`**。那组静态工厂在 OkHttp 4 已废弃、OkHttp 5 被移除，
  改成自己实现 `RequestBody` 从字节重放，与 OkHttp 版本无关。

### 链路顺序与"原域名 → 实际域名"

```
应用拦截器链：[域名改写] → [抓包] → ...宿主的拦截器... → ConnectInterceptor
```

抓包排在域名改写之后，记录的是**真正发出去的地址**；改写前的原始 URL 通过
`Request.tag` 带过来一起入库，界面上就能显示 `api.example.com → api.test.com`。

### 真机验证（SM-A5260 / Android 14）

宿主发起的请求被完整录制：

```
已录制 1 条记录
最新一条: GET https://self-check.internal/ping state=failed
         error=UnknownHostException: Unable to resolve host "self-check.internal"
```

落库后的 SQLite 内容（同时含改写前后两个地址）：

```
state=failed  method=GET
url=https://self-check.internal/ping              ← 实际请求的地址
originalUrl=https://self-check.example.com/ping   ← 改写前的原始地址
error=UnknownHostException: ...
```

生产渠道回归：`prodSampleRelease` dex 中该库符号数仍为 0；AAR 体积从 19KB 涨到 40KB。

## 十三、Compose 跨版本兼容：一条硬约束

### 发现经过

UI 模块接进某真实项目后，抓包 Tab 一打开就崩：

```
NoSuchMethodError: No static method weight$default(
    Landroidx/compose/foundation/layout/RowScope;...) in class RowScope
    at CaptureListScreenKt.CaptureListScreen(CaptureListScreen.kt:47)
```

同样的代码在验证工程里完全正常。两边 Compose 只差一个小版本（某真实项目是 1.10.4，验证工程是 1.10.6）。

**之前"老库 + 新宿主 = 兼容"的结论是有条件的，这一条要更正。**

### 根因

`Modifier.weight(1f)` 里的 `weight` 是**接口成员**（`RowScope` / `ColumnScope`），
而且带默认参数。Kotlin 对这种写法会生成一个静态桥接方法：

```
invokestatic RowScope.weight$default:(RowScope;Modifier;FZILjava/lang/Object;)Modifier;
```

新版 Compose 换掉了接口的 JVM 默认实现方式，这个桥接方法**不存在了** ——
于是编译期一切正常，真机运行才抛 `NoSuchMethodError`。

### 边界：只有接口作用域受影响

对编译产物做了全量字节码扫描，结果很清楚：

| 类别 | 例子 | 跨版本稳定性 |
|---|---|---|
| **接口作用域**成员 | `RowScope.weight` / `ColumnScope.weight` / `LazyItemScope` | ❌ 不稳定，禁止使用默认参数 |
| 顶层函数 | `SizeKt.fillMaxWidth` / `PaddingKt.padding` / `SnapshotStateKt.mutableStateOf` | ✅ 稳定 |

修法很简单：**把默认参数显式写全**，编译器就会直接调接口方法而不是走桥接。

```kotlin
// ❌ 生成 RowScope.weight$default，较老的 Compose 宿主上会崩
Modifier.weight(1f)

// ✅ 直接调用接口方法，两端都正常
Modifier.weight(1f, fill = true)
```

### 固化成构建门禁

光写进文档不够 —— 这种问题只在真机、只在特定宿主版本上炸，排查成本极高。所以加了
`verifyComposeBridges` 任务（`./gradlew check` 会带上它）：扫描编译产物的字节码，
发现 `RowScope` / `ColumnScope` / `LazyItemScope` 等接口作用域的 `$default` 调用就让构建失败。

门禁本身做过反向验证：把 `weight(1f, fill = true)` 改回 `weight(1f)`，它会立刻失败并指出
具体是哪条指令：

```
发现 Compose 接口作用域的默认参数桥接调用，这在较老的 Compose 宿主上会 NoSuchMethodError。
  io.github.netlab.ui.capture.CaptureListScreenKt → invokestatic
  RowScope.weight$default:(...)
```

### 顺带修正的兼容性矩阵

原来的矩阵只有 Kotlin 版本一个维度，不够了 —— **Compose 版本必须单独作为一个维度**，
因为同一份 UI 产物在 Compose 1.10.4 和 1.10.6 上表现就不同。

## 十四、产物契约门禁

接入真实项目踩出来的坑，光记在文档里没用 —— 没人会在提测前记得逐条对照。所以把**已经真实
踩过的三类问题**做成了构建门禁，`./gradlew check` 会带上它们。

### core：`verifyArtifactContract`

| 检查项 | 挡的是哪个坑 |
|---|---|
| 发布 POM 里不能出现 `<dependencies>` | AGP 9 给纯 Java 库自动挂 `kotlin-stdlib:2.3.x`，写进元数据后宿主解析拿到更新版本，直接以 "Module was compiled with an incompatible version of Kotlin" 崩在编译期（该项目就是这么挂的） |
| 字节码里不能出现 `kotlin/` 引用 | 上一条的物理佐证：只要有一处引用，宿主就必须有 stdlib，而它是 compileOnly，运行期会 `NoClassDefFoundError` |

### ui：`verifyComposeBridges`

扫描编译产物，发现接口作用域（`RowScope` / `ColumnScope` / `LazyItemScope`…）的 `$default`
调用就让构建失败。门禁做过反向验证（把 `weight(1f, fill = true)` 改回 `weight(1f)`，它会
立刻失败并指出具体指令）。

### 两个门禁都不是摆设

加门禁的过程中它们各抓到一个真问题：

- `lintDebug` 抓到 `NotificationManager.areNotificationsEnabled()` 需要 API 24 而 minSdk 21
- `lintDebug` 抓到 `ConcurrentHashMap.newKeySet()` 同样需要 API 24

### 还没做的：多宿主矩阵

门禁只能挡住"已知的那一类"。完整的兼容性矩阵（Kotlin 2.0/2.2/2.3 × Compose 1.7/1.9/1.10
× AGP 8.13/9.0 各一个最小宿主，自动构建 + 面板冒烟）还没落地，需要更多构建时间与设备，
是这类问题的根本解法。

## 十五、多模块：一个验证过的已知限制

网络能力封装在独立 library 模块里是很常见的结构。这一节记录**实测结论**，
因为文档里"每个模块各自应用插件"那句断言经不起推敲。

### 现状（已验证）

插件只应用在 app 模块时，library 模块里的 `OkHttpClient` **不会被插桩**。
复现器在 `networklib` 模块（`LibraryNetwork.CLIENT`）：

```
插件只应用在 :app → LibraryNetwork 里 DomainSwitchAgent 引用数 = 0
```

原因是 `InstrumentationScope.PROJECT` 只覆盖"应用插件的那个模块"。

### 为什么"每个模块各自应用插件"这条路走不通

给 `networklib` 也应用插件后，插桩确实生效了（`install` + `installClient` 都注入了）。
但紧接着暴露出更严重的问题：

**library 模块没有渠道维度。** app 有 devTest / preProduct / tansLine 三个渠道，
而 library 只有 debug / release —— 它的插桩会**同时进入所有 app 渠道**，包括生产渠道。
生产包里带着"调用 `DomainSwitchAgent.install`"的字节码，运行时却没有这个类，
直接 `NoClassDefFoundError`。

也就是说，**"每个模块各自应用插件"和"生产渠道零痕迹"是互相冲突的**，
不能简单叠加。

### 试过的另一条路：InstrumentationScope.ALL

思路是在 app 模块用 `ALL`，让它连带插桩依赖里的 library 类 ——
这样渠道白名单仍由 app 统一控制，听起来是正解。**实测直接失败**：

```
Execution failed for task ':app:mergeExtDexDevTestDebug'
  Failed to transform okhttp-4.12.0.jar ... Error while dexing.
```

因为 `ALL` 会把 **okhttp 自身**也插桩，而我们重写的正是 okhttp 的调用点 ——
等于把自己的改造再改一遍，dex 阶段就崩了。

### 结论

多模块目前是**已知限制**，不是"配一下就好"的小事。要真正解决，需要：

1. 用 `ALL` 范围，**同时**加一套排除规则（排除 `okhttp3.**` / `okio.**` / `retrofit2.**` /
   `androidx.**` / `kotlin.**` 等一切不该动的包），或者
2. 换一种注入机制（例如不靠字节码插桩，改成运行时注册）

在解决之前，接入方需要知道：**网络层写在独立 library 模块里的项目，当前不会被接管。**

## 九、本次实测证据

插桩前后字节码对比（`SampleNetwork.<clinit>`）：

```
插桩前                                    插桩后
18: new OkHttpClient$Builder              18: new OkHttpClient$Builder
21: dup                                   21: dup
22: invokespecial <init>()V               22: invokespecial <init>()V
                                          25: dup                                          ← 插件注入
                                          26: invokestatic DomainSwitchAgent.install(...)   ← 插件注入
25: getstatic businessInterceptor         29: getstatic businessInterceptor
28: invokevirtual addInterceptor          32: invokevirtual addInterceptor
```

宿主业务拦截器仍然在 `addInterceptor` 里注册，但域名拦截器已经排在它前面。

渠道产物对比：

| 渠道 | dex 中 `io/github/netlab` 符号 | 合并 manifest | 插桩 |
|---|---|---|---|
| devTestDebug | 8 个类全部存在 | 含 `DomainSwitchInitProvider` | 有 |
| prodSampleRelease | 0 | 无 | 无 |

单元测试：6 个用例全部通过（注入顺序、幂等、单向改写、未命中放行、无规则零开销）。

### 真机运行时验证（SM-A5260 / Android 14）

安装 `devTestDebug` 后启动，`DevTestSelfCheckProvider` 输出：

```
拦截器顺序 = [DomainSwitchInterceptor, SampleNetwork$$ExternalSyntheticLambda0]
未配置规则时: https://api.example.com/v1/user -> 同一对象（reference equal = true）
配置规则后:   https://api.example.com/v1/user -> https://api.pre-test.internal/v1/user
清除规则后:   https://api.example.com/v1/user -> 同一对象（reference equal = true）
真实请求链路的失败信息: Unable to resolve host "api.pre-test.internal": No address associated with hostname
```

最后一行是端到端证据：宿主发起的是 `https://api.example.com/v1/user`，
而实际 DNS 解析的是 `api.pre-test.internal`。这说明：

1. 拦截器确实在 OkHttp 链路里，且排在宿主拦截器之前；
2. 改写发生在**连接建立之前**（application 层），不是 network 层的事后修改；
3. 规则是在 `OkHttpClient` **已经构建完成之后**才设置的，下一个请求立刻生效，
   全程没有重建 client、没有重启 App。
