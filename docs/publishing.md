# 发布到 Maven Central

> 这份文档记录 netlab 的发布链路，以及**每条命令为什么长这样**。
> 结论都是照着官方文档核对过的，凡是"官方不支持、社区方案"的地方都单独标注了。

## 0. 先给结论

**Sonatype 官方目前没有提供 Gradle 插件。** 官方路径只有一条：

> 本地把制品（AAR / POM / sources / javadoc / 签名）构建成一个 **bundle zip** →
> 调 **Central Portal 的上传 API** 提交 → 审核通过后自动同步到 `repo1.maven.org`。

想让 Gradle 一步直传，只能借社区插件（`vanniktech/gradle-maven-publish-plugin`、
`JReleaser`、`GradleUp/nmcp` 等），**这些都不受 Sonatype 官方支持**，出问题官方不认。
本仓库选择"官方路径 + 一个 Make 目标"，依赖面最小。

要发布三个坐标：

| 坐标 | 内容 |
|---|---|
| `io.github.openking88:netlab:1.0.0` | 核心 AAR |
| `io.github.openking88:netlab-ui:1.0.0` | 面板 AAR |
| `io.github.openking88:netlab-gradle-plugin:1.0.0` | Gradle 插件（附带 `io.github.openking88.netlab` 的 marker 制品） |

---

## 1. 一次性准备（要用你自己的账号，我代劳不了）

### 1.1 注册 Central Portal 并认领命名空间

1. 打开 <https://central.sonatype.com> 用 **GitHub 账号**登录。
2. 新增命名空间 `io.github.openking88`。
   - 用 GitHub 登录的情况下，Sonatype 会用你 GitHub 身份对应的 `github.io` 域**自动**
     认领 `io.github.<GitHub 用户名>`，通常不需要任何操作。
   - 如果没自动认领（或换了账号），走 "Verifying a Namespace → By Code Hosting Services"：
     按提示在 GitHub 上建一个**临时的公开仓库**，仓库名 = Portal 给的 **Verification Key**，
     验证通过后即可删掉。
   - DNS TXT 那条路是给自有域名（`com.example` 之类）用的，这里用不上；
     ⚠️ 别在没加 TXT 记录的情况下去点确认，NXDOMAIN 会被缓存，反而拖慢验证。
3. 等命名空间状态变成 **Verified** 再往下走。

> 为什么选 `io.github.openking88`：本仓库的 groupId 必须是自己能证明归属的命名空间，
> `io.github.<GitHub 用户名>` 是最省事的一档。

### 1.2 生成 GPG 密钥

Central **强制要求每个制品都有 `.asc` 签名**，没有签名在上传校验阶段就会被拒。

**好消息：签名不需要系统装 gpg。** 签名是 Gradle 在自己的进程里用内存密钥完成的
（`useInMemoryPgpKeys`），gpg 只在两件事上有用：生成密钥、把公钥发到 keyserver。
而 gpg 在某些机器上根本装不上 —— 本机就是这种情况，Homebrew 直接拒绝：

```
Error: unknown or unsupported macOS version: :dunno
```

所以仓库自带了一个工具，用 BouncyCastle 现场生成密钥：

```bash
./tools/gen-signing-key.sh                     # 默认 UID 与输出目录
./tools/gen-signing-key.sh "Name <mail@host>"  # 自定义 UID
```

它会在 `~/.gradle/netlab-signing/` 下生成 `signing.key` / `signing.password` / `public.asc`
（权限 600），并打印把公钥发到 keyserver 的现成命令。

**如果本机有 gpg**，用官方那套也行，效果等价：

```bash
gpg --gen-key                                              # 建议 RSA 4096，设置口令
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>  # 公钥必须分发出去
gpg --export-secret-keys --armor <KEY_ID>                  # 输出为 ASCII-armored 私钥
```

不管用哪条路，**公钥一定要发到 keyserver**，否则校验阶段会报
"Invalid signature ... public key not found"：

