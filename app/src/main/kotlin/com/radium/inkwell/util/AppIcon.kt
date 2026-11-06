package com.radium.inkwell.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.radium.inkwell.R

/**
 * 桌面图标的图形。四种都不画「物件」（墨水瓶、摊开的书、墨滴那些同类满屏都是的），各自从一个出发点来：
 * 书签替名字（桌面上只有英文名 Inkwell）、翻页画动作、叠页和应用里的毛玻璃同一套材质、段落是中文排版本身。
 * 图形的几何在 tools/gen_launcher_icons.py，资源由它生成。
 *
 * [key] 是资源名里的那一段（`ic_launcher_fg_<key>_<纸色>`），清单和单测按它对。
 */
enum class IconShape(val key: String, val label: String, val description: String) {
    MARK("mark", "书签", "小写 i：竖是一条书签丝带，点是一个句号"),
    TURN("turn", "翻页", "一页纸，右下角正翻过去"),
    GLASS("glass", "叠页", "磨砂玻璃的一页，叠在彩色的一页上"),
    PARA("para", "段落", "一段中文：首行缩进两格，句末一个句号"),
}

/**
 * 桌面图标的纸色：取阅读器的纸张（暖纸、净白、青竹、墨蓝、夜色），各配一个强调色 —— 选图标像选纸。
 *
 * [paper] 是纸的那一层（多数图形的背景），[accent] 是强调色那一层（翻页的背景 = 翻过去露出的下一页）。
 * 面板里的纸色色板就拿这两层画，不在界面代码里另写一遍颜色。
 */
enum class IconPaper(
    val key: String,
    val label: String,
    @DrawableRes val paper: Int,
    @DrawableRes val accent: Int,
) {
    PAPER("paper", "暖纸", R.drawable.ic_launcher_bg_paper, R.drawable.ic_launcher_bg_turn_paper),
    WHITE("white", "净白", R.drawable.ic_launcher_bg_white, R.drawable.ic_launcher_bg_turn_white),
    BAMBOO("bamboo", "青竹", R.drawable.ic_launcher_bg_bamboo, R.drawable.ic_launcher_bg_turn_bamboo),
    INKBLUE("inkblue", "墨蓝", R.drawable.ic_launcher_bg_inkblue, R.drawable.ic_launcher_bg_turn_inkblue),
    NIGHT("night", "夜色", R.drawable.ic_launcher_bg_night, R.drawable.ic_launcher_bg_turn_night),
}

/**
 * 可切换的桌面图标：[IconShape] × [IconPaper] 的每一种组合，一共 20 个。
 *
 * Android 不允许运行时改 `android:icon` —— 图标是装机时由 PackageManager 解析的静态资源。
 * 唯一的正路是给同一个 Activity 挂多个 `activity-alias`，每个 alias 带自己的图标，
 * 然后在运行时启用其中一个、禁用其余（见 [AppIconManager]）。所以图形和纸色虽然分开选，落到桌面上是一个组合一个 alias。
 *
 * [component] 是**清单里 alias 的名字，一旦发布就不能再改** —— 它被写进了用户桌面上的
 * 快捷方式里。改名等于把人家已经摆好的图标弄失效。枚举名和资源名可以随便重构，这个不行。
 * 0.5.2 之前是五款位图图标（钢笔与墨水、线条书、暗夜、书页、水墨），那五个 alias 名接着分给五个新组合：
 * 默认的 `.IconClassic` 给新的默认（书签 · 暖纸），没换过图标的人升级后桌面入口不变、图换成新的；
 * 换过的人也落在一个新图标上，而不是桌面图标凭空消失。清单与这张表对不对得上，由 AppIconManifestTest 钉着。
 */
