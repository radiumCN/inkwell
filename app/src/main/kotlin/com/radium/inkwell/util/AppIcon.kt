package com.radium.inkwell.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.radium.inkwell.R

/**
 * 可切换的桌面图标。
 *
 * Android 不允许运行时改 `android:icon` —— 图标是装机时由 PackageManager 解析的静态资源。
 * 唯一的正路是给同一个 Activity 挂多个 `activity-alias`，每个 alias 带自己的图标，
 * 然后在运行时启用其中一个、禁用其余（见 [AppIconManager]）。
 *
 * [component] 是**清单里 alias 的名字，一旦发布就不能再改** —— 它被写进了用户桌面上的
 * 快捷方式里。改名等于把人家已经摆好的图标弄失效。枚举名和资源名可以随便重构，这个不行。
 */
enum class AppIcon(
    val component: String,
    val label: String,
    val description: String,
    @DrawableRes val preview: Int,
) {
    CLASSIC(
        component = ".IconClassic",
        label = "钢笔与墨水",
        description = "版画质感的蘸水笔，默认",
        preview = R.mipmap.ic_icon_classic,
    ),
    LINE(
        component = ".IconLine",
        label = "线条书",
        description = "一笔勾出的书页与墨滴",
        preview = R.mipmap.ic_icon_line,
    ),
    NIGHT(
        component = ".IconNight",
        label = "暗夜",
        description = "深色底，墨水瓶透出暖光",
        preview = R.mipmap.ic_icon_night,
    ),
    BOOK(
        component = ".IconBook",
        label = "书页",
        description = "立体书页，一滴墨落在纸上",
        preview = R.mipmap.ic_icon_book,
    ),
    INK(
        component = ".IconInk",
        label = "水墨",
        description = "毛笔一气呵成的书形",
        preview = R.mipmap.ic_icon_ink,
    );

    companion object {
        val DEFAULT = CLASSIC
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
     * **先启用新的，再禁用旧的。** 顺序反过来的话，中间会有一瞬间一个带 LAUNCHER 的组件都没有 ——
     * 桌面图标当场消失，而且不少启动器（尤其国产 ROM）之后也不会自己刷回来，只能重启桌面。
     *
     * DONT_KILL_APP：不加这个，改完组件状态系统会立刻杀掉进程，用户正读着的书就没了。
     */
    fun apply(context: Context, icon: AppIcon) {
        if (current(context) == icon) return
        val pm = context.packageManager

        pm.setComponentEnabledSetting(
            componentOf(context, icon),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(
                componentOf(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
