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

.PHONY: help check check-core check-ui check-hosts publish publish-core publish-ui bundle clean-bundle

help:
	@echo "make check        全部检查（core + ui）"
	@echo "make check-core   核心：单测 + 产物契约门禁"
	@echo "make check-ui     面板：lint + Compose 桥接门禁"
	@echo "make check-hosts  AGP 8.13.2 宿主验证工程构建"
	@echo "make publish      把 core 与 ui 发布到 mavenLocal（供宿主验证）"
	@echo "make bundle       构建 Maven Central 上传用的 bundle zip（需签名凭据）"

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
bundle:
	rm -rf "$(BUNDLE_DIR)"
	mkdir -p "$(BUNDLE_DIR)"
	cd library && ./gradlew :core:publish -Pnetlab.repo.url="file://$(BUNDLE_DIR)"
	cd ui && ./gradlew -Dorg.gradle.java.home=$(JAVA21) publish -Pnetlab.repo.url="file://$(BUNDLE_DIR)"
	cd library && ./gradlew -p plugin publish -Pnetlab.repo.url="file://$(BUNDLE_DIR)"
	rm -f "$(BUNDLE_ZIP)"
	# maven-metadata.xml 是"文件型仓库"的目录索引产物，Central 自己维护元数据，
	# 官方示例 bundle 里也没有它，打包时排掉，别把无关文件塞进上传包。
	cd "$(BUNDLE_DIR)" && zip -qr "$(BUNDLE_ZIP)" . -x 'maven-metadata.xml*' -x '*/maven-metadata.xml*'
	@echo "bundle 已生成：$(BUNDLE_ZIP)"
	@echo "上传前先自查签名是否齐全：unzip -l $(BUNDLE_ZIP) | grep -c '\.asc$$'"

clean-bundle:
	rm -rf "$(BUNDLE_DIR)" "$(BUNDLE_ZIP)"
