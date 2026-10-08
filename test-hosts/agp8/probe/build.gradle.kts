plugins {
    id("com.android.application")
    id("io.github.netlab")
}

domainSwitch {
    // 只有这两个渠道接入；prodSample 是占位的"生产渠道"，不插桩也不加依赖
    flavors.set(setOf("devTest", "preProduct"))
}

android {
    namespace = "com.example.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.probe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "env"
    productFlavors {
        create("devTest") {
            dimension = "env"
            // 插件应当自动从这些配置里识别出域名，不需要人工维护第二份清单
            buildConfigField("String", "BASE_URL", "\"https://api-dev.example.com\"")
            buildConfigField("String", "URL_WEB_HELP", "\"https://h5-dev.example.com/help\"")
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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
}
