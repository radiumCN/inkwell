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
    // 按 37.1 编译。最早是 Compose 1.13 beta（alpha BOM）的 AAR 元数据要求的，37.0 在 checkAarMetadata 就挂；
    // 回到稳定 BOM 后没再去掉：同期的 activity / lifecycle 也在往 37.1 走，CI 的三条工作流都已经装好 37.1。
    // 只影响编译期可见的 API，targetSdk 与运行行为不变。
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.radium.inkwell"
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 37
        // 版本号单点配置在 gradle/libs.versions.toml 的 [versions] inkwell
        // versionCode = 基础版本 × 1000 + 预发布序号，正式版记 999：
        //   0.3.12-beta.1 → 312001，0.3.12-beta.2 → 312002，0.3.12 → 312999
        // 从前预发布后缀被忽略，同一基础版本的所有 beta 与正式版同码。同码允许覆盖安装，于是
        // beta.3 上装回 beta.1 系统也不拦 —— 旧包的数据库版本更低时，Room 走降级兜底
        // （fallbackToDestructiveMigrationOnDowngrade）把整个书库静默清空。现在每一版严格递增，
        // 装回旧包会被系统按降级拒掉。旧方案发出过的最大码是 0.3.11 → 311，新方案任何一版都远大于它。
        val appVersion = libs.versions.inkwell.get()
        versionName = appVersion
        val baseCode = appVersion.substringBefore("-").split(".").let { (major, minor, patch) ->
            major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
        }
        val preRelease = appVersion.substringAfter("-", missingDelimiterValue = "")
        val preNumber = if (preRelease.isEmpty()) {
            999
        } else {
            // tag 历史里只有 beta.N 一种写法；别的后缀在配置期直接报错，免得悄悄算出一个不递增的码
            Regex("""beta\.(\d+)""").matchEntire(preRelease)?.groupValues?.get(1)?.toInt()
                ?.takeIf { it in 1..998 }
                ?: error("预发布后缀只支持 beta.1 ~ beta.998，实际是「$preRelease」")
        }
        versionCode = baseCode * 1000 + preNumber
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

// Baseline Profile 夹具（ProfileSeed）只在生成器跑的那两个变体里开。
// MainActivity 认启动 extra 导入一本「Benchmark」txt；而桌面 alias 是 exported 的，任何应用都能带着
// 这个 extra 拉起我们 —— 正式包里放着它，就是谁都能往用户书架上塞一本书，再经 WebDAV 扩散到所有设备。
// 不能按 debuggable 判：baselineprofile 插件生成的 nonMinifiedRelease / benchmarkRelease 都不可调试，
// 按它关掉，profile 就生成不出来了。默认值 false 在 res/values/profile_seed.xml。
androidComponents {
    // AGP 9 起 resValues 默认关闭，关着时下面的 put 只换来一句警告、什么都不生成
    finalizeDsl { android -> android.buildFeatures.resValues = true }
    onVariants { variant ->
        if (variant.buildType == "nonMinifiedRelease" || variant.buildType == "benchmarkRelease") {
            variant.resValues.put(
                variant.makeResValueKey("bool", "profile_seed_enabled"),
                com.android.build.api.variant.ResValue("true", "Baseline Profile 生成器专用变体"),
            )
        }
    }
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
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
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
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)

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
