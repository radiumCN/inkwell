package com.radium.inkwell.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.api.ReaderBookSource
import com.radium.inkwell.reader.paginate.ImageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读页的正文插图（阅读设置「正文显示图片」开着时才用得上）。三件事：
 *
 * - **排版前量尺寸**（[measure]）：分页要先知道图多高才知道这一页装不装得下。不量就排，只能先按固定比例留框、
 *   图来了再重排 —— 读着读着字整页往下跳。所以带图的章先把图取下来量好再排；量不出来的记成没取到
 *   （[ImageInfo.Failed]），分页排一条「点按重试」。
 * - **画的时候解码**（[bitmap] / [decode]）：按页上的框解一张正好大小的位图，放进按字节数算的 LRU。
 *   位图一律软件位图（ARGB_8888）：仿真翻页要把整页画进软件画布（renderPageBitmap），硬件位图画不进去，会崩。
 * - **看大图**（[decodeForViewer]）：长按时另解一张大一些的，给双指放大留余量，不进 LRU。
 *
 * 字节从 [ReaderBookSource.loadImage] 来：本地书读包里的资源，网上的书走正文缓存旁边的图片缓存、没有再下载。
 * 尺寸表只记在这个阅读会话里（几十个数，不占地方），重进书再量一遍 —— 本地书和缓存过的图量一次几毫秒。
 */
internal class ReaderImages {

    /** resourceId → 量好的尺寸 / 没取到。没量过的不在表里 */
    private val sizes = ConcurrentHashMap<String, ImageInfo>()

    /** 同时取几张：网上的书一章十几张图时别一起打过去，限流的站会把后面的全拒了 */
    private val permits = Semaphore(MEASURE_PARALLELISM)

    /** 解好的位图，按字节数算容量。不手动 recycle：页对象可能还拿着它，交给 GC */
    private val bitmaps = object : LruCache<String, ImageBitmap>(bitmapBudgetBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** 这一张量好了没有、多大；没量过返回 null（分页当它不排） */
    fun info(resourceId: String): ImageInfo? = sizes[resourceId]

    /**
     * 量这一章里没量过的图。慢网下整章最多等 [CHAPTER_BUDGET_MS]：到点还没回来的记成没取到，先把字排出来，
     * 那几张由读者点「重试」再取 —— 不然一章二十张图的漫画式插图章能让人对着转圈等一分钟。
     */
    suspend fun measure(src: ReaderBookSource, content: ChapterContent) {
        val ids = content.elements.mapNotNullTo(LinkedHashSet()) { (it as? ContentElement.Image)?.resourceId }
            .filter { !sizes.containsKey(it) }
        if (ids.isEmpty()) return
        val finished = withTimeoutOrNull(CHAPTER_BUDGET_MS) {
            coroutineScope { ids.map { id -> async { measureOne(src, id) } }.awaitAll() }
        }
        if (finished == null) ids.forEach { sizes.putIfAbsent(it, ImageInfo.Failed) }
    }

    /** 点「重试」：忘掉失败记录、再取一次；返回这次取到没有 */
    suspend fun retry(src: ReaderBookSource, resourceId: String): Boolean {
        sizes.remove(resourceId)
        return measureOne(src, resourceId) is ImageInfo.Sized
    }

    /** 新的阅读会话（进书、换源）：上次没取到的不再记着，让它们有机会重新取 */
    fun forgetFailures() {
        sizes.entries.removeAll { it.value == ImageInfo.Failed }
        undecodable.clear()
    }

    private suspend fun measureOne(src: ReaderBookSource, id: String): ImageInfo {
        sizes[id]?.let { return it }
        val bytes = permits.withPermit { withTimeoutOrNull(IMAGE_TIMEOUT_MS) { loadBytes(src, id) } }
        val info = bytes?.let { withContext(Dispatchers.Default) { boundsOf(it) } } ?: ImageInfo.Failed
        sizes[id] = info
        return info
    }

    /** 已经解好、正好是这个大小的位图；没有返回 null（调用方再去 [decode]） */
    fun bitmap(resourceId: String, width: Int, height: Int): ImageBitmap? = bitmaps.get(key(resourceId, width, height))

    /**
     * 量得出尺寸、却解不出整张的图（文件后半截坏了、解码时内存不够）。记下来别再排队：
     * 不记的话「解不出 → 刷新页面 → 页面又要这张 → 再解」会一直空转。框里留着那块淡底
     */
    private val undecodable = ConcurrentHashMap.newKeySet<String>()

    fun isUndecodable(resourceId: String, width: Int, height: Int): Boolean =
        key(resourceId, width, height) in undecodable

    /** 按页上的框解一张：先按 2 的幂降采样到不小于框，再缩到正好框的大小（框的比例就是原图的比例） */
    suspend fun decode(src: ReaderBookSource, resourceId: String, width: Int, height: Int): ImageBitmap? {
        val key = key(resourceId, width, height)
        bitmaps.get(key)?.let { return it }
        val bytes = loadBytes(src, resourceId)
        val bmp = bytes?.let { withContext(Dispatchers.Default) { decodeSampled(it, width, height, exact = true) } }
        if (bmp == null) {
            undecodable += key
            return null
        }
        return bmp.asImageBitmap().also { bitmaps.put(key, it) }
    }

    /** 看大图：解到不超过 [maxWidth]×[maxHeight]（调用方给屏幕的两倍，放大了也不糊），不进缓存 */
    suspend fun decodeForViewer(src: ReaderBookSource, resourceId: String, maxWidth: Int, maxHeight: Int): ImageBitmap? {
        val bytes = loadBytes(src, resourceId) ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = boundsOf(bytes) as? ImageInfo.Sized ?: return@withContext null
            val scale = minOf(1f, maxWidth.toFloat() / bounds.width, maxHeight.toFloat() / bounds.height)
            val w = (bounds.width * scale).toInt().coerceAtLeast(1)
            val h = (bounds.height * scale).toInt().coerceAtLeast(1)
            decodeSampled(bytes, w, h, exact = false)?.asImageBitmap()
        }
    }

