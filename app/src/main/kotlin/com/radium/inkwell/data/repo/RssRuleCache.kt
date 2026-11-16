package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.rss.LegadoRssConverter
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.db.entity.RssSourceEntity

/**
 * 只缓存规则内容的转换结果。启停、排序、分组不改规则，不该让整组重新解析。
 * 按条数和原文字符数双重限额；坏规则也记住 null，内容没变时不反复尝试转换。
 */
internal class RssRuleCache(
    private val maxEntries: Int = 64,
    private val maxChars: Int = 1_000_000,
    private val decode: (RssSourceEntity) -> RssSourceRule? = ::decodeRssRule,
) {
    private data class Entry(val json: String, val sourceJson: String, val version: Int, val rule: RssSourceRule?) {
        val chars: Int get() = json.length + sourceJson.length
        fun matches(e: RssSourceEntity) = json == e.json && sourceJson == e.sourceJson && version == e.converterVersion
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var chars = 0

    @Synchronized
    fun ruleOf(e: RssSourceEntity): RssSourceRule? {
        val old = entries[e.id]
        if (old != null && old.matches(e)) return old.rule?.withEnabled(e.enabled)
        if (old != null) { entries.remove(e.id); chars -= old.chars }
        val rule = decode(e)
        val entry = Entry(e.json, e.sourceJson, e.converterVersion, rule)
        if (maxEntries > 0 && entry.chars <= maxChars) {
            entries[e.id] = entry
            chars += entry.chars
            val iterator = entries.values.iterator()
            while (entries.size > maxEntries || chars > maxChars) {
                chars -= iterator.next().chars
                iterator.remove()
            }
        }
        return rule?.withEnabled(e.enabled)
    }

    private fun RssSourceRule.withEnabled(enabled: Boolean) = if (this.enabled == enabled) this else copy(enabled = enabled)
}

private fun decodeRssRule(e: RssSourceEntity): RssSourceRule? {
    if (e.converterVersion < LegadoRssConverter.VERSION && e.sourceJson.isNotBlank()) {
        val fresh = runCatching { LegadoRssConverter.convert(e.sourceJson).converted.singleOrNull()?.rule }.getOrNull()
        if (fresh != null) return fresh
    }
    return runCatching { RssSourceRule.fromJson(e.json) }.getOrNull()
}
