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

.PHONY: help check check-core check-ui check-hosts publish publish-core publish-ui

help:
	@echo "make check        全部检查（core + ui）"
	@echo "make check-core   核心：单测 + 产物契约门禁"
	@echo "make check-ui     面板：lint + Compose 桥接门禁"
	@echo "make check-hosts  AGP 8.13.2 宿主验证工程构建"
	@echo "make publish      把 core 与 ui 发布到 mavenLocal（供宿主验证）"

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
