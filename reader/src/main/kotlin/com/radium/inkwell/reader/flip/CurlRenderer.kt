package com.radium.inkwell.reader.flip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 经典仿真卷页算法（两段二阶贝塞尔折线 + 背面镜像 + 阴影），
 * 即 FBReader/Legado 系阅读器广泛使用的模拟翻页几何。
 *
 * 输入：整页位图（front=被卷起的页，under=露出的页）、触点、卷角。
 * 所有计算在 android.graphics 层完成，由 Compose drawIntoCanvas 调用。
 */
class CurlRenderer(private val width: Float, private val height: Float) {

    private val touch = PointF()
    private var cornerX = 0f
    private var cornerY = 0f

    private val bezierStart1 = PointF()
    private val bezierControl1 = PointF()
    private val bezierVertex1 = PointF()
    private val bezierEnd1 = PointF()
    private val bezierStart2 = PointF()
    private val bezierControl2 = PointF()
    private val bezierVertex2 = PointF()
    private val bezierEnd2 = PointF()

    private val pathCurl = Path()   // 卷起区域（含背面）
    private val pathUnder = Path()  // 露出下页的区域
    private val backMatrix = Matrix()

    /**
     * 纸背的正确画法：**先铺纸色，再把镜像正面以很低的透明度叠上去** —— 那是墨从纸的另一面
     * 淡淡透出来的样子。
     *
     * 从前用 `0.55·c + 110` 的 colorMatrix 直接洗白整张正面位图：`+110` 对 RGB 一视同仁地加，
     * 把暖纸（245,239,220）压成中性的（245,241,231），于是纸背发灰发冷 —— 跟正面的暖纸割裂。
     * 而且文字被洗成半吊子的灰，既不像透出的墨、也不像正面的黑。
     *
     * backPaint 只留一个可调的 alpha：铺过纸色之后，镜像文字用它压到 ~14% 的浓度。
     */
    private var paperColor = 0xFFF5EFDC.toInt()
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = BACK_INK_ALPHA }
    // 单位渐变（x: 0→1）+ localMatrix 复用，避免每帧 new Shader 的分配抖动。
    //
    // 阴影用**半透明黑但收窄了浓度**：纯黑 0x44 压在暖纸上会显出一道生硬的脏灰边，
    // 是"硬"的主要来源之一。降到 0x30 出头，让阴影更像纸的自遮挡而不是一条黑线。
    private val shadowMatrix = Matrix()
    private val foldShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            0x33000000, 0x00000000,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    private val backShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            0x26000000, 0x00000000,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    // 卷起弧面高光：中间亮、两侧透明，模拟光照到卷曲纸面。
    // 收到 0x22：纸不是塑料，中缝那道白太亮会显假
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            intArrayOf(0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    // 前页正面靠折痕的投影：纸张翘起在正面留下的阴影（近折痕深、向外渐隐）
    private val frontShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            0x00000000, 0x28000000,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }

    /**
     * @param touchX/touchY 当前触点（x 可越出屏幕左侧，用于把页完全卷走）
     * @param cornerBottom 卷角固定在右下(true)/右上(false)，手势开始时锁定
     */
    fun draw(
        canvas: Canvas,
        front: Bitmap,
        under: Bitmap,
        touchX: Float,
        touchY: Float,
        cornerBottom: Boolean,
        paperColor: Int,
    ) {
        this.paperColor = paperColor
        cornerX = width
        cornerY = if (cornerBottom) height else 0f
        touch.x = touchX.coerceIn(-width * 1.5f, width - 1.5f)
        // 触点太贴近卷角时几何会退化：midY 逼近 cornerY，控制点被算到屏幕外十几万像素，
        // 路径自交、Canvas 裁剪失效 —— 正面与镜像背面糊在一起。留出安全距离。
        val minGap = height * MIN_CORNER_GAP
        touch.y = if (cornerBottom) {
            touchY.coerceIn(1f, height - minGap)
        } else {
            touchY.coerceIn(minGap, height - 1f)
        }
        calcPoints()
        buildPaths()

        // 1. 前页未卷起部分：整画布挖掉卷起区域 + 折痕投影
        canvas.save()
        canvas.clipOutPath(pathCurl)
        canvas.drawBitmap(front, 0f, 0f, null)
        drawFrontShadow(canvas)
        canvas.restore()

        // 2. 下页露出区域 + 折线阴影
        canvas.save()
        canvas.clipPath(pathCurl)
        canvas.clipPath(pathUnder)
        canvas.drawBitmap(under, 0f, 0f, null)
        drawFoldShadow(canvas)
        canvas.restore()

        // 3. 前页背面（镜像 + 泛白）+ 折边阴影 + 弧面高光
        canvas.save()
        canvas.clipPath(pathCurl)
        canvas.clipOutPath(pathUnder)
        drawBack(canvas, front)
        drawBackShadow(canvas)
        drawBackHighlight(canvas)
        canvas.restore()
    }

    // ---------- 几何 ----------

    private fun calcPoints() {
        val midX = (touch.x + cornerX) / 2f
        val midY = (touch.y + cornerY) / 2f

        bezierControl1.x = midX - (cornerY - midY) * (cornerY - midY) / (cornerX - midX)
        bezierControl1.y = cornerY
        bezierControl2.x = cornerX
        val dy = cornerY - midY
        bezierControl2.y = if (abs(dy) < 0.1f) midY else midY - (cornerX - midX) * (cornerX - midX) / dy

        bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
        bezierStart1.y = cornerY

        // 限制卷角不撕裂：start1 越界时按比例回拉触点
        if (touch.x > 1f && touch.x < width - 1f) {
            if (bezierStart1.x < 0 || bezierStart1.x > width) {
                if (bezierStart1.x < 0) bezierStart1.x = width - bezierStart1.x
                val f1 = abs(cornerX - touch.x)
                val f2 = width * f1 / bezierStart1.x
                touch.x = abs(cornerX - f2)
                val f3 = abs(cornerX - touch.x) * abs(cornerY - touch.y) / f1
                touch.y = abs(cornerY - f3)

                val mx = (touch.x + cornerX) / 2f
                val my = (touch.y + cornerY) / 2f
                bezierControl1.x = mx - (cornerY - my) * (cornerY - my) / (cornerX - mx)
                bezierControl1.y = cornerY
                bezierControl2.x = cornerX
                val dy2 = cornerY - my
                bezierControl2.y = if (abs(dy2) < 0.1f) my else my - (cornerX - mx) * (cornerX - mx) / dy2
                bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
            }
        }
        bezierStart2.x = cornerX
        bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2f

        cross(touch, bezierControl1, bezierStart1, bezierStart2, bezierEnd1)
        cross(touch, bezierControl2, bezierStart1, bezierStart2, bezierEnd2)

        bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4f
        bezierVertex1.y = (2 * bezierControl1.y + bezierStart1.y + bezierEnd1.y) / 4f
        bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4f
        bezierVertex2.y = (2 * bezierControl2.y + bezierStart2.y + bezierEnd2.y) / 4f
    }

    /** 直线 (p1,p2) 与 (p3,p4) 交点 */
    private fun cross(p1: PointF, p2: PointF, p3: PointF, p4: PointF, out: PointF) {
        val a1 = (p2.y - p1.y) / (p2.x - p1.x + 1e-6f)
        val b1 = (p2.x * p1.y - p1.x * p2.y) / (p2.x - p1.x + 1e-6f)
        val a2 = (p4.y - p3.y) / (p4.x - p3.x + 1e-6f)
        val b2 = (p4.x * p3.y - p3.x * p4.y) / (p4.x - p3.x + 1e-6f)
        out.x = (b2 - b1) / (a1 - a2 + 1e-6f)
        out.y = a1 * out.x + b1
    }

    private fun buildPaths() {
        pathCurl.reset()
        pathCurl.moveTo(bezierStart1.x, bezierStart1.y)
        pathCurl.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y)
        pathCurl.lineTo(touch.x, touch.y)
        pathCurl.lineTo(bezierEnd2.x, bezierEnd2.y)
        pathCurl.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y)
        pathCurl.lineTo(cornerX, cornerY)
        pathCurl.close()

        // 背面四边形之外 = 露出下页的区域（在 pathCurl 内）
        pathUnder.reset()
        pathUnder.moveTo(bezierStart1.x, bezierStart1.y)
        pathUnder.lineTo(bezierVertex1.x, bezierVertex1.y)
        pathUnder.lineTo(bezierVertex2.x, bezierVertex2.y)
        pathUnder.lineTo(bezierStart2.x, bezierStart2.y)
        pathUnder.lineTo(cornerX, cornerY)
        pathUnder.close()
    }

    private fun drawBack(canvas: Canvas, front: Bitmap) {
        // 先把整个背面铺成纸色（此时 canvas 已被 clip 到背面区域）——
        // 这一步决定了纸背是暖纸还是灰白。铺满纸色，纸背就和正面同一种纸。
        canvas.drawColor(paperColor)

        // 再沿折线 (start1 → start2) 镜像前页，用很低的 alpha 叠上去 = 淡淡透出的墨
        val dx = bezierStart2.x - bezierStart1.x
        val dy = bezierStart2.y - bezierStart1.y
        val len2 = dx * dx + dy * dy + 1e-6f
        val cos2 = (dx * dx - dy * dy) / len2
        val sin2 = 2f * dx * dy / len2
        backMatrix.reset()
        backMatrix.setValues(
            floatArrayOf(
                cos2, sin2, 0f,
                sin2, -cos2, 0f,
                0f, 0f, 1f,
            )
        )
        backMatrix.preTranslate(-bezierStart1.x, -bezierStart1.y)
        backMatrix.postTranslate(bezierStart1.x, bezierStart1.y)
        canvas.drawBitmap(front, backMatrix, backPaint)
    }

    /** 背面靠折线处的暗部，增强纸张卷曲的立体感 */
    private fun drawBackShadow(canvas: Canvas) {
        drawShadowStrip(canvas, backShadowPaint, widthDivisor = 6f, maxWidth = 40f, towardLeft = false)
    }

    /** 前页正面靠折痕的投影，向页面主体渐隐（纸张翘起的正面阴影） */
    private fun drawFrontShadow(canvas: Canvas) {
        val fold = hypot((touch.x - cornerX).toDouble(), (touch.y - cornerY).toDouble()).toFloat()
        val w = (fold / 5f).coerceIn(0f, 30f)
        if (w < 2f) return
        val degree = Math.toDegrees(
            atan2((bezierControl1.x - cornerX).toDouble(), (bezierControl2.y - cornerY).toDouble())
        ).toFloat()
        // 渐变 x:0→1 映射为 near-far（近折痕深），画在折痕向页面主体（左）一侧
        shadowMatrix.reset()
        shadowMatrix.setScale(-w, 1f)
        shadowMatrix.postTranslate(bezierStart1.x, bezierStart1.y)
        frontShadowPaint.shader.setLocalMatrix(shadowMatrix)
        canvas.save()
        canvas.rotate(degree, bezierStart1.x, bezierStart1.y)
        canvas.drawRect(
            bezierStart1.x - w, bezierStart1.y - height * 2,
            bezierStart1.x, bezierStart1.y + height * 2, frontShadowPaint,
        )
        canvas.restore()
    }

    /** 卷起弧面中段高光；卷得越多高光越明显 */
    private fun drawBackHighlight(canvas: Canvas) {
        val fold = hypot((touch.x - cornerX).toDouble(), (touch.y - cornerY).toDouble()).toFloat()
        val hlWidth = (fold / 3f).coerceIn(0f, 90f)
        if (hlWidth < 2f) return
        val degree = Math.toDegrees(
            atan2((bezierControl1.x - cornerX).toDouble(), (bezierControl2.y - cornerY).toDouble())
        ).toFloat()
        shadowMatrix.reset()
        shadowMatrix.setScale(hlWidth, 1f)
        shadowMatrix.postTranslate(bezierStart1.x - hlWidth, bezierStart1.y)
        highlightPaint.shader.setLocalMatrix(shadowMatrix)
        canvas.save()
        canvas.rotate(degree, bezierStart1.x, bezierStart1.y)
        canvas.drawRect(
            bezierStart1.x - hlWidth, bezierStart1.y - height * 2,
            bezierStart1.x, bezierStart1.y + height * 2, highlightPaint,
        )
        canvas.restore()
    }

    private fun drawFoldShadow(canvas: Canvas) {
        drawShadowStrip(canvas, foldShadowPaint, widthDivisor = 4f, maxWidth = 60f, towardLeft = true)
    }

    /** 沿折线画渐变阴影条；shader 为单位渐变，用 localMatrix 定位（无每帧分配） */
    private fun drawShadowStrip(
        canvas: Canvas,
        paint: Paint,
        widthDivisor: Float,
        maxWidth: Float,
        towardLeft: Boolean,
    ) {
        val degree = Math.toDegrees(
            atan2((bezierControl1.x - cornerX).toDouble(), (bezierControl2.y - cornerY).toDouble())
        ).toFloat()
        val shadowWidth = (hypot(
            (touch.x - cornerX).toDouble(),
            (touch.y - cornerY).toDouble(),
        ) / widthDivisor).toFloat().coerceAtMost(maxWidth)
        if (shadowWidth < 1f) return

        shadowMatrix.reset()
        shadowMatrix.setScale(if (towardLeft) -shadowWidth else shadowWidth, 1f)
        shadowMatrix.postTranslate(bezierStart1.x, bezierStart1.y)
        paint.shader.setLocalMatrix(shadowMatrix)

        canvas.save()
        canvas.rotate(degree, bezierStart1.x, bezierStart1.y)
        if (towardLeft) {
            canvas.drawRect(
                bezierStart1.x - shadowWidth, bezierStart1.y - height * 2,
                bezierStart1.x, bezierStart1.y + height * 2, paint,
            )
        } else {
            canvas.drawRect(
                bezierStart1.x, bezierStart1.y - height * 2,
                bezierStart1.x + shadowWidth, bezierStart1.y + height * 2, paint,
            )
        }
        canvas.restore()
    }

    private companion object {
        /**
         * 触点与卷角之间必须留出的最小纵向距离（占页高的比例）。
         * 低于它，`bezierControl2.y = midY - (cornerX-midX)² / (cornerY-midY)` 的分母趋零，
         * 控制点飞到屏幕外十几万像素，路径自交、裁剪崩掉。
         */
        const val MIN_CORNER_GAP = 0.08f

        /** 纸背透出的墨的浓度。真纸从背面看，另一面的字只是极淡的一层，不是灰版正文 */
        const val BACK_INK_ALPHA = 36 // ~14%
    }
}
