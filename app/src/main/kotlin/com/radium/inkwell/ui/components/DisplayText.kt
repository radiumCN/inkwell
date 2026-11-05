package com.radium.inkwell.ui.components

/**
 * 换行、全角空格、连串空白压成一个空格。书源给的最新章字段常夹着换行（「04-18\n第13370章 …」），
 * 单行显示时只剩第一行，于是被截成「04-18…」。换源面板、书架「继续阅读」卡显示最新章时都过一遍。
 */
internal fun String.compactWhitespace(): String = trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("[\\s\\u3000]+")
