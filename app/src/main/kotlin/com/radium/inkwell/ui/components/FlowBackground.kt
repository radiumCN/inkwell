// 移植自 Miuix 示例 App 的背景特效（compose-miuix-ui/miuix，example/shared/src/commonMain/kotlin/component/effect：
// BgEffectBackground / BgEffectConfig / BgEffectModifier / BgEffectPainter / OS3BgFrag），
// Copyright 2026 compose-miuix-ui contributors，Apache License 2.0。
// 改动：只留 OS3 那套参数与着色器；着色器直接用 Android 的 RuntimeShader（minSdk 35，不必走 Miuix 的跨平台包装）；
// 不再按「是否双栏」而是按最短边判断平板；动画跟系统的「移除动画」开关走。
package com.radium.inkwell.ui.components

import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * HyperOS「关于手机」那种流动的彩色底：四团柔光按固定的 HyperOS 配色慢慢游走、换色，叠一层柔噪声。
 * 现在只有关于页的头部用它（[baseColor] 先铺一层，彩色画在上面）。
 *
 * 配色是 HyperOS 自己的那几组（浅色粉蓝紫、深色深蓝紫），**不跟 App 主题色走** —— 它是「系统关于页」的那个样子，
 * 换成主题色就成了随便一块渐变。深浅跟着 [baseColor] 判断。
 *
 * 动画：位置每帧挪一点（最多 60 帧 / 秒），颜色每隔几秒用一段很慢的弹簧换到下一组。这两样是这块背景自己的节奏
 * （照搬 Miuix 示例），不是界面动效，所以不读 AppTheme.motionScheme；系统关了动画时整块定格在第一帧。
 * 着色器要 API 33，我们 minSdk 35，不必做降级。
 *
 * @param bgModifier 挂在画背景的那一层上，不含 [content]：要拿这块背景做毛玻璃取样时在这里挂 layerBackdrop
 */
@Composable
fun FlowBackground(
    baseColor: Color,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = baseColor.luminance() < 0.5f
    val pad = LocalConfiguration.current.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
    val preset = remember(dark, pad) { FlowPresets.get(pad = pad, dark = dark) }
    val painter = remember { FlowPainter() }
    val playing = animationsEnabled()
    val colorStage = remember { Animatable(0f) }
    LaunchedEffect(playing, preset) {
        if (!playing) return@LaunchedEffect
        var target = floor(colorStage.value) + 1f
        while (isActive) {
            delay((preset.colorInterpPeriod * 500).toLong())
            colorStage.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 35f))
            target += 1f
        }
    }
    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize()
                .then(bgModifier)
                .then(FlowElement(painter, preset, baseColor, playing) { colorStage.value }),
        )
        content()
    }
}

private const val TABLET_MIN_WIDTH_DP = 600

private class FlowPreset(
    val points: FloatArray,
    val colors1: FloatArray,
    val colors2: FloatArray,
    val colors3: FloatArray,
    val colorInterpPeriod: Float,
    val lightOffset: Float,
    val saturateOffset: Float,
    val pointOffset: Float,
)

/** HyperOS 3 的四套参数（手机 / 平板 × 浅 / 深），原样照搬 */
private object FlowPresets {
    private val POINTS = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f)

    private val PHONE_LIGHT = FlowPreset(
        points = POINTS,
        colors1 = floatArrayOf(1.0f, 0.9f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
        colors2 = floatArrayOf(0.58f, 0.74f, 1.0f, 1.0f, 1.0f, 0.9f, 0.93f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
        colors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 1.0f, 0.6f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.0f, 1.0f, 0.56f, 0.69f, 1.0f, 1.0f),
        colorInterpPeriod = 5.0f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

    private val PHONE_DARK = FlowPreset(
        points = POINTS,
        colors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
        colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f),
        colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
        colorInterpPeriod = 8.0f,
        lightOffset = 0.0f,
        saturateOffset = 0.17f,
        pointOffset = 0.4f,
    )

    private val PAD_LIGHT = FlowPreset(
        points = POINTS,
        colors1 = floatArrayOf(0.99f, 0.77f, 0.86f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.72f, 0.74f, 1.0f, 1.0f, 0.98f, 0.76f, 0.8f, 1.0f),
        colors2 = floatArrayOf(0.66f, 0.75f, 1.0f, 1.0f, 1.0f, 0.86f, 0.91f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
        colors3 = floatArrayOf(0.97f, 0.79f, 0.85f, 1.0f, 0.65f, 0.68f, 0.98f, 1.0f, 0.66f, 0.77f, 1.0f, 1.0f, 0.72f, 0.73f, 0.98f, 1.0f),
        colorInterpPeriod = 7.0f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

    private val PAD_DARK = FlowPreset(
        points = POINTS,
        colors1 = floatArrayOf(0.66f, 0.26f, 0.62f, 0.4f, 0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.64f, 0.96f, 0.5f, 0.14f, 0.18f, 0.55f, 0.5f),
        colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.11f, 0.16f, 0.83f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f),
        colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.5f, 0.11f, 0.16f, 0.83f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.27f, 0.18f, 0.6f, 0.6f),
        colorInterpPeriod = 7.0f,
        lightOffset = 0.0f,
        saturateOffset = 0.0f,
        pointOffset = 0.2f,
    )

    fun get(pad: Boolean, dark: Boolean): FlowPreset = when {
        pad && dark -> PAD_DARK
        pad -> PAD_LIGHT
        dark -> PHONE_DARK
        else -> PHONE_LIGHT
    }
}

