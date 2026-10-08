/**
 * ⚠️ 这个模块是「多模块限制」的复现器，不是示例。
 *
 * 它的网络层写在独立 library 模块里，插件只应用在 :app 上 ——
 * 这种情况下 LibraryNetwork 里的 OkHttpClient **不会**被插桩。
 * 保留它是为了让这个限制一直可复现、可验证，而不是只停留在文档里。
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.networklib"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 模拟"网络能力封装在独立的 library 模块里"这种常见结构
    implementation(libs.okhttp)
}
