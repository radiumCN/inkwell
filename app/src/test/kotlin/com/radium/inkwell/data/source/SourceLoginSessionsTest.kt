package com.radium.inkwell.data.source

import kotlin.test.Test
import kotlin.test.assertEquals

/** 启动时清理登录会话（[SourceLoginSessions.prune]）：哪些文件算没人用了 */
class SourceLoginSessionsTest {

    @Test
    fun `没有规则再用的会话文件连同写到一半的临时文件一起清，正用着的留着`() {
        val live = loginProfileName("现在的规则")
        val old = loginProfileName("更新前的规则")
        val names = listOf(live, "$live.bak", old, "$old.new")
        assertEquals(listOf(old, "$old.new"), staleSessionFiles(names, setOf("现在的规则")))
    }

    @Test
    fun `一个来源都没有了，全部清掉`() {
        val names = listOf(loginProfileName("a"), loginProfileName("b"))
        assertEquals(names, staleSessionFiles(names, emptySet()))
    }
}
