plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.radium.inkwell.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 测哪个 App。测试代码跑在**另一个进程**里 —— 这是能测"冷启动"的前提，
    // 同进程里没法把自己杀掉再计时。
    targetProjectPath = ":app"
}

kotlin {
    jvmToolchain(21)
}

baselineProfile {
    // 用已连接的设备/模拟器生成（CI 里由 baseline-profile.yml 起一台）。
    // 另一条路是 Gradle Managed Device，但那样每次都要它自己下镜像、起机器，
    // 本地和 CI 各来一遍，慢且不受控 —— 我们自己起，起完复用。
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
