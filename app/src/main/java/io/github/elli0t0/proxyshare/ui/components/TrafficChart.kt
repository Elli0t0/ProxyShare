package io.github.elli0t0.proxyshare.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elli0t0.proxyshare.R
import io.github.elli0t0.proxyshare.engine.SpeedSample
import io.github.elli0t0.proxyshare.ui.screens.formatBytes

/**
 * Smallest "nice" value >= [value], rounded in the DISPLAY unit
 * (KB/MB/GB) so the axis label never reads "4.8 MB/s": the old
 * decimal 1-2-5-10 ladder produced values like 5,000,000 bytes,
 * which formatBytes renders as 4.8 MB. 0 or negative input scales
 * to the 10 KB/s floor so the chart always has headroom.
 */
fun niceCeiling(value: Long): Long {
    val floor = 10L * 1024
    if (value <= floor) return floor
    var u = 1024L
    while (value / u >= 1000) u *= 1024
    val n = value.toDouble() / u
    val steps = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
    val s = steps.firstOrNull { it >= n } ?: return u * 1024
    return (s * u).toLong()
}

/** Chart ceiling with a speed limit folded in, so the limit line is always on scale. */
fun chartMaxWithLimit(peakBps: Long, limitBps: Long): Long =
    niceCeiling(maxOf(peakBps, limitBps))

/** Y position of a limit line in a plot of [heightPx] with axis max [maxBps]. */
fun limitLineY(limitBps: Long, maxBps: Long, heightPx: Float): Float {
    if (maxBps <= 0L) return heightPx
    return heightPx * (1f - limitBps.coerceIn(0L, maxBps).toFloat() / maxBps.toFloat())
}

private data class ChartPaths(
    val upLine: Path,
    val upFill: Path,
    val downLine: Path,
    val downFill: Path,
)

private fun smoothLinePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2f
        path.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
    }
    return path
}

private fun fillPath(line: Path, width: Float, height: Float): Path {
    return Path().apply {
        addPath(line)
        lineTo(width, height)
        lineTo(0f, height)
        close()
    }
}

private fun buildChartPaths(
    history: List<SpeedSample>,
    size: IntSize,
    max: Long,
    flatGapPx: Float,
): ChartPaths {
    val empty = ChartPaths(Path(), Path(), Path(), Path())
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    if (width <= 0f || height <= 0f || max <= 0L) return empty
    val idle = history.size < 2 || history.all { it.upBps == 0L && it.downBps == 0L }
    val upPoints: List<Offset>
    val downPoints: List<Offset>
    if (idle) {
        // Flat lines at the bottom, 2dp apart so both colors stay visible.
        upPoints = listOf(Offset(0f, height - flatGapPx), Offset(width, height - flatGapPx))
        downPoints = listOf(
            Offset(0f, height - flatGapPx * 3),
            Offset(width, height - flatGapPx * 3),
        )
    } else {
        val count = history.size
        fun x(index: Int): Float = index.toFloat() / (count - 1).toFloat() * width
        fun y(value: Long): Float =
            height - (value.coerceIn(0L, max).toFloat() / max.toFloat()) * height
        upPoints = history.mapIndexed { i, sample -> Offset(x(i), y(sample.upBps)) }
        downPoints = history.mapIndexed { i, sample -> Offset(x(i), y(sample.downBps)) }
    }
    val upLine = smoothLinePath(upPoints)
    val downLine = smoothLinePath(downPoints)
    return ChartPaths(
        upLine = upLine,
        upFill = fillPath(upLine, width, height),
        downLine = downLine,
        downFill = fillPath(downLine, width, height),
    )
}

/**
 * Live traffic chart: upload (tertiary) and download (primary) lines
 * over the rolling 2-minute history, oldest on the left. Redraws once
 * per second when a new sample lands; no fade/slide animations. The
 * Y axis eases: it grows instantly to a nice ceiling and shrinks
 * slowly (10% per tick) so the chart doesn't jump.
 */
