package com.radium.inkwell.data.prefs

import com.radium.inkwell.reader.api.ReaderSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * 一套可命名的排版方案：「白天紧凑」「夜里宽松」这类，在阅读设置里一键切换。
 *
 * **只管排版，不管纸色。** 纸色已经按日/夜两槽各记一套、跟着 App 日夜自动切（见 ReaderPrefs）；
 * 方案里再带一份纸色，白天点一下「夜里宽松」就会把日间槽刷成深色纸，两套机制互相打架。
 * 翻页方式、点击分区这些交互设置也不进方案 —— 它们跟「这一屏字长什么样」无关，切方案时变了只会让人意外。
 *
 * 字段都带默认值：老版本存下的方案缺了后加的字段，按默认补上而不是整条读不出来。
 */
@Serializable
data class LayoutScheme(
    val name: String,
    val fontSizeSp: Float = 18f,
    val fontId: String = ReaderSettings.FONT_SYSTEM,
    val lineSpacingMult: Float = 1.6f,
    val paragraphSpacingEm: Float = 0.6f,
    val letterSpacingEm: Float = 0f,
    val firstLineIndentEm: Float = 2f,
    val justify: Boolean = true,
    val titleScale: Float = 1.4f,
    val titleTopSpacingDp: Float = 24f,
    val headerTopSpacingDp: Float = 24f,
    val marginHorizontalDp: Float = 24f,
    val marginVerticalDp: Float = 28f,
) {
    fun applyTo(s: ReaderSettings): ReaderSettings = s.copy(
        fontSizeSp = fontSizeSp,
        fontId = fontId,
        lineSpacingMult = lineSpacingMult,
        paragraphSpacingEm = paragraphSpacingEm,
        letterSpacingEm = letterSpacingEm,
        firstLineIndentEm = firstLineIndentEm,
        justify = justify,
        titleScale = titleScale,
        titleTopSpacingDp = titleTopSpacingDp,
        headerTopSpacingDp = headerTopSpacingDp,
        marginHorizontalDp = marginHorizontalDp,
        marginVerticalDp = marginVerticalDp,
    )

    /**
     * 当前排版是不是就是这套方案。浮点比较留容差：设置经 DataStore 与 JSON 往返，
     * 1.6f 回来不一定还是同一个比特，严格相等会让刚点中的方案显示成「没选中」。
     */
    fun matches(s: ReaderSettings): Boolean =
        near(fontSizeSp, s.fontSizeSp) &&
            fontId == s.fontId &&
            near(lineSpacingMult, s.lineSpacingMult) &&
            near(paragraphSpacingEm, s.paragraphSpacingEm) &&
            near(letterSpacingEm, s.letterSpacingEm) &&
            near(firstLineIndentEm, s.firstLineIndentEm) &&
            justify == s.justify &&
            near(titleScale, s.titleScale) &&
            near(titleTopSpacingDp, s.titleTopSpacingDp) &&
            near(headerTopSpacingDp, s.headerTopSpacingDp) &&
            near(marginHorizontalDp, s.marginHorizontalDp) &&
            near(marginVerticalDp, s.marginVerticalDp)

    companion object {
        /** 方案名的长度上限：它要塞进一个 chip 里 */
        const val MAX_NAME_LENGTH = 12

        fun capture(name: String, s: ReaderSettings) = LayoutScheme(
            name = name,
            fontSizeSp = s.fontSizeSp,
            fontId = s.fontId,
            lineSpacingMult = s.lineSpacingMult,
            paragraphSpacingEm = s.paragraphSpacingEm,
            letterSpacingEm = s.letterSpacingEm,
            firstLineIndentEm = s.firstLineIndentEm,
            justify = s.justify,
            titleScale = s.titleScale,
            titleTopSpacingDp = s.titleTopSpacingDp,
            headerTopSpacingDp = s.headerTopSpacingDp,
            marginHorizontalDp = s.marginHorizontalDp,
            marginVerticalDp = s.marginVerticalDp,
        )

        private fun near(a: Float, b: Float) = abs(a - b) < 0.001f
    }
}

/** 方案列表 ↔ JSON 字符串。存在 DataStore 的一个键里，也原样进 WebDAV 的阅读设置块 */
object LayoutSchemeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(LayoutScheme.serializer())

    fun encode(schemes: List<LayoutScheme>): String = json.encodeToString(serializer, schemes)

    /** 读不懂（被手改坏、来自更新的版本且结构变了）就当没有，不能让一个坏键拖垮整份阅读设置 */
    fun decode(raw: String?): List<LayoutScheme> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /** 存一套：同名覆盖（就地替换，不挪位置），新名追加到末尾 */
    fun upsert(schemes: List<LayoutScheme>, scheme: LayoutScheme): List<LayoutScheme> {
        val i = schemes.indexOfFirst { it.name == scheme.name }
        return if (i >= 0) schemes.toMutableList().also { it[i] = scheme } else schemes + scheme
    }
}
