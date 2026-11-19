package com.radium.inkwell.util

/** Feedback logs leave the device. Remove URLs and common credentials before packaging. */
internal object FeedbackLogSanitizer {
    private val url = Regex("(?i)https?://[^\\s<>\\\"]+")
    private val header = Regex("(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+")
    private val credential = Regex("(?i)([\\\"']?\\b(?:access[_-]?token|refresh[_-]?token|token|api[_-]?key|password|passwd|secret)\\b[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun sanitize(text: String): String = text
        .replace(header) { "${it.groupValues[1]}: [已隐藏]" }
        .replace(url, "[地址已隐藏]")
        .replace(credential) { "${it.groupValues[1]}[已隐藏]" }
        .replace(bearer, "Bearer [已隐藏]")
        .replace(email, "[邮箱已隐藏]")
}
