package com.radium.inkwell.reader.flip

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 仿真卷页。几何 + **阴影/背面的绘制结构逐行对齐 legado 的 SimulationPageDelegate**。
 *
 * 之前我用自写的 `drawShadowStrip` 近似阴影，位置/旋转/宽度都对不上 legado，怎么调颜色都不像。
 * 这一版改用与它相同的机制：`GradientDrawable + setBounds + rotate(mDegrees)`，三处阴影
 * （下页投影 / 前页折痕 / 背面折缝）各自的路径、旋转角、宽度公式都照它的来；背面镜像也换成
 * 它绕 control1 的反射矩阵。GPL→MIT：只复刻逻辑与数值，不拷贝其文件。
 *
 * 输入：整页位图（front=被卷起的页，under=露出的页）、触点、卷角、纸色。
 */
class CurlRenderer(private val width: Float, private val height: Float) {

    private val touch = PointF()
    private var cornerX = 0f
    private var cornerY = 0f
    private var isRtOrLb = false            // 右上 / 左下（我们只有右上或右下，右上时为 true）
    private var degrees = 0f                // 折缝旋转角（legado 的 mDegrees）
    private var touchToCornerDis = 0f
    private val maxLength = hypot(width.toDouble(), height.toDouble()).toFloat()

    private val bezierStart1 = PointF()
    private val bezierControl1 = PointF()
    private val bezierVertex1 = PointF()
    private val bezierEnd1 = PointF()
    private val bezierStart2 = PointF()
    private val bezierControl2 = PointF()
    private val bezierVertex2 = PointF()
    private val bezierEnd2 = PointF()

    private val pathFront = Path()  // 前页可见区（挖掉卷起区）
    private val pathUnder = Path()  // 露出下页的区
    private val pathBack = Path()   // 卷起页背面
    private val pathTmp = Path()    // 前页折痕投影用的临时四边形

