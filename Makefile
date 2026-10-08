# netlab —— 仓库级构建入口
#
# 仓库里有三个彼此独立的 Gradle 构建，工具链并不相同：
#   library/         AGP 9 + Kotlin 2.3（core 是纯 Java，插件编译在 AGP 8.13 的 API 上）
#   ui/              Gradle 8.13 + Kotlin 2.0.21（必须是这个版本，见 README 的说明）
#   test-hosts/agp8/ Gradle 8.13
#
# Gradle 8.13 **不支持 JDK 25**，所以要显式指定 JDK 21。
# 不想依赖下面的默认值，可以在命令行覆盖：make check JAVA21=/path/to/jdk21

JAVA21 ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)

# Maven Central 发布：先落到本地文件型仓库（bundle 的目录形态），再打成 zip 上传。
# 注意：构建 bundle 需要配好签名凭据，否则产物里没有 .asc，Central 会直接拒。
BUNDLE_DIR ?= $(CURDIR)/build/central-bundle
BUNDLE_ZIP ?= $(CURDIR)/build/netlab-central-bundle.zip

# 发布凭据放在**仓库外**，权限 600，不进 git，也避免污染全局 gradle.properties
# （全局文件里塞 signingKey 会和别的项目的同名属性互相覆盖）：
#   signing.key        PGP 私钥（ASCII-armored）
#   signing.password   私钥口令
#   portal.user        Central Portal user token 的用户名
#   portal.password    Central Portal user token 的密码
NETLAB_SECRETS ?= $(HOME)/.gradle/netlab-signing

CENTRAL_API ?= https://central.sonatype.com
VERSION ?= 1.0.0
# USER_MANAGED：先校验，人工确认后再放行；AUTOMATIC：校验通过即发布
PUBLISHING_TYPE ?= USER_MANAGED

# 必须是单行 —— 多行的变量展开到 recipe 里会被拆成多条命令
NETLAB_SIGN_ENV = ORG_GRADLE_PROJECT_signingKey="$$(cat $(NETLAB_SECRETS)/signing.key)" ORG_GRADLE_PROJECT_signingPassword="$$(cat $(NETLAB_SECRETS)/signing.password)"
NETLAB_TOKEN = $$(printf '%s:%s' "$$(cat $(NETLAB_SECRETS)/portal.user)" "$$(cat $(NETLAB_SECRETS)/portal.password)" | base64 | tr -d '\n')

.PHONY: help check check-core check-ui check-hosts publish publish-core publish-ui \
        bundle clean-bundle check-secrets upload status central-publish central-drop

help:
	@echo "make check        全部检查（core + ui）"
	@echo "make check-core   核心：单测 + 产物契约门禁"
	@echo "make check-ui     面板：lint + Compose 桥接门禁"
	@echo "make check-hosts  AGP 8.13.2 宿主验证工程构建"
	@echo "make publish      把 core 与 ui 发布到 mavenLocal（供宿主验证）"
	@echo "make bundle       构建 Maven Central 上传用的 bundle zip（需 $(NETLAB_SECRETS)）"
	@echo "make upload       把 bundle 传给 Central Portal（默认 USER_MANAGED）"
	@echo "make status DEPLOYMENT=<id>           查询部署状态"
	@echo "make central-publish DEPLOYMENT=<id>  放行（不可撤销）"
	@echo "make central-drop DEPLOYMENT=<id>     丢弃部署"

check: check-core check-ui

check-core:
	cd library && ./gradlew :core:check

check-ui:
	cd ui && ./gradlew -Dorg.gradle.java.home=$(JAVA21) check

check-hosts:
	cd test-hosts/agp8 && ./gradlew -Dorg.gradle.java.home=$(JAVA21) :probe:assembleDevTestDebug

publish: publish-core publish-ui

publish-core:
	cd library && ./gradlew :core:publishToMavenLocal

publish-ui:
	cd ui && ./gradlew -Dorg.gradle.java.home=$(JAVA21) publishToMavenLocal

# 三个制品分别发布到同一个文件型仓库，目录结构就是 Central 要求的 bundle 布局：
#   io/github/openking88/netlab/1.0.0/…       （AAR + POM + sources + javadoc + .asc）
#   io/github/openking88/netlab-ui/1.0.0/…
#   io/github/openking88/netlab-gradle-plugin/1.0.0/…
# 之后把目录内容压成 zip，根目录直接是 io/…
# 没有签名凭据时直接失败，别产出"看着正常、上传才发现没有 .asc"的包
check-secrets:
	@test -s "$(NETLAB_SECRETS)/signing.key" || { echo "缺少私钥：$(NETLAB_SECRETS)/signing.key"; exit 1; }
	@test -s "$(NETLAB_SECRETS)/signing.password" || { echo "缺少口令：$(NETLAB_SECRETS)/signing.password"; exit 1; }