```bash
curl -sS -X POST --data-urlencode "keytext=$(cat ~/.gradle/netlab-signing/public.asc)" \
  https://keyserver.ubuntu.com/pks/add
```

回查（用**完整指纹**，短 ID 在集群各节点上同步有延迟）：

```bash
curl -sS "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<完整指纹>" | head -3
```

### 1.3 生成上传用的 user token

打开 <https://central.sonatype.com/usertoken> 生成一对 token，得到 `username` / `password`。

> ⚠️ **这是账号密码的等价物，等同于明文口令**。用完就 revoke 重新生成一份，
> 不要贴进任何聊天记录、issue、CI 日志。

### 1.4 把凭据放到仓库外的专用目录

凭据放在**仓库外的专用目录** `~/.gradle/netlab-signing/`（`NETLAB_SECRETS`，可覆盖）：

| 文件 | 内容 | 来源 |
|---|---|---|
| `signing.key` | PGP 私钥（ASCII-armored） | 1.2 |
| `signing.password` | 私钥口令 | 1.2 |
| `portal.user` | Central user token 用户名 | 1.3 |
| `portal.password` | Central user token 密码 | 1.3 |

```bash
printf '%s' '<1.3 的 username>' > ~/.gradle/netlab-signing/portal.user
printf '%s' '<1.3 的 password>' > ~/.gradle/netlab-signing/portal.password
chmod 600 ~/.gradle/netlab-signing/portal.*
```

> **为什么不写全局 `~/.gradle/gradle.properties`**：那个文件是所有项目共用的，
> 往里塞 `signingKey` / `signingPassword` 会和其他项目的同名属性互相覆盖。
> Makefile 在调用 Gradle 时用 `ORG_GRADLE_PROJECT_*` 环境变量注入，作用域只限这次构建。
> 三个构建（`library/core`、`ui`、`library/plugin`）都会读到同一份凭据；
> 没配就只发 `mavenLocal`，本地验证不受影响。

`netlab.repo.url` 不需要配置 —— 构建 bundle 时用命令行传本地目录，上传走 API。

---

## 2. 构建 bundle

```bash
make bundle
```

没有配签名凭据时会**直接报错退出**（`make check-secrets`），
不会产出"看着正常、上传才发现没有 `.asc`"的包。

它做的事，拆开就是三条命令（三块制品分别发到同一个本地文件型仓库）：

```bash
REPO=$PWD/build/central-bundle
(cd library && ORG_GRADLE_PROJECT_signingKey="$(cat ~/.gradle/netlab-signing/signing.key)" \
                ORG_GRADLE_PROJECT_signingPassword="$(cat ~/.gradle/netlab-signing/signing.password)" \
                ./gradlew :core:publish -Pnetlab.repo.url="file://$REPO")
# ui 与 plugin 同理，ui 还要带上 -Dorg.gradle.java.home=$JAVA21
(cd "$REPO" && zip -qr ../netlab-central-bundle.zip . -x 'maven-metadata.xml*' -x '*/maven-metadata.xml*')
```

产物落在 `build/central-bundle/`，目录结构恰好就是 Central 要的布局：

```
io/github/openking88/netlab/1.0.0/
    netlab-1.0.0.aar
    netlab-1.0.0.pom
    netlab-1.0.0-sources.jar
    netlab-1.0.0-javadoc.jar
    netlab-1.0.0.aar.asc        ← 每份制品都要有对应的 .asc
    …
```

打包时顺手排掉了 `maven-metadata.xml`：那是"文件型仓库"的目录索引产物，
Central 自己维护元数据，官方示例 bundle 里也没有它。

校验文件方面，Central 的硬性要求是 **`.md5` 和 `.sha1` 必须有**；
`.sha256` / `.sha512` 属于"支持但不强制"，本地构建会一并生成，留着无害。
`.asc` 不用配校验文件，校验文件也不需要 `.asc`。

