package com.radium.inkwell.update

/**
 * 更新源。
 *
 * - [GITHUB]：直接读 GitHub Releases（现状，需能访问 GitHub）。
 * - [SERVER]：中转服务器，把上游 Release 镜像到自己的服务器，GitHub 受限时也能用，
 *   且带 sha256、支持应用内下载→校验→安装。
 *
 * 与 [UpdateChannel] 正交：任一源都有 stable / beta 两个渠道。
 */
enum class UpdateSource(val label: String) {
    GITHUB("GitHub"),
    SERVER("中转服务器"),
}

/** 一次检查更新的结果，GitHub 与中转服务器共用 */
sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val info: UpdateInfo) : CheckResult
    data class Failed(val message: String) : CheckResult
}

/**
 * 统一的「有新版本」信息。
 *
 * - [directInstall] 非空（中转服务器）→ 可**应用内**下载 → 校验 sha256 → 拉起系统安装器；
 * - 为空（GitHub）→ 走 [browserUrl] 用浏览器下载 APK / 查看 Release。
 */
data class UpdateInfo(
    /** 不带 v 前缀，如 "0.1.0-beta.63" */
    val latestVersion: String,
    val notes: String,
    val isPrerelease: Boolean,
    val directInstall: DirectInstall? = null,
    /** 浏览器兜底 URL：GitHub 的 APK 直链或 Release 页；directInstall 为空时用 */
    val browserUrl: String? = null,
    /** browserUrl 是否指向 APK 直链（决定按钮文案：下载 APK vs 查看 Release） */
    val browserIsApk: Boolean = false,
)

/** 应用内直装的目标：直链 + 校验信息 */
data class DirectInstall(
    val url: String,
    val sha256: String,
    val size: Long,
    val filename: String,
)

/** 更新源的统一抽象：GitHub 与中转服务器各一份实现 */
interface UpdateProvider {
    /** [versionName] 不带 v 前缀（PackageInfo.versionName，如 "0.1.0-beta.63"） */
    suspend fun check(channel: UpdateChannel, versionName: String): CheckResult
}
