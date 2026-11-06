package com.personal.watermarkcamera.domain

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 纯绘制逻辑：只依赖 android.graphics，不依赖任何 UI 框架。
 * 预览层和拍照后的离屏合成都调用同一个方法，保证「所见即所得」。
 *
 * 两套布局：
 * - [WatermarkStyle.HERO]：大号时间 + 橙色竖线 + 日期/星期小字 + 地点行 + 经纬度行 + 自定义文案条；
 * - [WatermarkStyle.COMPACT]：等字号左对齐多行，无竖线、无底条。
 * 两者都以「文本块中心点」定位，拖动只改归一化坐标。
 */
class WatermarkRenderer {

    /** 最近一次绘制的水印块尺寸（像素，针对传入的图像宽高）。拖动时用它做边界钳制。 */
    @Volatile
    var lastBlockWidth: Float = 0f
        private set

    @Volatile
    var lastBlockHeight: Float = 0f
        private set

    /** 最近一次绘制时水印块的左上角（画面坐标系，像素）。用于预览层做拖动命中判定。 */
    @Volatile
    var lastBlockLeft: Float = 0f
        private set

    @Volatile
    var lastBlockTop: Float = 0f
        private set

    fun render(
        canvas: Canvas,
        imageWidth: Int,
        imageHeight: Int,
        spec: WatermarkSpec,
        /** 仅预览层用：拖动时给水印块加描边，不改成片外观。 */
        highlight: Boolean = false
    ) {
        if (imageWidth <= 0 || imageHeight <= 0) return
        if (spec.isEmpty) {
            // 清空水印后旧 bbox 不能继续参与命中，否则空白处仍能拖
            lastBlockWidth = 0f
            lastBlockHeight = 0f
            lastBlockLeft = 0f
            lastBlockTop = 0f
            return
        }
        when (spec.style) {
            WatermarkStyle.HERO -> renderHero(canvas, imageWidth, imageHeight, spec, highlight)
            WatermarkStyle.COMPACT -> renderCompact(canvas, imageWidth, imageHeight, spec, highlight)
        }
    }

    private fun renderHero(
        canvas: Canvas,
        width: Int,
        height: Int,
        spec: WatermarkSpec,
        highlight: Boolean
    ) {
        val titleSize = (spec.titleSizeFraction * width).coerceAtLeast(2f)
        val smallSize = titleSize * 0.38f
        val gap = smallSize * 0.5f
        val smallLineHeight = smallSize * 1.22f

        val timePaint = textPaint(titleSize, spec, bold = true)
        val smallPaint = textPaint(smallSize, spec, bold = false)
        val accentPaint = accentPaint(spec)

        val time = spec.timeText
        val infoLine = listOfNotNull(spec.weekdayText, spec.weatherText)
            .joinToString(" ")
            .ifBlank { null }
        val rightLines = listOfNotNull(spec.dateText, infoLine)
        val timeWidth = time?.let { timePaint.measureText(it) } ?: 0f
        val rightWidth = rightLines.maxOfOrNull { smallPaint.measureText(it) } ?: 0f
        val barWidth = if (time != null && rightLines.isNotEmpty()) {
            maxOf(2f, width * 0.0028f)
        } else {
            0f
        }
        val barGap = if (barWidth > 0f) gap * 1.2f else 0f
        val row1Width = timeWidth + barGap + barWidth + barGap + rightWidth

        val titleMetrics = timePaint.fontMetrics
        val titleLineHeight = titleMetrics.descent - titleMetrics.ascent
        val smallMetrics = smallPaint.fontMetrics
        val rightBlockHeight = smallLineHeight * rightLines.size
        val row1Height = maxOf(titleLineHeight, rightBlockHeight)

        val locText = spec.locationText
        val dotSize = smallSize * 0.5f
        val locTextOffset = dotSize + smallSize * 0.35f
        val locWidth = locText?.let { locTextOffset + smallPaint.measureText(it) } ?: 0f

        // 经纬度行：深色圆角底，与参考成片一致（地点行下方）
        val coordinatesLabel = spec.coordinatesText?.let { "经纬度：$it" }
        val coordPadX = smallSize * 0.5f
        val coordPadY = smallSize * 0.22f
        val coordWidth = coordinatesLabel?.let {
            coordPadX * 2f + smallPaint.measureText(it)
        } ?: 0f
        val coordHeight = smallLineHeight + coordPadY * 2f

        val customText = spec.customText
        val padX = smallSize * 0.5f
        val padY = smallSize * 0.22f
        val stripeWidth = if (customText != null) maxOf(2f, width * 0.0022f) else 0f
        val customWidth = customText?.let {
            stripeWidth + padX + smallPaint.measureText(it) + padX
        } ?: 0f
        val customHeight = smallLineHeight + padY * 2f

        val blockWidth = maxOf(row1Width, locWidth, coordWidth, customWidth)
        val blockHeight = row1Height +
            (if (locText != null) gap * 0.9f + smallLineHeight else 0f) +
            (if (coordinatesLabel != null) gap * 0.9f + coordHeight else 0f) +
            (if (customText != null) gap * 0.9f + customHeight else 0f)

        lastBlockWidth = blockWidth
        lastBlockHeight = blockHeight

        // 归一化坐标是「水印中心点」；横竖屏切换后画面比例变化（3:4 ↔ 4:3），
        // 同一比例可能让整块水印超出画面，这里按图像边界钳制，预览与成片共用同一逻辑。
        val centerX = clampCenter(spec.xFraction * width, blockWidth, width.toFloat())
        val centerY = clampCenter(spec.yFraction * height, blockHeight, height.toFloat())
        val left = centerX - blockWidth / 2f
        val top = centerY - blockHeight / 2f
        lastBlockLeft = left
        lastBlockTop = top

        canvas.save()
        if (spec.rotationDegrees != 0f) {
            canvas.rotate(spec.rotationDegrees, centerX, centerY)
        }

        var cursorY = top

        // 第一行：大号时间 + 橙色竖线 + 日期/星期
        if (time != null) {
            val baseline = centeredBaseline(cursorY, row1Height, titleMetrics)
            canvas.drawText(time, left, baseline, timePaint)
        }
        if (barWidth > 0f) {
            val barHeight = maxOf(rightBlockHeight, smallLineHeight) * 0.92f
            val barX = left + timeWidth + barGap
            val barY = cursorY + (row1Height - barHeight) / 2f
            canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, accentPaint)
        }
        if (rightLines.isNotEmpty()) {
            var lineTop = cursorY + (row1Height - rightBlockHeight) / 2f
            val textX = left + timeWidth + barGap + barWidth + barGap
            rightLines.forEach { line ->
                canvas.drawText(line, textX, lineTop - smallMetrics.ascent, smallPaint)
                lineTop += smallLineHeight
            }
        }
        cursorY += row1Height

