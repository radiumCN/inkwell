package com.radium.inkwell.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficialWebDavTest {

    @Test
    fun `空串回落到新 DAV 根`() {
        assertEquals(OfficialWebDav.DAV, OfficialWebDav.resolveDavUrl(""))
        assertEquals(OfficialWebDav.DAV, OfficialWebDav.resolveDavUrl("  "))
    }

    @Test
    fun `旧 api 主机改写成站点主机`() {
        assertEquals(OfficialWebDav.DAV, OfficialWebDav.resolveDavUrl(OfficialWebDav.LEGACY_DAV))
        assertEquals(
            OfficialWebDav.DAV,
            OfficialWebDav.resolveDavUrl("https://webdav-api.skylark.run/dav"),
        )
        assertTrue(OfficialWebDav.isLegacyDav(OfficialWebDav.LEGACY_DAV))
        assertTrue(OfficialWebDav.isLegacyDav("https://webdav-api.skylark.run/dav"))
        assertFalse(OfficialWebDav.isLegacyDav(""))
        assertFalse(OfficialWebDav.isLegacyDav(OfficialWebDav.DAV))
    }

    @Test
    fun `其它地址只补尾斜杠`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/",
            OfficialWebDav.resolveDavUrl("https://dav.jianguoyun.com/dav"),
        )
    }
}
