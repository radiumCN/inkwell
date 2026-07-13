package com.radium.inkwell.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @Test
    fun `newer versions detected`() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.9"))
        assertTrue(UpdateChecker.isNewer("0.1.0.1", "0.1.0"))
    }

    @Test
    fun `equal or older versions ignored`() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `non numeric suffixes are tolerated`() {
        assertFalse(UpdateChecker.isNewer("0.1.0-beta", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("0.2.0-rc1", "0.1.0"))
    }
}