        // 地点行：橙色圆点 + 文本
        if (locText != null) {
            cursorY += gap * 0.9f
            val dotCenterY = cursorY + smallLineHeight / 2f
            canvas.drawCircle(left + dotSize / 2f, dotCenterY, dotSize / 2f, accentPaint)
            val baseline = centeredBaseline(cursorY, smallLineHeight, smallMetrics)
            canvas.drawText(locText, left + locTextOffset, baseline, smallPaint)
            cursorY += smallLineHeight
        }

        // 经纬度行：可配置圆角底板（透明度 0 时不绘制）
        if (coordinatesLabel != null) {
            cursorY += gap * 0.9f
            val radius = smallSize * 0.18f
            if (spec.coordinateBackdropAlpha > 0f) {
                canvas.drawRoundRect(
                    left, cursorY, left + coordWidth, cursorY + coordHeight,
                    radius, radius,
                    backdropPaint(
                        spec.coordinateBackdropColorArgb,
                        spec.coordinateBackdropAlpha,
                        spec
                    )
                )
            }
            val baseline = centeredBaseline(cursorY, coordHeight, smallMetrics)
            canvas.drawText(coordinatesLabel, left + coordPadX, baseline, smallPaint)
            cursorY += coordHeight
        }

        // 自定义文案：可配置圆角底 + 左侧橙色竖条（底板完全透明时两者都不画）
        // 底板范围与占位高度一致（cursorY..cursorY+customHeight），文字按底板中心垂直居中
        if (customText != null) {
            cursorY += gap * 0.9f
            val radius = smallSize * 0.18f
            if (spec.customBackdropAlpha > 0f) {
                canvas.drawRoundRect(
                    left, cursorY, left + customWidth, cursorY + customHeight,
                    radius, radius,
                    backdropPaint(spec.customBackdropColorArgb, spec.customBackdropAlpha, spec)
                )
                canvas.drawRect(
                    left, cursorY, left + stripeWidth, cursorY + customHeight,
                    accentPaint
                )
            }
            val baseline = centeredBaseline(cursorY, customHeight, smallMetrics)
            canvas.drawText(customText, left + stripeWidth + padX, baseline, smallPaint)
        }

        if (highlight) {
            val pad = smallSize * 0.6f
            canvas.drawRoundRect(
                left - pad, top - pad, left + blockWidth + pad, top + blockHeight + pad,
                pad, pad, highlightPaint(width)
            )
        }

