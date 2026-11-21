package com.radium.inkwell.core.webdav

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class WebDavQuota(val usedBytes: Long, val totalBytes: Long)

internal fun parseWebDavQuota(xml: String): WebDavQuota? {
    if ("<!DOCTYPE" in xml) throw IOException("WebDAV 容量响应包含不支持的文档类型")
    val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("WebDAV 容量响应不允许外部实体") }
    val root = builder.parse(InputSource(StringReader(xml))).documentElement
    if (root.namespaceURI != "DAV:" || root.localName != "multistatus") return null
    val response = root.davChildren("response").singleOrNull() ?: return null
    val properties = response.davChildren("propstat")
        .filter { it.davChildren("status").singleOrNull()?.textContent?.trim()?.matches(OK_STATUS) == true }
        .flatMap { it.davChildren("prop") }

    fun value(name: String): Long? = properties.flatMap { it.davChildren(name) }
        .singleOrNull()?.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    val used = value("quota-used-bytes") ?: return null
    val available = value("quota-available-bytes") ?: return null
    if (available > Long.MAX_VALUE - used || used + available == 0L) return null
    return WebDavQuota(used, used + available)
}

private fun Element.davChildren(name: String): List<Element> = buildList {
    var child = firstChild
    while (child != null) {
        if (child is Element && child.namespaceURI == "DAV:" && child.localName == name) add(child)
        child = child.nextSibling
    }
}

private val OK_STATUS = Regex("HTTP/\\S+ +200(?: +.*)?")