/** 着色器与它的 uniform。值没变就不重设（每帧只有时间和点位在变） */
private class FlowPainter {
    val shader = RuntimeShader(FLOW_FRAG).apply {
        setFloatUniform("uTranslateY", 0f)
        setFloatUniform("uNoiseScale", 1.5f)
        setFloatUniform("uPointRadiusMulti", 1f)
        setFloatUniform("uAlphaMulti", 1f)
    }

    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colors = FloatArray(16)
    private val pointsAnim = FloatArray(8)
    private var appliedPreset: FlowPreset? = null
    private var appliedStage = Float.NaN
    private var appliedTime = Float.NaN

    fun update(width: Float, height: Float, preset: FlowPreset, stage: Float, time: Float) {
        if (resolution[0] != width || resolution[1] != height) {
            resolution[0] = width
            resolution[1] = height
            shader.setFloatUniform("uResolution", resolution)
            updateBound(width, height)
            shader.setFloatUniform("uBound", bound)
        }
        if (appliedPreset !== preset) {
            shader.setFloatUniform("uPoints", preset.points)
            shader.setFloatUniform("uLightOffset", preset.lightOffset)
            shader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
        }
        if (appliedPreset !== preset || appliedStage != stage) {
            val base = stage.toInt()
            val fraction = stage - base
            val start = colorsFor(preset, base)
            val end = colorsFor(preset, base + 1)
            for (i in 0 until 16) colors[i] = start[i] + (end[i] - start[i]) * fraction
            shader.setFloatUniform("uColors", colors)
            appliedStage = stage
        }
        if (appliedPreset !== preset || appliedTime != time) {
            shader.setFloatUniform("uAnimTime", time)
            for (i in 0 until 4) {
                val x = preset.points[i * 3]
                val y = preset.points[i * 3 + 1]
                val animX = x + sin(time + y) * preset.pointOffset
                pointsAnim[i * 2] = animX
                pointsAnim[i * 2 + 1] = y + cos(time + animX) * preset.pointOffset
            }
            shader.setFloatUniform("uPointsAnim", pointsAnim)
            appliedTime = time
        }
        appliedPreset = preset
    }

    private fun colorsFor(preset: FlowPreset, index: Int): FloatArray = when (index.mod(4)) {
        1 -> preset.colors1
        3 -> preset.colors3
        else -> preset.colors2
    }

    /** 光团铺满整块；横着比竖着宽时按宽高比把纵向撑开，光团才是圆的而不是被压扁 */
    private fun updateBound(width: Float, height: Float) {
        bound[0] = 0f
        bound[2] = 1f
        if (width <= height) {
            bound[1] = 0f
            bound[3] = 1f
        } else {
            val aspect = width / height
            bound[1] = 0.5f - aspect / 2f
            bound[3] = aspect
        }
    }
}

private class FlowElement(
    val painter: FlowPainter,
    val preset: FlowPreset,
    val baseColor: Color,
    val playing: Boolean,
    val colorStage: () -> Float,
) : ModifierNodeElement<FlowNode>() {
    override fun create() = FlowNode(painter, preset, baseColor, playing, colorStage)

    override fun update(node: FlowNode) = node.update(painter, preset, baseColor, playing, colorStage)

    // colorStage 是每次重组新建的 lambda，不进比较：读的是同一个 Animatable
    override fun equals(other: Any?): Boolean = other is FlowElement && other.painter === painter &&
        other.preset === preset && other.baseColor == baseColor && other.playing == playing

    override fun hashCode(): Int = ((painter.hashCode() * 31 + preset.hashCode()) * 31 + baseColor.hashCode()) * 31 +
        playing.hashCode()
}

private class FlowNode(
    private var painter: FlowPainter,
    private var preset: FlowPreset,
    private var baseColor: Color,
    private var playing: Boolean,
    private var colorStage: () -> Float,
) : Modifier.Node(), DrawModifierNode {

    private var job: Job? = null
    private var time = 0f

    override fun onAttach() {
        if (playing) start()
    }

    override fun onDetach() {
        job?.cancel()
        job = null
    }

    fun update(painter: FlowPainter, preset: FlowPreset, baseColor: Color, playing: Boolean, colorStage: () -> Float) {
        this.painter = painter
        this.preset = preset
        this.baseColor = baseColor
        this.colorStage = colorStage
        if (this.playing != playing) {
            this.playing = playing
            if (playing) {
                start()
            } else {
                job?.cancel()
                job = null
            }
        }
        invalidateDraw()
    }

    /** 每帧推进时间、重画；不超过 60 帧 / 秒（高刷屏上背景不必跟着 120 帧跑） */
    private fun start() {
        job?.cancel()
        val offset = time
        job = coroutineScope.launch {
            val minDelta = 1_000_000_000L / 60L
            val origin = withFrameNanos { it }
            var last = origin
            while (isActive) {
                val now = withFrameNanos { it }
                if (now - last < minDelta) continue
                last = now
                time = offset + (now - origin) / 1_000_000_000f
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawRect(baseColor)
        painter.update(size.width, size.height, preset, colorStage(), time)
        drawRect(ShaderBrush(painter.shader))
        drawContent()
    }
}

/** HyperOS 3 背景着色器（AGSL），原样照搬 */
private const val FLOW_FRAG = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform float uTranslateY;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }

    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);

        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));

        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }

    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }

    vec4 main(vec2 fragCoord){
        vec2 vUv = fragCoord/uResolution;
        vUv.y = 1.0-vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0., uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;

        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));

        for (int i = 0; i < 4; i++){
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;

            float d = distance(uv, point);
            float pct = smoothstep(rad, 0., d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }

        float oppositeNoise = smoothstep(0., 1., noiseValue);
        color.rgb /= color.a;
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset;

        color.a = clamp(color.a, 0., 1.);
        color.a *= uAlphaMulti;

        color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""