bundle: check-secrets
	rm -rf "$(BUNDLE_DIR)"
	mkdir -p "$(BUNDLE_DIR)"
	cd library && $(NETLAB_SIGN_ENV) ./gradlew :core:publish -Pnetlab.repo.url="file://$(BUNDLE_DIR)"
	cd ui && $(NETLAB_SIGN_ENV) ./gradlew -Dorg.gradle.java.home=$(JAVA21) publish -Pnetlab.repo.url="file://$(BUNDLE_DIR)"
	cd library && $(NETLAB_SIGN_ENV) ./gradlew -p plugin publish -Pnetlab.repo.url="file://$(BUNDLE_DIR)"
	rm -f "$(BUNDLE_ZIP)"
	# maven-metadata.xml 是"文件型仓库"的目录索引产物，Central 自己维护元数据，
	# 官方示例 bundle 里也没有它，打包时排掉，别把无关文件塞进上传包。
	@# 签名完整性门禁：每个制品都必须有同名 .asc，缺一个 Central 校验就整体失败
	@missing=""; for f in $$(cd "$(BUNDLE_DIR)" && find . -type f \
	    ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
	    ! -name 'maven-metadata.xml'); do \
	  [ -f "$(BUNDLE_DIR)/$$f.asc" ] || missing="$$missing $$f"; \
	done; \
	if [ -n "$$missing" ]; then echo "以下制品缺 .asc，Central 会拒：$$missing"; exit 1; fi
	cd "$(BUNDLE_DIR)" && zip -qr "$(BUNDLE_ZIP)" . -x 'maven-metadata.xml*' -x '*/maven-metadata.xml*'
	@echo "bundle 已生成：$(BUNDLE_ZIP)"
	@echo "签名完整性检查通过；.asc 文件数：$$(unzip -l "$(BUNDLE_ZIP)" | grep -c '\.asc$$')"

clean-bundle:
	rm -rf "$(BUNDLE_DIR)" "$(BUNDLE_ZIP)"

# 上传 bundle。USER_MANAGED 时只做校验、不进中央仓库；校验通过后可以先把部署
# 当 Maven 仓库给宿主自测，确认没问题再 make central-publish。
upload: check-secrets
	@test -s "$(NETLAB_SECRETS)/portal.user" || { echo "缺少 user token：$(NETLAB_SECRETS)/portal.user"; exit 1; }
	@test -f "$(BUNDLE_ZIP)" || { echo "还没有 bundle，先跑 make bundle"; exit 1; }
	curl --silent --show-error --request POST \
	  --url "$(CENTRAL_API)/api/v1/publisher/upload?name=netlab-$(VERSION)&publishingType=$(PUBLISHING_TYPE)" \
	  --header "Authorization: Bearer $(NETLAB_TOKEN)" \
	  --form "bundle=@$(BUNDLE_ZIP);type=application/octet-stream" \
	  -w '\n[HTTP %{http_code}] 上面那串就是 deployment id\n'

status: check-secrets
	@test -n "$(DEPLOYMENT)" || { echo "用法：make status DEPLOYMENT=<deploymentId>"; exit 1; }
	curl --silent --show-error --request POST \
	  --url "$(CENTRAL_API)/api/v1/publisher/status?id=$(DEPLOYMENT)" \
	  --header "Authorization: Bearer $(NETLAB_TOKEN)" | python3 -m json.tool

# ⚠️ 不可撤销：放行后制品进入 Maven Central，同坐标同版本不能覆盖也不能删除
central-publish: check-secrets
	@test -n "$(DEPLOYMENT)" || { echo "用法：make central-publish DEPLOYMENT=<deploymentId>"; exit 1; }
	curl --silent --show-error --request POST \
	  --url "$(CENTRAL_API)/api/v1/publisher/deployment/$(DEPLOYMENT)" \
	  --header "Authorization: Bearer $(NETLAB_TOKEN)" \
	  -w '\n[HTTP %{http_code}]\n'

central-drop: check-secrets
	@test -n "$(DEPLOYMENT)" || { echo "用法：make central-drop DEPLOYMENT=<deploymentId>"; exit 1; }
	curl --silent --show-error --request DELETE \
	  --url "$(CENTRAL_API)/api/v1/publisher/deployment/$(DEPLOYMENT)" \
	  --header "Authorization: Bearer $(NETLAB_TOKEN)" \
	  -w '\n[HTTP %{http_code}]\n'
