package com.radium.inkwell.ui.components

import coil3.decode.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ShelfCoverDisplayTest {
    @Test fun cachedShelfThumbnailSkipsColoredPlaceholderAndFade() {
        assertEquals(CoverDisplay.NEUTRAL, coverDisplay(true, hasModel = true, source = null))
        for (source in listOf(DataSource.DISK, DataSource.MEMORY_CACHE, DataSource.MEMORY)) {
            assertEquals(CoverDisplay.DIRECT, coverDisplay(true, hasModel = true, source = source))
        }
        assertEquals(CoverDisplay.FADE, coverDisplay(true, hasModel = true, source = DataSource.NETWORK))
        assertEquals(CoverDisplay.TITLE, coverDisplay(true, hasModel = false, source = null))
    }

    @Test fun otherScreensKeepTheirTitlePlaceholderAndDiskFade() {
        assertEquals(CoverDisplay.TITLE, coverDisplay(false, hasModel = true, source = null))
        assertEquals(CoverDisplay.FADE, coverDisplay(false, hasModel = true, source = DataSource.DISK))
    }
}