    private suspend fun loadBytes(src: ReaderBookSource, id: String): ByteArray? = try {
        src.loadImage(id)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 包里的资源读坏了、网络书的缓存文件被清到一半：当没取到
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun key(id: String, w: Int, h: Int) = "$id\u0000$w\u0000$h"

    private companion object {
        const val MEASURE_PARALLELISM = 3

        /** 一张图最多等这么久（网上的书）。本地书几毫秒就回来，用不着它 */
        const val IMAGE_TIMEOUT_MS = 15_000L

        /** 一章的图最多一共等这么久，到点没回来的先记成没取到，见 [measure] */
        const val CHAPTER_BUDGET_MS = 20_000L

        /** 位图缓存：可用堆的八分之一，最多 48MB —— 够前后三页的图，仿真翻页还要另占两张整页 */
        fun bitmapBudgetBytes(): Int =
            minOf(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024).toInt()

        fun boundsOf(bytes: ByteArray): ImageInfo {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            return if (o.outWidth > 0 && o.outHeight > 0) ImageInfo.Sized(o.outWidth, o.outHeight) else ImageInfo.Failed
        }

        /**
         * 降采样解码：inSampleSize 取「解出来仍不小于目标」的最大 2 的幂，大图不会整张解进内存。
         * [exact] 时再缩到正好目标大小（页上的框）；看大图时保留降采样后的大小。
         */
        fun decodeSampled(bytes: ByteArray, width: Int, height: Int, exact: Boolean): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                // 软件位图：硬件位图画不进仿真翻页的软件画布（见类注释）
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            when {
                decoded == null -> null
                !exact || (decoded.width == width && decoded.height == height) -> decoded
                else -> Bitmap.createScaledBitmap(decoded, width, height, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
