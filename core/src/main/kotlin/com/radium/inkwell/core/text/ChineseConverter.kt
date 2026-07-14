package com.radium.inkwell.core.text

import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.radium.inkwell.core.model.ContentElement

/**
 * 简繁转换。
 *
 * 用 opencc4j 而不是手搓字表：半吊子的映射会**静默**把字转错，而读者根本不会意识到
 * 是转换出的错，只会觉得"这书源怎么有错别字"—— 这种 bug 事后无从查起。
 */
object ChineseConverter {

    fun toSimplified(text: String): String = ZhConverterUtil.toSimple(text)

    fun toTraditional(text: String): String = ZhConverterUtil.toTraditional(text)

    /** 按元素转换正文；图片与分隔符原样放行 */
    fun convert(elements: List<ContentElement>, transform: (String) -> String): List<ContentElement> =
        elements.map { el ->
            when (el) {
                is ContentElement.Paragraph -> ContentElement.Paragraph(transform(el.text))
                is ContentElement.Heading -> ContentElement.Heading(el.level, transform(el.text))
                else -> el
            }
        }
}
