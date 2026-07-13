package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateArithmeticTest {

    private val vars = mapOf("keyword" to "剑", "page" to "3")

    @Test
    fun `page arithmetic expressions evaluate in templates`() {
        assertEquals("/list?start=40", expandTemplate("/list?start={{(page-1)*20}}", vars))
        assertEquals("/p/3.html", expandTemplate("/p/{{page}}.html", vars))
        assertEquals("/p/9", expandTemplate("/p/{{page*3}}", vars))
        assertEquals("/p/2", expandTemplate("/p/{{page - 1}}", vars))
    }

    @Test
    fun `unknown vars and invalid expressions become empty`() {
        assertEquals("/x/", expandTemplate("/x/{{bogus}}", vars))
        assertEquals("/x/", expandTemplate("/x/{{page*}}", vars))
        assertEquals("/x/", expandTemplate("/x/{{page/0}}", vars))
    }
}
