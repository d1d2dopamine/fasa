package dev.fasa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Everything here is drawn with Compose Canvas. No charting library:
// one less dependency that can break the build, and full control of the palette.

data class RingArc(
    val low: Double,
    val median: Double,
    val high: Double,
    val confidence: Double,
    val color: Color,
    val inset: Dp = 0.dp,
)

private fun norm(hour: Double): Double {
    var h = hour % 24.0
    if (h < 0) h += 24.0
    return h
}

// Midnight is at the top, the clock runs clockwise.
private fun angleOf(hour: Double): Float = (norm(hour) / 24.0 * 360.0 - 90.0).toFloat()

private fun sweepOf(from: Double, to: Double): Float {
    var s = norm(to) - norm(from)
    if (s < 0) s += 24.0
    return (s / 24.0 * 360.0).toFloat()
}

private fun argbOf(c: Color): Int = android.graphics.Color.argb(
    (c.alpha * 255).toInt(),
    (c.red * 255).toInt(),
    (c.green * 255).toInt(),
    (c.blue * 255).toInt(),
)

// A 24 hour ring.
// Each band is an arc. Angular length is the time range, stroke thickness is
// uncertainty: thick and faint means the model is guessing, thin and bright
// means it is sure. No percentage is printed. The shape carries that alone.
@Composable
fun DayRing(
    nowHour: Double,
    arcs: List<RingArc>,
    trackColor: Color,
    tickColor: Color,
    labelColor: Color,
    nowColor: Color,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = with(density) { 28.dp.toPx() }
            val side = min(size.width, size.height) - pad * 2f
            if (side <= 0f) return@Canvas
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            val full = Size(side, side)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = side / 2f

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = full,
                style = Stroke(width = with(density) { 2.dp.toPx() }),
            )

            for (h in 0 until 24) {
                val a = angleOf(h.toDouble()) * PI.toFloat() / 180f
                val major = h % 6 == 0
                val inner = radius - with(density) { (if (major) 12.dp else 6.dp).toPx() }
                drawLine(
                    color = if (major) labelColor else tickColor,
                    start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
                    end = Offset(cx + cos(a) * radius, cy + sin(a) * radius),
                    strokeWidth = with(density) { (if (major) 2.dp else 1.dp).toPx() },
                )
            }

            val paint = android.graphics.Paint()
            paint.isAntiAlias = true
            paint.color = argbOf(labelColor)
            paint.textSize = with(density) { 11.dp.toPx() }
            paint.textAlign = android.graphics.Paint.Align.CENTER
            for (h in listOf(0, 6, 12, 18)) {
                val a = angleOf(h.toDouble()) * PI.toFloat() / 180f
                val r = radius + with(density) { 15.dp.toPx() }
                val x = cx + cos(a) * r
                val y = cy + sin(a) * r + paint.textSize / 3f
                val label = if (h < 10) "0" + h else h.toString()
                drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
            }

            // Widest first, so the narrow confident arcs stay readable on top.
            arcs.sortedByDescending { sweepOf(it.low, it.high) }.forEach { arc ->
                val conf = arc.confidence.coerceIn(0.0, 1.0).toFloat()
                val thickDp = 30f - 21f * conf
                val thick = with(density) { thickDp.dp.toPx() }
                val ins = with(density) { arc.inset.toPx() }
                val sz = Size(side - ins * 2f, side - ins * 2f)
                val tl = Offset(topLeft.x + ins, topLeft.y + ins)

                drawArc(
                    color = arc.color.copy(alpha = 0.16f + 0.34f * conf),
                    startAngle = angleOf(arc.low),
                    sweepAngle = max(sweepOf(arc.low, arc.high), 1.5f),
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = thick),
                )

                drawArc(
                    color = arc.color,
                    startAngle = angleOf(arc.median) - 0.6f,
                    sweepAngle = 1.2f,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = thick),
                )
            }

            val na = angleOf(nowHour) * PI.toFloat() / 180f
            val nInner = radius - with(density) { 34.dp.toPx() }
            drawLine(
                color = nowColor,
                start = Offset(cx + cos(na) * nInner, cy + sin(na) * nInner),
                end = Offset(cx + cos(na) * radius, cy + sin(na) * radius),
                strokeWidth = with(density) { 2.dp.toPx() },
            )
            drawCircle(
                color = nowColor,
                radius = with(density) { 3.dp.toPx() },
                center = Offset(cx + cos(na) * radius, cy + sin(na) * radius),
            )
        }
        center()
    }
}
