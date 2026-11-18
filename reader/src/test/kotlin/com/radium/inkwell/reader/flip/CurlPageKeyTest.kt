package com.radium.inkwell.reader.flip

import androidx.compose.ui.graphics.ImageBitmap
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.paginate.PageSpec
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CurlPageKeyTest {
    private fun image(): ImageBitmap = Proxy.newProxyInstance(
        ImageBitmap::class.java.classLoader, arrayOf(ImageBitmap::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            else -> error("Bitmap decoding is not needed for cache identity")
        }
    } as ImageBitmap

    @Test fun `decoded image invalidates placeholder snapshot even with identical pagination`() {
        val spec = PageSpec(0, 0, emptyList(), 0, 0)
        val measured = HashMap<Int, MeasuredParagraph>()
        val placeholder = CurlPageKey(spec, measured, emptyMap())
        val bitmap = image()
        val decoded = CurlPageKey(spec, measured, mapOf(1 to bitmap))
        assertNotEquals(placeholder, decoded)
        assertNotEquals(placeholder, CurlPageKey(spec, measured, emptyMap(), failedImages = setOf(1)))
        assertEquals(decoded, CurlPageKey(spec, measured, mapOf(1 to bitmap)))
        assertNotEquals(decoded, CurlPageKey(spec, measured, mapOf(1 to image())))
        assertNotEquals(decoded, CurlPageKey(spec, HashMap(measured), mapOf(1 to bitmap)))
    }
}
