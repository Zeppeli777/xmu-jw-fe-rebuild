plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xmu.jw"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.xmu.jw"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 个人自用: 暂用 debug 签名, 需要正式签名时再配 signingConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 构建前把 ../web 同步进 assets, 前端改完直接重新构建即可生效
    val syncWeb by tasks.registering(Sync::class) {
        from(File(rootDir, "../web"))
        into("src/main/assets/web")
    }

    // 演示模式(仅 debug): 把 analysis/ 里抓包的真实样本 + 演示 PDF 同步进 debug assets
    val syncMockData by tasks.registering(Copy::class) {
        from(File(rootDir, "../analysis")) {
            include("xywccx-data.json", "jdfpm-data.json")
        }
        from(File(rootDir, "tools/demo-proof.pdf"))
        into("src/debug/assets/mock")
    }
    tasks.named("preBuild") { dependsOn(syncWeb, syncMockData) }
}

dependencies {
    // 零第三方依赖(运行时): HTTP 用 java.net, JSON 用系统 org.json, WebView 用框架自带
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
