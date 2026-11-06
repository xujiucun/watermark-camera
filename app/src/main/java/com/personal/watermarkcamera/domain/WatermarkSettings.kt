package com.personal.watermarkcamera.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 1~2 套一键切换的预设。 */
enum class WatermarkTemplate(val id: String, val label: String) {
    TIME_LOCATION("hero", "时间地点"),
    COMPACT("compact", "简洁时间");

    companion object {
        fun fromId(id: String?): WatermarkTemplate =
            entries.firstOrNull { it.id == id } ?: TIME_LOCATION
    }
}

/** 地点粒度：省市区 / 详细地址（截图里是「市+区 + 小区」的详细形式）。 */
enum class LocationDetail(val id: String, val label: String) {
    DISTRICT("district", "省市区"),
    DETAIL("detail", "详细地址");

    companion object {
        fun fromId(id: String?): LocationDetail =
            entries.firstOrNull { it.id == id } ?: DETAIL
    }
}

/**
 * 用户可编辑的水印参数，持久化到 SharedPreferences。
 * [toSpec] 负责把「设置 + 时间戳 + 位置快照」换算成渲染器唯一依赖的 [WatermarkSpec]，
 * 预览与成片都调用同一个方法，避免两套逻辑。
 */
data class WatermarkSettings(
    val template: WatermarkTemplate = WatermarkTemplate.TIME_LOCATION,
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    val showLocation: Boolean = true,
    /** 经纬度行默认开启；与地点行同源，拿不到定位快照时整行不显示。 */
    val showCoordinates: Boolean = true,
    val showCustom: Boolean = false,
    val customText: String = "记录生活",
    val titleSizeFraction: Float = 0.07f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val alpha: Float = 1f,
    /** 经纬度行的底板颜色与自身不透明度（最终不透明度 = 自身 × [alpha]）。 */
    val coordinateBackdropColorArgb: Int = 0xFF000000.toInt(),
    val coordinateBackdropAlpha: Float = 1f,
    /** 自定义文案行的底板颜色与自身不透明度（最终不透明度 = 自身 × [alpha]）。 */
    val customBackdropColorArgb: Int = 0xFF000000.toInt(),
    val customBackdropAlpha: Float = 1f,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val locationDetail: LocationDetail = LocationDetail.DETAIL
) {

    fun toSpec(
        timestampMillis: Long,
        location: LocationSnapshot?,
        locating: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): WatermarkSpec {
        val zoned = Instant.ofEpochMilli(timestampMillis).atZone(zoneId)
        val timeText = if (showTime) zoned.format(TIME_FORMAT) else null
        val dateText = if (showDate) zoned.format(DATE_FORMAT) else null
        val weekdayText = if (showDate) WEEKDAY_LABELS[zoned.dayOfWeek.value - 1] else null
        val weatherText = if (showWeather) location?.weatherText else null
        val locationText = if (showLocation) resolveLocationText(location, locating) else null
        // 经纬度与地点取自同一个快照，避免「地点聊城、坐标北京」的错配
        val coordinates = if (showCoordinates) location?.coordinates else null
        val custom = if (showCustom) customText.trim().ifBlank { null } else null

        return WatermarkSpec(
            timeText = timeText,
            dateText = dateText,
            weekdayText = weekdayText,
            weatherText = weatherText,
            locationText = locationText,
            coordinatesText = coordinates,
            customText = custom,
            xFraction = xFraction,
            yFraction = yFraction,
            titleSizeFraction = titleSizeFraction,
            colorArgb = colorArgb,
            alpha = alpha,
            coordinateBackdropColorArgb = coordinateBackdropColorArgb,
            coordinateBackdropAlpha = coordinateBackdropAlpha,
            customBackdropColorArgb = customBackdropColorArgb,
            customBackdropAlpha = customBackdropAlpha,
            style = if (template == WatermarkTemplate.COMPACT) {
                WatermarkStyle.COMPACT
            } else {
                WatermarkStyle.HERO
            }
        )
    }

    private fun resolveLocationText(location: LocationSnapshot?, locating: Boolean): String {
        if (location == null) return if (locating) "定位中…" else "未获取位置"
        // 逆地理失败时降级由独立的经纬度行承担，地点行不再复用坐标文本
        val fallback = "未获取位置"
        return when (locationDetail) {
            LocationDetail.DISTRICT -> location.district ?: fallback
            LocationDetail.DETAIL -> location.detail ?: fallback
        }
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val WEEKDAY_LABELS =
            listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    }
}