@Composable
fun TrafficChart(
    history: List<SpeedSample>,
    current: SpeedSample,
    modifier: Modifier = Modifier,
    downloadLimitBps: Long = 0,
    uploadLimitBps: Long = 0,
) {
    val upColor = MaterialTheme.colorScheme.tertiary
    val downColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val strokePx = with(density) { 2.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    // One line: the download limit when set, else the upload limit.
    val limitBps = if (downloadLimitBps > 0) downloadLimitBps else uploadLimitBps
    val limited = downloadLimitBps > 0 || uploadLimitBps > 0

    val maxRaw = remember(history) {
        history.maxOfOrNull { maxOf(it.upBps, it.downBps) } ?: 0L
    }
    // Starts at the 10 KB/s floor so the first frame already has a scale.
    var smoothedMax by remember { mutableLongStateOf(10_240L) }
    LaunchedEffect(history, limitBps) {
        val target = chartMaxWithLimit(maxRaw, limitBps)
        smoothedMax = if (target >= smoothedMax) {
            target
        } else {
            maxOf(target, smoothedMax * 9 / 10)
        }
    }

    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val paths = remember(history, chartSize, smoothedMax, strokePx) {
        buildChartPaths(history, chartSize, smoothedMax, strokePx)
    }
    val upFillBrush = remember(upColor, chartSize) {
        Brush.verticalGradient(
            colors = listOf(upColor.copy(alpha = 0.15f), upColor.copy(alpha = 0f)),
            startY = 0f,
            endY = chartSize.height.toFloat(),
        )
    }
    val downFillBrush = remember(downColor, chartSize) {
        Brush.verticalGradient(
            colors = listOf(downColor.copy(alpha = 0.15f), downColor.copy(alpha = 0f)),
            startY = 0f,
            endY = chartSize.height.toFloat(),
        )
    }
    val maxLabel = remember(smoothedMax, labelColor) {
        textMeasurer.measure(
            text = AnnotatedString("${formatBytes(smoothedMax)}/s"),
            style = TextStyle(
                color = labelColor,
                fontSize = 11.sp,
                fontFeatureSettings = "tnum",
            ),
        )
    }
    val chartDesc = stringResource(
        R.string.traffic_chart_desc,
        formatBytes(current.upBps),
        formatBytes(current.downBps),
    )

    SectionCard(title = stringResource(R.string.traffic_title), modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LegendItem(
                color = upColor,
                label = stringResource(R.string.stat_upload),
                bps = current.upBps,
            )
            LegendItem(
                color = downColor,
                label = stringResource(R.string.stat_download),
                bps = current.downBps,
            )
            if (limited) {
                InfoChip(stringResource(R.string.limited_chip))
            }
        }
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .onSizeChanged { chartSize = it }
                    .semantics { contentDescription = chartDesc },
            ) {
                val width = size.width
                val height = size.height
                val gridStroke = 1.dp.toPx()
                for (fraction in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                    val y = height * fraction
                    drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = gridStroke)
                }
                drawPath(paths.upFill, brush = upFillBrush)
                drawPath(paths.downFill, brush = downFillBrush)
                val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(paths.upLine, color = upColor, style = stroke)
                drawPath(paths.downLine, color = downColor, style = stroke)
                if (limitBps > 0) {
                    drawLine(
                        color = if (downloadLimitBps > 0) downColor else upColor,
                        start = Offset(0f, limitLineY(limitBps, smoothedMax, height)),
                        end = Offset(width, limitLineY(limitBps, smoothedMax, height)),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                    )
                }
                // Max label sits just above the top grid line (y = 25%),
                // inside the plot's left padding so it stays with the line.
                drawText(
                    maxLabel,
                    topLeft = Offset(
                        4.dp.toPx(),
                        (height * 0.25f - maxLabel.size.height.toFloat() - 4.dp.toPx())
                            .coerceAtLeast(0f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, bps: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${formatBytes(bps)}/s",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
        )
    }
}
