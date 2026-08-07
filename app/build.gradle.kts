plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.radium.inkwell"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.radium.inkwell"
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        // 版本号单点配置在 gradle/libs.versions.toml 的 [versions] inkwell
        // versionCode 忽略预发布后缀（0.1.0-beta.1 与 0.1.0 同码，允许同码覆盖安装）
        val appVersion = libs.versions.inkwell.get()
        versionName = appVersion
        versionCode = appVersion.substringBefore("-").split(".").let { (major, minor, patch) ->
            major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
        }
    }

    // 正式签名从环境变量注入（CI 用）；未配置时回落 debug 签名，本地开发无感
    val hasReleaseSigning = !System.getenv("SIGNING_KEYSTORE_PATH").isNullOrBlank()
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(System.getenv("SIGNING_KEYSTORE_PATH"))
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
}

// Compose 编译器指标：哪些 composable 不可跳过（non-skippable）、哪些入参类型不稳定。
// 默认关闭 —— 开着会给每次编译多写一批报告文件，平时不需要。要看时：
//   ./gradlew :app:assembleRelease -PcomposeMetrics
// 产物在 app/build/compose_metrics/，*-composables.txt 是逐函数的 skippable/restartable 结论。
composeCompiler {
    if (project.hasProperty("composeMetrics")) {
        reportsDestination = layout.buildDirectory.dir("compose_metrics")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":reader"))

    // 装包时把 Baseline Profile 交给 ART 去 AOT 编译。**没有它 profile 进了包也不生效**
    // （Play 会自己装，但我们走的是 GitHub Release + 自建中转，只能自己装）
    implementation(libs.androidx.profileinstaller)
    // 生成器模块的产物由此并进 :app 的包
    baselineProfile(project(":baselineprofile"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // WebView 渲染只能在真机/模拟器上验证，JVM 单测覆盖不到
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
