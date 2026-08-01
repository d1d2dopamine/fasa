package dev.fasa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

// Drift line. Where the sleep gate slides over the coming days if nothing
// intervenes. For a delayed phase this line climbs, and that climb is the
// whole diagnosis in one picture.
@Composable
fun DriftChart(
    hours: List<Double>,
    lineColor: Color,
    fillColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (hours.size < 2) return@Canvas
        val lo = (hours.min()) - 0.5
        val hi = (hours.max()) + 0.5
        val span = max(hi - lo, 1.0)
        val stepX = size.width / (hours.size - 1).toFloat()

        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        fun px(i: Int): Float = stepX * i
        fun py(i: Int): Float =
            size.height - (((hours[i] - lo) / span) * size.height).toFloat()

        val line = Path()
        line.moveTo(px(0), py(0))
        for (i in 1 until hours.size) line.lineTo(px(i), py(i))

        val area = Path()
        area.addPath(line)
        area.lineTo(size.width, size.height)
        area.lineTo(0f, size.height)
        area.close()

        drawPath(area, color = fillColor)
        drawPath(line, color = lineColor, style = Stroke(width = 4f))
        for (i in hours.indices) {
            drawCircle(lineColor, radius = 6f, center = Offset(px(i), py(i)))
        }
    }
}

// Histogram of one particle parameter. A wide spread means the model still
// holds many competing explanations for the same nights.
@Composable
fun Histogram(
    values: List<Double>,
    bins: Int,
    barColor: Color,
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = if (hi - lo < 1e-9) 1.0 else hi - lo
        val counts = IntArray(bins)
        values.forEach { v ->
            val idx = (((v - lo) / span) * (bins - 1)).toInt().coerceIn(0, bins - 1)
            counts[idx] = counts[idx] + 1
        }
        val peak = max(counts.max(), 1)
        val gap = size.width / bins * 0.18f
        val w = size.width / bins - gap
        drawLine(
            baseColor,
            Offset(0f, size.height),
            Offset(size.width, size.height),
            strokeWidth = 2f,
        )
        counts.forEachIndexed { i, c ->
            val h = size.height * (c.toFloat() / peak.toFloat())
            drawRect(
                color = barColor,
                topLeft = Offset(i * (w + gap) + gap / 2f, size.height - h),
                size = Size(w, h),
            )
        }
    }
}