    private val backMatrix = Matrix()
    private val backClipBounds = Rect()
    private val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private var paperColor = 0xFFF5EFDC.toInt()
    // 纸背：铺纸色 + 极淡镜像（墨从另一面透出），比 legado 的满强度镜像更干净、跟主题走
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = BACK_INK_ALPHA }

    // ---- 阴影：与 legado 相同的 GradientDrawable（颜色即它的数值）----
    // 下页投影：#111111 不透明 → 透明
    private val backShadowLR = grad(GradientDrawable.Orientation.LEFT_RIGHT, 0xFF111111.toInt(), 0x00111111)
    private val backShadowRL = grad(GradientDrawable.Orientation.RIGHT_LEFT, 0xFF111111.toInt(), 0x00111111)
    // 背面折缝：#333333 透明 → 69%
    private val folderShadowLR = grad(GradientDrawable.Orientation.LEFT_RIGHT, 0x00333333, 0xB0333333.toInt())
    private val folderShadowRL = grad(GradientDrawable.Orientation.RIGHT_LEFT, 0x00333333, 0xB0333333.toInt())
    // 前页折痕投影：#111111 50% → 透明（四个朝向）
    private val frontShadowVLR = grad(GradientDrawable.Orientation.LEFT_RIGHT, 0x81111111.toInt(), 0x00111111)
    private val frontShadowVRL = grad(GradientDrawable.Orientation.RIGHT_LEFT, 0x81111111.toInt(), 0x00111111)
    private val frontShadowHTB = grad(GradientDrawable.Orientation.TOP_BOTTOM, 0x81111111.toInt(), 0x00111111)
    private val frontShadowHBT = grad(GradientDrawable.Orientation.BOTTOM_TOP, 0x81111111.toInt(), 0x00111111)

    private fun grad(o: GradientDrawable.Orientation, vararg colors: Int) =
        GradientDrawable(o, colors).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }

    /**
     * @param cornerBottom 卷角固定在右下(true)/右上(false)，手势开始时锁定
     * @param front 卷起页的位图，用于前页可见区和背面淡墨。
     * @param drawLiveFront 尚无卷起页位图时，直接绘制前页可见区与背面镜像。
     * @param back 翻页进行中补齐的位图可先用于背面，正面保持起手时的绘制方式。
     */
    fun draw(
        canvas: Canvas,
        front: Bitmap?,
        under: Bitmap?,
        touchX: Float,
        touchY: Float,
        cornerBottom: Boolean,
        paperColor: Int,
        drawLiveUnder: (() -> Unit)? = null,
        drawLiveFront: (() -> Unit)? = null,
        back: Bitmap? = front,
    ) {
        if (touchX >= width) {
            // 后翻已到终点。直接画完整的上一页，避免几何钳制在右下角留下最后一片卷角。
            canvas.drawColor(paperColor)
            if (front != null) canvas.drawBitmap(front, 0f, 0f, null) else drawLiveFront?.invoke()
            return
        }
        this.paperColor = paperColor
        cornerX = width
        cornerY = if (cornerBottom) height else 0f
        // legado 的 mIsRtOrLb：右上角(cornerY==0 且 cornerX==width) 或 左下角。我们只有右上/右下 → 右上时 true
        isRtOrLb = cornerY == 0f
        touch.x = touchX.coerceIn(-width * 1.5f, width - 1.5f)
        touch.y = touchY.coerceIn(1f, height - 1f)
        calcPoints()
        buildPaths()
        touchToCornerDis = hypot((touch.x - cornerX).toDouble(), (touch.y - cornerY).toDouble()).toFloat()
        degrees = Math.toDegrees(
            atan2((bezierControl1.x - cornerX).toDouble(), (bezierControl2.y - cornerY).toDouble())
        ).toFloat()
        val shadowBottom = curlShadowBottom(width, height, bezierStart1.x, bezierStart1.y, degrees, maxLength)

        // 先铺满纸色。折页路径盖不住的缝、以及 clip 漏出来的像素，
        // 否则会透出底层或未清的缓冲，整屏发褐发紫。
        canvas.drawColor(paperColor)
        // legado onDraw 顺序：前页区 → 下页区+投影 → 前页折痕投影 → 背面。
        // 拖动帧也画三片阴影：关掉会变成「纸片平移」，不像在卷。跟手靠触点只在
        // drawBehind 里读，不靠少画阴影。
        drawCurrentPageArea(canvas, front, drawLiveFront)
        drawNextPageAreaAndShadow(canvas, under, drawLiveUnder, shadowBottom)
        drawCurrentPageShadow(canvas)
        drawCurrentBackArea(canvas, back, drawLiveFront, shadowBottom)
    }

    /** 前页可见区（挖掉卷起区，画前页） */
    private fun drawCurrentPageArea(canvas: Canvas, front: Bitmap?, drawLiveFront: (() -> Unit)?) {
        if (front == null && drawLiveFront == null) return // 纸色已经铺过
        canvas.save()
        canvas.clipOutPath(pathFront)
        if (front != null) canvas.drawBitmap(front, 0f, 0f, null) else drawLiveFront?.invoke()
        canvas.restore()
    }

    /** 下页露出区 + 翻起页投在下页上的阴影（legado 的 backShadow，宽 = 触角距/4） */
    private fun drawNextPageAreaAndShadow(
        canvas: Canvas,
        under: Bitmap?,
        drawLiveUnder: (() -> Unit)?,
        shadowBottom: Int,
    ) {
        canvas.save()
        canvas.clipPath(pathFront)
        // 底页铺满整个卷起区，背面随后覆盖。贝塞尔曲线与阴影的多边形在页缘
        // 可能不完全重合，底页也裁到阴影多边形时，慢拖会露出一块空白。
        if (under != null) canvas.drawBitmap(under, 0f, 0f, null) else drawLiveUnder?.invoke()
        canvas.clipPath(pathUnder)

        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
        val w = (touchToCornerDis / 4f)
        val (drawable, left, right) = if (isRtOrLb) {
            Triple(backShadowLR, bezierStart1.x.toInt(), (bezierStart1.x + w).toInt())
        } else {
            Triple(backShadowRL, (bezierStart1.x - w).toInt(), bezierStart1.x.toInt())
        }
        drawable.setBounds(left, bezierStart1.y.toInt(), right, shadowBottom)
        drawable.draw(canvas)
        canvas.restore()
    }

    /** 前页折痕投影：卷起的纸在前页留下的两片阴影（control1 侧竖向 + control2 侧横向） */
    private fun drawCurrentPageShadow(canvas: Canvas) {
        val angle = if (isRtOrLb) {
            Math.PI / 4 - atan2((bezierControl1.y - touch.y).toDouble(), (touch.x - bezierControl1.x).toDouble())
        } else {
            Math.PI / 4 - atan2((touch.y - bezierControl1.y).toDouble(), (touch.x - bezierControl1.x).toDouble())
        }
        val d1 = 25f * 1.414f * cos(angle).toFloat()
        val d2 = 25f * 1.414f * sin(angle).toFloat()
        val x = touch.x + d1
        val y = if (isRtOrLb) touch.y + d2 else touch.y - d2

        // ── control1 侧
        pathTmp.reset()
        pathTmp.moveTo(x, y)
        pathTmp.lineTo(touch.x, touch.y)
        pathTmp.lineTo(bezierControl1.x, bezierControl1.y)
        pathTmp.lineTo(bezierStart1.x, bezierStart1.y)
        pathTmp.close()
        canvas.save()
        canvas.clipOutPath(pathFront)
        canvas.clipPath(pathTmp)
        val (d1lx, d1rx, d1drawable) = if (isRtOrLb) {
            Triple(bezierControl1.x.toInt(), (bezierControl1.x + 25).toInt(), frontShadowVLR)
        } else {
            Triple((bezierControl1.x - 25).toInt(), (bezierControl1.x + 1).toInt(), frontShadowVRL)
        }
        var rot = Math.toDegrees(
            atan2((touch.x - bezierControl1.x).toDouble(), (bezierControl1.y - touch.y).toDouble())
        ).toFloat()
        canvas.rotate(rot, bezierControl1.x, bezierControl1.y)
        d1drawable.setBounds(d1lx, (bezierControl1.y - maxLength).toInt(), d1rx, bezierControl1.y.toInt())
        d1drawable.draw(canvas)
        canvas.restore()

        // ── control2 侧
        pathTmp.reset()
        pathTmp.moveTo(x, y)
        pathTmp.lineTo(touch.x, touch.y)
        pathTmp.lineTo(bezierControl2.x, bezierControl2.y)
        pathTmp.lineTo(bezierStart2.x, bezierStart2.y)
        pathTmp.close()
        canvas.save()
        canvas.clipOutPath(pathFront)
        canvas.clipPath(pathTmp)
        val (d2lx, d2rx, d2drawable) = if (isRtOrLb) {
            Triple(bezierControl2.y.toInt(), (bezierControl2.y + 25).toInt(), frontShadowHTB)
        } else {
            Triple((bezierControl2.y - 25).toInt(), (bezierControl2.y + 1).toInt(), frontShadowHBT)
        }
        rot = Math.toDegrees(
            atan2((bezierControl2.y - touch.y).toDouble(), (bezierControl2.x - touch.x).toDouble())
        ).toFloat()
        canvas.rotate(rot, bezierControl2.x, bezierControl2.y)
        val temp = if (bezierControl2.y < 0) (bezierControl2.y - height).toDouble() else bezierControl2.y.toDouble()
        val hmg = hypot(bezierControl2.x.toDouble(), temp).toFloat()
        if (hmg > maxLength) {
            d2drawable.setBounds(
                (bezierControl2.x - 25 - hmg).toInt(), d2lx,
                (bezierControl2.x + maxLength - hmg).toInt(), d2rx,
            )
        } else {
            d2drawable.setBounds((bezierControl2.x - maxLength).toInt(), d2lx, bezierControl2.x.toInt(), d2rx)
        }
        d2drawable.draw(canvas)
        canvas.restore()
    }

    /** 卷起页背面（铺纸色 + 淡墨镜像）+ 背面折缝阴影（legado 的 folderShadow，宽 = min 半距） */
    private fun drawCurrentBackArea(
        canvas: Canvas,
        back: Bitmap?,
        drawLiveFront: (() -> Unit)?,
        shadowBottom: Int,
    ) {
        val i = (bezierStart1.x + bezierControl1.x) / 2f
        val f1 = abs(i - bezierControl1.x)
        val i1 = (bezierStart2.y + bezierControl2.y) / 2f
        val f2 = abs(i1 - bezierControl2.y)
        val f3 = min(f1, f2)

        canvas.save()
        canvas.clipPath(pathFront)
        canvas.clipPath(pathBack)

        // 背面镜像：绕 control1 按 (f8,f9) 反射 —— legado 的矩阵
        val dis = hypot((cornerX - bezierControl1.x).toDouble(), (bezierControl2.y - cornerY).toDouble()).toFloat()
        val f8 = (cornerX - bezierControl1.x) / dis
        val f9 = (bezierControl2.y - cornerY) / dis
        matrixArray[0] = 1 - 2 * f9 * f9
        matrixArray[1] = 2 * f8 * f9
        matrixArray[3] = matrixArray[1]
        matrixArray[4] = 1 - 2 * f8 * f8
        backMatrix.reset()
        backMatrix.setValues(matrixArray)
        backMatrix.preTranslate(-bezierControl1.x, -bezierControl1.y)
        backMatrix.postTranslate(bezierControl1.x, bezierControl1.y)
        canvas.drawColor(paperColor)
        if (back != null) {
            canvas.drawBitmap(back, backMatrix, backPaint)
        } else if (drawLiveFront != null && canvas.getClipBounds(backClipBounds)) {
            canvas.saveLayerAlpha(
                backClipBounds.left.toFloat(), backClipBounds.top.toFloat(),
                backClipBounds.right.toFloat(), backClipBounds.bottom.toFloat(), BACK_INK_ALPHA,
            )
            canvas.concat(backMatrix)
            drawLiveFront()
            canvas.restore()
        }

        canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
        val (drawable, left, right) = if (isRtOrLb) {
            Triple(folderShadowLR, (bezierStart1.x - 1).toInt(), (bezierStart1.x + f3 + 1).toInt())
        } else {
            Triple(folderShadowRL, (bezierStart1.x - f3 - 1).toInt(), (bezierStart1.x + 1).toInt())
        }
        drawable.setBounds(left, bezierStart1.y.toInt(), right, shadowBottom)
        drawable.draw(canvas)
        canvas.restore()
    }

    // ---------- 几何（与 legado calcPoints 一致）----------

    private fun calcPoints() {
        val midX = (touch.x + cornerX) / 2f
        val midY = (touch.y + cornerY) / 2f

        bezierControl1.x = midX - (cornerY - midY) * (cornerY - midY) / (cornerX - midX)
        bezierControl1.y = cornerY
        bezierControl2.x = cornerX
        val dy = cornerY - midY
        bezierControl2.y =
            if (abs(dy) < 0.1f) midY - (cornerX - midX) * (cornerX - midX) / 0.1f
            else midY - (cornerX - midX) * (cornerX - midX) / dy

        bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
        bezierStart1.y = cornerY

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
                bezierControl2.y =
                    if (abs(dy2) < 0.1f) my - (cornerX - mx) * (cornerX - mx) / 0.1f
                    else my - (cornerX - mx) * (cornerX - mx) / dy2
                bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
            }
        }
        bezierStart2.x = cornerX
        bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2f

        // 这两个交点恰好在触点和各自控制点的中间。直接取中点，避免
        // 钉住触点 Y 后折线接近竖直时，斜率求交把卷曲边缘算歪。
        bezierEnd1.set((touch.x + bezierControl1.x) / 2f, (touch.y + bezierControl1.y) / 2f)
        bezierEnd2.set((touch.x + bezierControl2.x) / 2f, (touch.y + bezierControl2.y) / 2f)

        bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4f
        bezierVertex1.y = (2 * bezierControl1.y + bezierStart1.y + bezierEnd1.y) / 4f
        bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4f
        bezierVertex2.y = (2 * bezierControl2.y + bezierStart2.y + bezierEnd2.y) / 4f
    }

    private fun buildPaths() {
        // 前页可见区外缘（legado path0）
        pathFront.reset()
        pathFront.moveTo(bezierStart1.x, bezierStart1.y)
        pathFront.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y)
        pathFront.lineTo(touch.x, touch.y)
        pathFront.lineTo(bezierEnd2.x, bezierEnd2.y)
        pathFront.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y)
        pathFront.lineTo(cornerX, cornerY)
        pathFront.close()

        // 下页露出区（legado path1 in nextPageArea）
        pathUnder.reset()
        pathUnder.moveTo(bezierStart1.x, bezierStart1.y)
        pathUnder.lineTo(bezierVertex1.x, bezierVertex1.y)
        pathUnder.lineTo(bezierVertex2.x, bezierVertex2.y)
        pathUnder.lineTo(bezierStart2.x, bezierStart2.y)
        pathUnder.lineTo(cornerX, cornerY)
        pathUnder.close()

        // 背面区（legado path1 in currentBackArea）
        pathBack.reset()
        pathBack.moveTo(bezierVertex2.x, bezierVertex2.y)
        pathBack.lineTo(bezierVertex1.x, bezierVertex1.y)
        pathBack.lineTo(bezierEnd1.x, bezierEnd1.y)
        pathBack.lineTo(touch.x, touch.y)
        pathBack.lineTo(bezierEnd2.x, bezierEnd2.y)
        pathBack.close()
    }

    private companion object {
        /** 纸背透出的墨的浓度（legado 用满强度，我们保留主题选的浅色纸 + 淡墨，背面更干净） */
        const val BACK_INK_ALPHA = 13 // ~5%
    }
}

internal fun curlShadowBottom(
    width: Float,
    height: Float,
    pivotX: Float,
    pivotY: Float,
    degrees: Float,
    pageDiagonal: Float,
): Int {
    val radians = Math.toRadians(degrees.toDouble())
    val xProjection = -sin(radians)
    val yProjection = cos(radians)
    // 斜卷收尾时支点可能移出视口。逆旋转后，沿阴影长轴的最大投影取自视口四角之一。
    val farX = if (xProjection >= 0) width else 0f
    val farY = if (yProjection >= 0) height else 0f
    val farthestY = pivotY + (farX - pivotX) * xProjection + (farY - pivotY) * yProjection
    return maxOf((pivotY + pageDiagonal).toInt(), ceil(farthestY).toInt())
}
