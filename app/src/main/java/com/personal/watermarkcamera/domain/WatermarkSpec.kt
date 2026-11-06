package com.personal.watermarkcamera.domain

/** 水印排版风格：HERO（大号时间 + 橙色竖线 + 小字信息） / COMPACT（等字号简洁多行）。 */
enum class WatermarkStyle {
    HERO,
    COMPACT
}

/**
 * 水印规格。位置、字号都以「图像宽度」归一化，因此同一份规格在预览尺寸和成片全分辨率下
 * 渲染结果一致；相机缩放只改变取景画面，不影响这里的坐标系。
 *
 * [xFraction] / [yFraction] 是水印文本块的中心点（0.5, 0.5 即画面正中），可见区域拖动时
 * 只改这两个值。各字段为 null 表示该类内容关闭。
 */
data class WatermarkSpec(
    val timeText: String? = null,
    val dateText: String? = null,
    val weekdayText: String? = null,
    val weatherText: String? = null,
    val locationText: String? = null,
    val coordinatesText: String? = null,
    val customText: String? = null,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val titleSizeFraction: Float = 0.07f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val alpha: Float = 1f,
    /** 底板自身颜色与不透明度；绘制时再 × [alpha]，与整块水印透明度是两层语义。 */
    val coordinateBackdropColorArgb: Int = 0xFF000000.toInt(),
    val coordinateBackdropAlpha: Float = 1f,
    val customBackdropColorArgb: Int = 0xFF000000.toInt(),
    val customBackdropAlpha: Float = 1f,
    val accentArgb: Int = 0xFFFF6B35.toInt(),
    val style: WatermarkStyle = WatermarkStyle.HERO,
    val rotationDegrees: Float = 0f
) {
    val isEmpty: Boolean
        get() = timeText == null && dateText == null && weekdayText == null &&
            weatherText == null && locationText == null && coordinatesText == null &&
            customText == null
}
