package com.radium.inkwell.core.source

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Legado `bookSourceType` 取值。Inkwell 只跑小说正文引擎，导入/同步一律只收 [TEXT]。
 *
 * 0 文本 · 1 音频 · 2 图片(漫画) · 3 文件 · 4 视频（部分分支扩展）
 */
object BookSourceTypes {
    const val TEXT = 0
    const val AUDIO = 1
    const val IMAGE = 2
    const val FILE = 3
    const val VIDEO = 4

    fun isTextNovel(type: Int): Boolean = type == TEXT

    /** 跳过原因，直接拼进导入报告（「听书站: 音频源不支持」） */
    fun unsupportedReason(type: Int): String = when (type) {
        AUDIO -> "音频源不支持"
        IMAGE -> "漫画源不支持"
        FILE -> "文件源不支持"
        VIDEO -> "视频源不支持"
        else -> "非小说书源（type=$type）不支持"
    }

    /**
     * 从原始 JSON 抠类型。缺省 / 解析失败按文本（0）—— 与 Legado 默认一致。
     * 兼容数字、整型字符串、偶发浮点写法。
     */
    fun parse(obj: JsonObject): Int {
        val el = obj["bookSourceType"] ?: return TEXT
        val p = el as? JsonPrimitive ?: return TEXT
        p.intOrNull?.let { return it }
        p.longOrNull?.let { return it.toInt() }
        val raw = p.content.trim()
        raw.toIntOrNull()?.let { return it }
        raw.toDoubleOrNull()?.toInt()?.let { return it }
        return TEXT
    }
}
