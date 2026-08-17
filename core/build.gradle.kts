plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
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