打包前有一道**签名完整性门禁**：bundle 目录里每个制品都必须有同名 `.asc`，
缺一个就直接失败（Central 会因为一个缺签名而整体拒掉，本地先拦住更省事）。
成功后输出的 `.asc` 文件数应为 **15**：

| 坐标 | 制品 |
|---|---|
| `netlab` | aar / pom / sources / javadoc |
| `netlab-ui` | aar / pom / sources / javadoc / module |
| `netlab-gradle-plugin` | jar / pom / sources / javadoc / module |
| `io.github.openking88.netlab.gradle.plugin`（marker） | pom |

想单独复核也可以：

```bash
unzip -l build/netlab-central-bundle.zip | grep -c '\.asc$'
```

---

## 3. 上传

```bash
make upload                                   # USER_MANAGED（默认）：只校验，人工放行
make upload PUBLISHING_TYPE=AUTOMATIC         # 校验通过即发布
```

等价的手工 curl（token 从凭据目录读，别敲进命令行历史）：

```bash
TOKEN=$(printf '%s:%s' "$(cat ~/.gradle/netlab-signing/portal.user)" \
                       "$(cat ~/.gradle/netlab-signing/portal.password)" | base64 | tr -d '\n')

curl --request POST \
  --url 'https://central.sonatype.com/api/v1/publisher/upload?name=netlab-1.0.0&publishingType=USER_MANAGED' \
  --header "Authorization: Bearer $TOKEN" \
  --form 'bundle=@build/netlab-central-bundle.zip;type=application/octet-stream'
```

要点：

- **`Content-Type` 是 `multipart/form-data`**，**只有一个 part**，名字必须叫 **`bundle`**，
  part 的 content-type 是 `application/octet-stream`，并且要带上文件名
  （官方示例就是 `--form bundle=@central-bundle.zip`）。
- 查询参数 `name` 只是给人看的（Portal 里显示部署名），`publishingType` 二选一：
  - `AUTOMATIC`：校验通过后自动发布；
  - `USER_MANAGED`：校验通过后停在 Portal 等你点 Publish（**不传时的默认值**）。
  首发建议用 `USER_MANAGED`，先看清校验结果再放行。
- 认证头是 `Authorization: Bearer <base64(username:password)>`，(用户名:密码) 做 base64，
  不是 Basic 认证（API 也认 `UserToken <base64>`，但官方推荐 `Bearer`）。
- 单包上限 **1 GB**，一次只能传一个 zip，一个包里可以有多个组件（我们三个坐标打在一起）。

成功返回 **HTTP 201**，响应体里是 deployment id。

---

## 4. 查询部署状态

```bash
make status DEPLOYMENT=<deploymentId>
```

底层是 `POST /api/v1/publisher/status?id=<deploymentId>` —— **注意是 POST，不是 GET**。

状态流转：`PENDING` → `VALIDATING` → `VALIDATED` → `PUBLISHING` → `PUBLISHED`；
任一步失败会变 `FAILED`，并在 Portal 的 Deployments 页给出具体原因。
`USER_MANAGED` 的部署在 `VALIDATED` 之后会一直等你确认。

### 放行 / 丢弃

```bash
make central-publish DEPLOYMENT=<deploymentId>   # 放行 → 204
make central-drop    DEPLOYMENT=<deploymentId>   # 丢弃（VALIDATED / FAILED 可用）
```

⚠️ **`central-publish` 不可撤销**：放行后制品进入 Maven Central，
同坐标同版本既不能覆盖也不能删除。首发务必先在 `VALIDATED` 状态做完下面这步自测。

### 先发布前自测

这是当初决定用 `USER_MANAGED` 的另一个理由：**校验通过但还没放行（VALIDATED）的部署，
可以当成一个 Maven 仓库直接给宿主消费**。渠道里把仓库地址临时指过去，验证
"插件解析得到、`domainSwitch` 配置生效、面板能打开"，确认没问题再去点 Publish：