        canvas.restore()
    }

    private fun renderCompact(
        canvas: Canvas,
        width: Int,
        height: Int,
        spec: WatermarkSpec,
        highlight: Boolean
    ) {
        val baseSize = (spec.titleSizeFraction * width * 0.42f).coerceAtLeast(2f)
        val timeSize = baseSize * 1.55f
        val gap = baseSize * 0.45f

        val normalPaint = textPaint(baseSize, spec, bold = false)
        val timePaint = textPaint(timeSize, spec, bold = true)
        val normalMetrics = normalPaint.fontMetrics
        val normalLineHeight = normalMetrics.descent - normalMetrics.ascent
        val timeMetrics = timePaint.fontMetrics
        val timeLineHeight = timeMetrics.descent - timeMetrics.ascent

        val lines = mutableListOf<CompactLine>()
        spec.timeText?.let { lines += CompactLine(it, timePaint, timeLineHeight) }
        val secondLine = listOfNotNull(spec.dateText, spec.weekdayText, spec.weatherText)
            .joinToString("  ")
        if (secondLine.isNotEmpty()) lines += CompactLine(secondLine, normalPaint, normalLineHeight)
        spec.locationText?.let { lines += CompactLine(it, normalPaint, normalLineHeight) }
        spec.coordinatesText?.let {
            lines += CompactLine("经纬度：$it", normalPaint, normalLineHeight)
        }
        spec.customText?.let { lines += CompactLine(it, normalPaint, normalLineHeight) }
        if (lines.isEmpty()) return

        val blockWidth = lines.maxOf { it.paint.measureText(it.text) }
        val blockHeight = lines.sumOf { it.lineHeight.toDouble() }.toFloat() +
            gap * (lines.size - 1)

        lastBlockWidth = blockWidth
        lastBlockHeight = blockHeight

        val centerX = clampCenter(spec.xFraction * width, blockWidth, width.toFloat())
        val centerY = clampCenter(spec.yFraction * height, blockHeight, height.toFloat())
        val left = centerX - blockWidth / 2f
        val top = centerY - blockHeight / 2f
        lastBlockLeft = left
        lastBlockTop = top
        var cursorY = top

        canvas.save()
        if (spec.rotationDegrees != 0f) {
            canvas.rotate(spec.rotationDegrees, centerX, centerY)
        }
        lines.forEach { line ->
            val metrics = line.paint.fontMetrics
            val baseline = centeredBaseline(cursorY, line.lineHeight, metrics)
            canvas.drawText(line.text, left, baseline, line.paint)
            cursorY += line.lineHeight + gap
        }
        if (highlight) {
            val pad = baseSize * 0.55f
            canvas.drawRoundRect(
                left - pad, top - pad, left + blockWidth + pad, top + blockHeight + pad,
                pad, pad, highlightPaint(width)
            )
        }
        canvas.restore()
    }

    /**
     * 计算在 [top]..[top]+[boxHeight] 的范围内垂直居中时的文字 baseline。
     *
     * Paint 的 ascent 为负、descent 为正，文本「字体盒中心」位于
     * baseline + (ascent + descent) / 2。直接按 top 画 baseline 会整体偏下，
     * 这里显式把字体盒中心对齐到盒子中心，保证底条内的文字上下留白一致。
     */
    private fun centeredBaseline(top: Float, boxHeight: Float, metrics: Paint.FontMetrics): Float {
        val textHeight = metrics.descent - metrics.ascent
        return top + (boxHeight - textHeight) / 2f - metrics.ascent
    }

    /**
     * 把水印块中心限制在画面内：整块比画面还大时退化为居中，
     * 否则保留尽量接近原归一化坐标的可视位置。
     */
    private fun clampCenter(desired: Float, blockSize: Float, imageSize: Float): Float {
        if (blockSize >= imageSize) return imageSize / 2f
        return desired.coerceIn(blockSize / 2f, imageSize - blockSize / 2f)
    }

    private fun highlightPaint(width: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 107, 53)
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, width * 0.003f)
        strokeJoin = Paint.Join.ROUND
    }

    private fun textPaint(size: Float, spec: WatermarkSpec, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = size
            color = spec.colorArgb
            alpha = (spec.alpha.coerceIn(0f, 1f) * 255f).toInt()
            setShadowLayer(size * 0.14f, 0f, size * 0.06f, Color.argb(150, 0, 0, 0))
        }

    private fun accentPaint(spec: WatermarkSpec): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.accentArgb
        alpha = (spec.alpha.coerceIn(0f, 1f) * 255f).toInt()
    }

    /**
     * 底板画笔：颜色由用户选择，不透明度 = 底板自身 alpha × 整块水印 [WatermarkSpec.alpha]。
     * 两层语义独立，底板的透明叠加不会影响文字本身的透明度。
     */
    private fun backdropPaint(
        colorArgb: Int,
        backdropAlpha: Float,
        spec: WatermarkSpec
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        alpha = (
            backdropAlpha.coerceIn(0f, 1f) * spec.alpha.coerceIn(0f, 1f) * 255f
            ).toInt()
    }

    private data class CompactLine(
        val text: String,
        val paint: Paint,
        val lineHeight: Float
    )
}
