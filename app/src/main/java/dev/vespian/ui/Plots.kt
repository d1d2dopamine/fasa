package dev.vespian.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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

// History of promises. One column per measured night: the vertical bar is the
// onset band the model published that evening, the dot is when sleep actually
// started. A dot inside its bar is a hit, outside is a miss. Nights recorded
// before the model began writing its forecasts down have no bar, only a dot,
// and they are drawn dim so they cannot be mistaken for a score.
@Composable
fun NightsChart(
    actual: List<Double>,
    predLow: List<Double?>,
    predHigh: List<Double?>,
    hitColor: Color,
    missColor: Color,
    plainColor: Color,
    bandColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (actual.isEmpty()) return@Canvas

        val values = mutableListOf<Double>()
        values.addAll(actual)
        predLow.filterNotNull().forEach { values.add(it) }
        predHigh.filterNotNull().forEach { values.add(it) }
        val lo = values.min() - 0.5
        val hi = values.max() + 0.5
        val span = max(hi - lo, 1.0)

        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val slot = size.width / actual.size
        // A thin capsule, not a fat block. Fourteen of these sit side by side,
        // so anything wider turns the chart into a wall.
        val barWidth = (slot * 0.18f).coerceIn(3f, 7f)
        // The dot has to read as a point on the bar, not as another bar. Small
        // enough to sit inside the capsule, large enough to see.
        val dotRadius = (barWidth * 0.62f).coerceIn(2.5f, 4.5f)

        fun py(v: Double): Float =
            size.height - (((v - lo) / span) * size.height).toFloat()

        actual.indices.forEach { i ->
            val cx = slot * i + slot / 2f
            val low = predLow.getOrNull(i)
            val high = predHigh.getOrNull(i)
            val point = actual[i]

            if (low != null && high != null) {
                val top = py(high)
                val bottom = py(low)
                val height = max(bottom - top, barWidth)
                drawRoundRect(
                    color = bandColor,
                    topLeft = Offset(cx - barWidth / 2f, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }

            val color = when {
                low == null || high == null -> plainColor
                point >= low && point <= high -> hitColor
                else -> missColor
            }
            val centre = Offset(cx, py(point))
            // A hairline ring in the grid colour keeps the dot legible where it
            // overlaps its own band.
            drawCircle(gridColor, radius = dotRadius + 1.5f, center = centre)
            drawCircle(color, radius = dotRadius, center = centre)
        }
    }
}

/**
 * Heart rate over a chosen period.
 *
 * The period is cut into equal slices and every slice becomes one point: the
 * average of the readings inside it, with the lowest to highest of that slice
 * drawn as a band behind the line. A day and three months therefore look the
 * same amount of busy, and a slice the band measured nothing in is a gap in the
 * line rather than a line dropped to zero.
 */
@Composable
fun HrChart(
    avg: List<Double?>,
    low: List<Double?>,
    high: List<Double?>,
    lineColor: Color,
    bandColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val present = avg.indices.filter { avg[it] != null }
        if (present.isEmpty()) return@Canvas

        val values = mutableListOf<Double>()
        avg.filterNotNull().forEach { values.add(it) }
        low.filterNotNull().forEach { values.add(it) }
        high.filterNotNull().forEach { values.add(it) }
        val lo = values.min() - 2.0
        val hi = values.max() + 2.0
        val span = max(hi - lo, 1.0)

        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val slots = max(avg.size, 1)
        fun px(i: Int): Float =
            if (slots == 1) size.width / 2f else size.width * i / (slots - 1).toFloat()

        fun py(v: Double): Float =
            size.height - (((v - lo) / span) * size.height).toFloat()

        // The spread first, so the line sits on top of its own band.
        present.forEach { i ->
            val l = low.getOrNull(i)
            val h = high.getOrNull(i)
            if (l == null || h == null) return@forEach
            val top = py(h)
            val bottom = py(l)
            val w = max(size.width / slots.toFloat() * 0.7f, 2f)
            drawRect(
                color = bandColor,
                topLeft = Offset(px(i) - w / 2f, top),
                size = Size(w, max(bottom - top, 1.5f)),
            )
        }

        // One stroke per unbroken run of measured slices. Joining across a gap
        // would invent a heart rate for hours the band was off the wrist.
        var run = Path()
        var started = false
        var last = -2
        present.forEach { i ->
            val v = avg[i] ?: return@forEach
            if (!started || i != last + 1) {
                if (started) drawPath(run, color = lineColor, style = Stroke(width = 3.5f))
                run = Path()
                run.moveTo(px(i), py(v))
                started = true
            } else {
                run.lineTo(px(i), py(v))
            }
            last = i
        }
        if (started) drawPath(run, color = lineColor, style = Stroke(width = 3.5f))

        // A single measured slice draws no line at all, so mark it as a point.
        if (present.size == 1) {
            val i = present.first()
            avg[i]?.let { drawCircle(lineColor, radius = 5f, center = Offset(px(i), py(it))) }
        }
    }
}

// Light over one day, one bar per hour. The scale is compressed the same way
// the model compresses it, so a bar is drawn by how much that hour could move
// the clock, not by the raw number of lux.
@Composable
fun LightDay(
    doses: List<Double>,
    barColor: Color,
    dimColor: Color,
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (doses.isEmpty()) return@Canvas
        val gap = size.width / doses.size * 0.22f
        val w = size.width / doses.size - gap
        drawLine(
            baseColor,
            Offset(0f, size.height),
            Offset(size.width, size.height),
            strokeWidth = 2f,
        )
        doses.forEachIndexed { i, d ->
            val v = d.coerceIn(0.0, 1.0)
            val h = max((size.height * v).toFloat(), 2f)
            drawRect(
                color = if (v < 0.02) dimColor else barColor,
                topLeft = Offset(i * (w + gap) + gap / 2f, size.height - h),
                size = Size(w, h),
            )
        }
    }
}
