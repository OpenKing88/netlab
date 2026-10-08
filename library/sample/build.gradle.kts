plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  // 零代码接入：宿主只写这一行插件，依赖与插桩都由插件按渠道处理
  id("io.github.openking88.netlab")
}

// 渠道白名单：只有 devTest / preProduct 会被插桩并注入运行时依赖，
// prodSample（占位生产渠道）既不插桩也不加依赖 —— 产物里零痕迹
domainSwitch {
    flavors.set(setOf("devTest", "preProduct"))
    // 故意用一组非默认值，验证插件配置能否落进运行时
    capture.set(true)
    maxRecords.set(42)
    maxBodyBytes.set(64 * 1024)
}

android {
    namespace = "com.example.domainswitchverify"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.domainswitchverify"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 接入开关以「渠道」为单位，而不是 debug/release
    flavorDimensions += "env"
    productFlavors {
        create("devTest") {
            dimension = "env"
            // 插件会自动把这些配置里的域名识别成候选清单
            buildConfigField("String", "BASE_URL", "\"https://api.example.com\"")
            buildConfigField("String", "URL_WEB_HELP", "\"https://h5.example.com/help\"")
        }
        create("preProduct") {
            dimension = "env"
            buildConfigField("String", "BASE_URL", "\"https://api-pre.example.com\"")
            buildConfigField("String", "URL_WEB_HELP", "\"https://h5-pre.example.com/help\"")
        }
        create("prodSample") { dimension = "env" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // 网络层：宿主原本就有的依赖，域名切换库不强行指定版本
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.gson)
}
