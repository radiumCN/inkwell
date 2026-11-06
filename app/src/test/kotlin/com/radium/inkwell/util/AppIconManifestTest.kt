package com.radium.inkwell.util

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 桌面图标的对应表（[AppIcon]）和清单里的 activity-alias 对得上。
 *
 * 对不上的后果全在桌面上，编译和别的单测都看不出来：
 * - 表里有、清单里没有 —— 换到那一款时 setComponentEnabledSettings 直接抛异常；
 * - 清单里有、表里没有 —— 那个 alias 永远切不掉，桌面上两个图标；
 * - 默认启用的不是 [AppIcon.DEFAULT] —— 没换过图标的人，设置里勾的和桌面上摆的不是同一个；
 * - 发布过的 alias 名少了一个 —— 换过那一款的人升级后，桌面图标凭空消失。
 */
class AppIconManifestTest {

    private val ns = "http://schemas.android.com/apk/res/android"

    private val manifest = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private val aliases: List<Element> = manifest.getElementsByTagName("activity-alias")
        .let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Element.attr(name: String): String = getAttributeNS(ns, name)

    private fun mipmapOf(icon: AppIcon) = "ic_launcher_${icon.shape.key}_${icon.paper.key}"

    @Test
    fun `每个图标都有自己的 alias，清单里没有多出来的`() {
        val names = aliases.map { it.attr("name") }
        assertEquals(names.size, names.toSet().size, "清单里有重名的 alias：$names")
        assertEquals(AppIcon.entries.map { it.component }.toSet(), names.toSet())
    }

    @Test
    fun `默认启用的只有一个，就是 DEFAULT`() {
        val enabled = aliases.filter { it.attr("enabled") == "true" }.map { it.attr("name") }
        assertEquals(listOf(AppIcon.DEFAULT.component), enabled)
        // application 的 android:icon 出现在「设置 → 应用」「最近任务」，不随 alias 切换：放默认款
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@mipmap/${mipmapOf(AppIcon.DEFAULT)}", app.attr("icon"))
    }

    @Test
    fun `发布过的 alias 名一个都没少`() {
        // 0.5.2 之前那五款图标的 alias 名，已经写进了用户桌面的快捷方式
        val published = listOf(".IconClassic", ".IconLine", ".IconNight", ".IconBook", ".IconInk")
        val names = aliases.map { it.attr("name") }.toSet()
        published.forEach { assertTrue(it in names, "发布过的 alias $it 不见了") }
        // 没换过图标的人启用着的就是 .IconClassic：它得一直是默认，升级后桌面入口才不变
        assertEquals(".IconClassic", AppIcon.DEFAULT.component)
    }

    @Test
    fun `每个 alias 用的是它那个组合的图标，三层资源都在`() {
        val byName = aliases.associateBy { it.attr("name") }
        AppIcon.entries.forEach { icon ->
            val mipmap = mipmapOf(icon)
            assertEquals("@mipmap/$mipmap", byName.getValue(icon.component).attr("icon"), "${icon.name} 的图标对不上")
            val xml = File("src/main/res/mipmap-anydpi-v26/$mipmap.xml").readText()
            val layers = Regex("@drawable/(\\w+)").findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(3, layers.size, "$mipmap 应有背景、前景、单色三层：$layers")
            layers.forEach { assertTrue(File("src/main/res/drawable/$it.xml").exists(), "$mipmap 引用的 $it 不存在") }
        }
    }

    @Test
    fun `图形和纸色的每种组合都有，且只有一个`() {
        IconShape.entries.forEach { shape ->
            IconPaper.entries.forEach { paper ->
                assertEquals(1, AppIcon.entries.count { it.shape == shape && it.paper == paper }, "$shape × $paper")
            }
        }
    }
}