enum class AppIcon(
    val shape: IconShape,
    val paper: IconPaper,
    val component: String,
    @DrawableRes val foreground: Int,
) {
    MARK_PAPER(IconShape.MARK, IconPaper.PAPER, ".IconClassic", R.drawable.ic_launcher_fg_mark_paper),
    MARK_WHITE(IconShape.MARK, IconPaper.WHITE, ".IconMarkWhite", R.drawable.ic_launcher_fg_mark_white),
    MARK_BAMBOO(IconShape.MARK, IconPaper.BAMBOO, ".IconMarkBamboo", R.drawable.ic_launcher_fg_mark_bamboo),
    MARK_INKBLUE(IconShape.MARK, IconPaper.INKBLUE, ".IconMarkInkBlue", R.drawable.ic_launcher_fg_mark_inkblue),
    MARK_NIGHT(IconShape.MARK, IconPaper.NIGHT, ".IconNight", R.drawable.ic_launcher_fg_mark_night),
    TURN_PAPER(IconShape.TURN, IconPaper.PAPER, ".IconBook", R.drawable.ic_launcher_fg_turn_paper),
    TURN_WHITE(IconShape.TURN, IconPaper.WHITE, ".IconTurnWhite", R.drawable.ic_launcher_fg_turn_white),
    TURN_BAMBOO(IconShape.TURN, IconPaper.BAMBOO, ".IconTurnBamboo", R.drawable.ic_launcher_fg_turn_bamboo),
    TURN_INKBLUE(IconShape.TURN, IconPaper.INKBLUE, ".IconTurnInkBlue", R.drawable.ic_launcher_fg_turn_inkblue),
    TURN_NIGHT(IconShape.TURN, IconPaper.NIGHT, ".IconTurnNight", R.drawable.ic_launcher_fg_turn_night),
    GLASS_PAPER(IconShape.GLASS, IconPaper.PAPER, ".IconInk", R.drawable.ic_launcher_fg_glass_paper),
    GLASS_WHITE(IconShape.GLASS, IconPaper.WHITE, ".IconGlassWhite", R.drawable.ic_launcher_fg_glass_white),
    GLASS_BAMBOO(IconShape.GLASS, IconPaper.BAMBOO, ".IconGlassBamboo", R.drawable.ic_launcher_fg_glass_bamboo),
    GLASS_INKBLUE(IconShape.GLASS, IconPaper.INKBLUE, ".IconGlassInkBlue", R.drawable.ic_launcher_fg_glass_inkblue),
    GLASS_NIGHT(IconShape.GLASS, IconPaper.NIGHT, ".IconGlassNight", R.drawable.ic_launcher_fg_glass_night),
    PARA_PAPER(IconShape.PARA, IconPaper.PAPER, ".IconLine", R.drawable.ic_launcher_fg_para_paper),
    PARA_WHITE(IconShape.PARA, IconPaper.WHITE, ".IconParaWhite", R.drawable.ic_launcher_fg_para_white),
    PARA_BAMBOO(IconShape.PARA, IconPaper.BAMBOO, ".IconParaBamboo", R.drawable.ic_launcher_fg_para_bamboo),
    PARA_INKBLUE(IconShape.PARA, IconPaper.INKBLUE, ".IconParaInkBlue", R.drawable.ic_launcher_fg_para_inkblue),
    PARA_NIGHT(IconShape.PARA, IconPaper.NIGHT, ".IconParaNight", R.drawable.ic_launcher_fg_para_night),
    ;

    /** 背景层：翻页的背景是翻过去露出的下一页（强调色），其余三种是纸 */
    @get:DrawableRes
    val background: Int
        get() = if (shape == IconShape.TURN) paper.accent else paper.paper

    /** 设置行上显示的名字：「书签 · 暖纸」 */
    val label: String
        get() = "${shape.label} · ${paper.label}"

    companion object {
        val DEFAULT = MARK_PAPER

        fun of(shape: IconShape, paper: IconPaper): AppIcon = entries.first { it.shape == shape && it.paper == paper }
    }
}

object AppIconManager {

    private fun componentOf(context: Context, icon: AppIcon) =
        ComponentName(context.packageName, context.packageName + icon.component)

    /**
     * 当前生效的图标。
     *
     * 以 PackageManager 的组件状态为准，而不是我们自己存的偏好 —— 用户可能是从旧版本升上来的
     * （那时还没有这个功能，组件状态全是 DEFAULT），或者刚清过数据。偏好和系统真实状态不一致时，
     * 系统说了算：桌面上摆着的是哪个，界面里就该勾哪个。
     */
    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        val enabled = AppIcon.entries.firstOrNull {
            pm.getComponentEnabledSetting(componentOf(context, it)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        // 谁都没被显式启用 = 还没切过，走清单里 android:enabled 的默认值
        return enabled ?: AppIcon.DEFAULT
    }

    /**
     * 切换图标。
     *
     * **一次批量提交**（Android 13 的 `setComponentEnabledSettings`，minSdk 正好是 33）：启用新的、禁用其余一起生效。
     * 从前是逐个调 `setComponentEnabledSetting`、先启用新的再禁用旧的 —— 顺序反过来的话，中间会有一瞬间
     * 一个带 LAUNCHER 的组件都没有，桌面图标当场消失，不少启动器（尤其国产 ROM）之后也不会自己刷回来。
     * 批量提交没有这个中间态；alias 从 5 个变成 20 个以后，逐个调还意味着一次切换给桌面连发十几次组件变更。
     *
     * DONT_KILL_APP：不加这个，改完组件状态系统会立刻杀掉进程，用户正读着的书就没了。
     */
    fun apply(context: Context, icon: AppIcon) {
        if (current(context) == icon) return
        context.packageManager.setComponentEnabledSettings(
            AppIcon.entries.map {
                PackageManager.ComponentEnabledSetting(
                    componentOf(context, it),
                    if (it == icon) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    PackageManager.DONT_KILL_APP,
                )
            },
        )
    }
}
