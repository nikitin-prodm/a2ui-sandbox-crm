package dev.a2uicrm.agentic.render

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Analytics components (5.3): PipelineStrip (funnel) + TrendChart (single-series
 * bar/line on Canvas). Both are catalog-constrained: no pie/radial, no multi-series
 * (catalog.json components: PipelineStrip / TrendChart).
 */

@Composable
fun PipelineStripComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val stages = (props["stages"] as? List<*>) ?: emptyList<Any>()
    val selected = props["selectedStage"] as? String

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, raw ->
            val stage = raw as? Map<*, *> ?: return@forEachIndexed
            val name = A2uiRendererRegistry.resolveString(stage["name"], ctx)
            val count = A2uiRendererRegistry.resolveInt(stage["count"], ctx) ?: 0
            val amount = stage["amount"]?.let { A2uiRendererRegistry.resolveString(it, ctx) }
            val highlight = (stage["highlight"] as? Boolean) == true || name == selected

            val container = when {
                highlight -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(
                color = container,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable {
                    // rendererSemantics default: filterStage with the stage name.
                    ctx.dispatchAction(
                        node,
                        defaultEvent = mapOf(
                            "name" to "filterStage",
                            "context" to mapOf("stage" to name),
                        ),
                    )
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 5.4h: stage icon token (the LLM sets meaningful per-stage icons —
                    // "make different icons for each funnel stage").
                    A2uiRendererRegistry.iconVector(stage["icon"] as? String)?.let { iv ->
                        androidx.compose.material3.Icon(
                            imageVector = iv,
                            contentDescription = name,
                            tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = name,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = count.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    amount?.let {
                        Text(
                            text = it,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (index < stages.size - 1) {
                Text(
                    text = "›",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
fun TrendChartComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val kind = props["kind"] as? String ?: "bar"
    val points = (props["points"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList()
    val labels = (props["labels"] as? List<*>)?.map { A2uiRendererRegistry.resolveString(it, ctx) } ?: emptyList()
    val unit = props["unit"]?.let { A2uiRendererRegistry.resolveString(it, ctx) }
    val caption = props["caption"]?.let { A2uiRendererRegistry.resolveString(it, ctx) }
    // 5.5: value labels are ON by default (bug fix: "just bars with no numbers").
    val showValues = (props["showValues"] as? Boolean) ?: true

    // Safe rendering: the validator guarantees >= 3 points and equal label count,
    // but the renderer stays defensive (broken payloads never crash the app).
    if (points.isEmpty()) return

    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val valueStyle = TextStyle(fontSize = 9.sp, color = labelColor, fontWeight = FontWeight.Medium)

    /** 61.0 -> "61"; 68.5 -> "68.5"; 1234.0 -> "1.2k" (fits narrow bar slots). */
    fun compact(v: Double): String = when {
        kotlin.math.abs(v) >= 1000 -> "%.1fk".format(v / 1000)
        v % 1.0 == 0.0 -> "%d".format(v.toLong())
        else -> "%.1f".format(v)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            val maxValue = points.max()
            val minValue = points.min().coerceAtMost(0.0)
            val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
            val chartWidth = size.width
            // 14dp top reserve for the value labels, 18dp bottom for the labels row.
            val areaTop = 14.dp.toPx()
            val areaBottom = size.height - 18.dp.toPx() // space for labels
            val areaHeight = (areaBottom - areaTop).coerceAtLeast(1f)

            // horizontal grid lines
            for (i in 0..2) {
                val y = areaBottom - areaHeight * (i / 2f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(chartWidth, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            if (kind == "line") {
                val step = if (points.size > 1) chartWidth / (points.size - 1) else chartWidth
                val path = Path()
                points.forEachIndexed { i, v ->
                    val x = i * step
                    val y = areaBottom - areaHeight * ((v - minValue) / range).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
                points.forEachIndexed { i, v ->
                    val x = i * step
                    val y = areaBottom - areaHeight * ((v - minValue) / range).toFloat()
                    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
                    if (showValues) {
                        val text = compact(v)
                        val measured = textMeasurer.measure(text, valueStyle)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(x - measured.size.width / 2f, (y - 14.dp.toPx()).coerceAtLeast(0f)),
                        )
                    }
                }
            } else {
                val slot = chartWidth / points.size
                val barWidth = slot * 0.55f
                points.forEachIndexed { i, v ->
                    val h = areaHeight * ((v - minValue) / range).toFloat()
                    val barTop = areaBottom - h
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(i * slot + (slot - barWidth) / 2f, barTop),
                        size = androidx.compose.ui.geometry.Size(barWidth, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    )
                    if (showValues) {
                        val text = compact(v)
                        val measured = textMeasurer.measure(text, valueStyle)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                i * slot + (slot - measured.size.width) / 2f,
                                (barTop - 13.dp.toPx()).coerceAtLeast(0f),
                            ),
                        )
                    }
                }
            }
        }

        // labels row
        if (labels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        text = label.take(12),
                        fontSize = 9.sp,
                        color = labelColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (caption != null || unit != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOfNotNull(caption, unit?.let { "unit: $it" }).joinToString(" · "),
                fontSize = 11.sp,
                color = labelColor,
            )
        }
    }
}