```kotlin
// settings.gradle.kts（仅本地自测时临时加，验完删掉）
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://central.sonatype.com/api/v1/publisher/deployments/download")
            credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = "Bearer <base64 的 token>"
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
        google()
        mavenCentral()
    }
}
```

> 首次发布通常是人工审核，同步到 `repo1.maven.org` 需要一段时间，别反复重传。

---

## 5. 验证发布结果

```bash
curl -sI https://repo1.maven.org/maven2/io/github/openking88/netlab/1.0.0/netlab-1.0.0.pom
```

`200` 即已可被消费。随后宿主里把 `mavenLocal()` 换成 `mavenCentral()`，
插件坐标带上版本号即可：

```kotlin
plugins { id("io.github.openking88.netlab") version "1.0.0" }
```

---

## 6. 会被 Central 拒掉的常见原因

| 现象 | 原因 |
|---|---|
| `Missing signature` / 校验报缺 `.asc` | `signingKey` / `signingPassword` 没配，或配错口令 |
| `Invalid signature ... public key not found` | 公钥没发到 keyserver |
| 校验报缺 `.md5` / `.sha1` | 这两样是硬性要求；本仓库由 Gradle 自动生成，改动发布配置时别关掉 |
| 缺 `-sources.jar` / `-javadoc.jar` | 三个模块都已注册这两个制品；若改构建，别把它们删掉 |
| POM 缺 `name` / `description` / `url` / `licenses` / `developers` / `scm` | 每个公开制品都要求元数据齐全，插件 marker 制品也一样 |
| `Namespace not verified` | 1.1 没做完 |
| 版本号已存在 | Central 上同坐标同版本**不可覆盖**，只能换版本重发 |
| 上传直接失败、连部署都没建出来 | 先看包是不是超过 1 GB、能不能本地正常解压 |

---

## 7. 发下一个版本

1. 改三处 `version`：`library/core/build.gradle.kts`、`ui/build.gradle.kts`、
   `library/plugin/build.gradle.kts`（以及插件 DSL 里指向 `netlab` / `netlab-ui` 的默认版本约定）。
2. 在 `CHANGELOG.md` 顶部的「未发布」下补这一版的内容，并把 `[未发布]` 的比较链接指到新 tag。
3. 打 tag 并推：`git tag vX.Y.Z && git push origin vX.Y.Z`。
4. `make bundle VERSION=X.Y.Z` → `make upload VERSION=X.Y.Z` → 校验通过后
   `make central-publish DEPLOYMENT=<id>`。

签名密钥不用换，除非它泄露或过期 —— 同一把密钥可以一直签后续版本。

---

## 8. 官方 vs 社区

| 方案 | 官方支持 | 说明 |
|---|---|---|
| 构建 bundle + Portal API（本仓库用法） | ✅ | 依赖最少，失败点最清楚 |
| Portal 网页手动上传 zip | ✅ | 不想写脚本时可用，就是每次要手动点 |
| Portal 的 OSSRH Staging API | ✅ | 从老 OSSRH 迁移过来的项目可以用，保留原样即可 |
| `JReleaser` | ❌（社区） | 官方只单独介绍了它，支持 Portal 发布，配 Gradle 插件用 |
| `vanniktech/gradle-maven-publish-plugin` | ❌（社区） | 一行 `publishToMavenCentral()`，屏蔽了底层细节 |
| `GradleUp/nmcp` | ❌（社区） | 轻量，只管上传这一步 |

官方原话（Gradle 那页）：**目前没有官方 Gradle 插件，Gradle 支持在 roadmap 上**；
下面那批社区插件"**NOT SUPPORTED by Sonatype**，用之前先掂量自己的风险承受度，
也不要把问题提给 Sonatype 的支持团队"。

参考：

- Central Portal 文档：<https://central.sonatype.org/publish/publish-portal-api/>
- 命名空间与账号：<https://central.sonatype.org/register/central-portal/>
- 上传用 token：<https://central.sonatype.com/usertoken>
- 要求清单（中央仓库验收标准）：<https://central.sonatype.org/publish/requirements/>
