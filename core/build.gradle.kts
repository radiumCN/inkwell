plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    // IcuRegexCompatTest 扫的是**整个仓库**的 .kt（app、reader 里的正则在真机上一样会炸），可 Gradle 只认 core 自己的
    // 源码是输入：只改了 app 时 :core:test 被当成 UP-TO-DATE 跳过，本地全绿、CI 一跑才挂（0.5.1-beta.12 栽过）。
    // 把那些文件也声明成输入，改了哪个模块的代码都会重跑。
    inputs.files(
        rootProject.fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPropertyName("repoKotlinSources").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    // 见 libs.versions.toml 的 jspecify 注释：补上 Jsoup 没传过来的注解，给 Kotlin 2.4 推断用
    compileOnly(libs.jspecify)
    implementation(libs.rhino)
    implementation(libs.juniversalchardet)
    implementation(libs.json.path)
    implementation(libs.xpath)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
