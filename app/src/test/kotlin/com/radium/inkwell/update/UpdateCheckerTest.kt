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
    fun `prerelease is older than same release`() {
        // semver：预发布 < 正式版
        assertFalse(UpdateChecker.isNewer("0.2.0-beta.1", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.2.0-beta.1"))
        assertTrue(UpdateChecker.isNewer("0.2.0-beta.1", "0.1.0"))
    }

    @Test
    fun `prerelease identifiers compare segment-wise`() {
        assertTrue(UpdateChecker.isNewer("0.2.0-beta.2", "0.2.0-beta.1"))
        assertTrue(UpdateChecker.isNewer("0.2.0-rc.1", "0.2.0-beta.2"))
        assertTrue(UpdateChecker.isNewer("0.2.0-beta.1", "0.2.0-beta"))
        assertFalse(UpdateChecker.isNewer("0.2.0-beta.1", "0.2.0-beta.1"))
    }
}
