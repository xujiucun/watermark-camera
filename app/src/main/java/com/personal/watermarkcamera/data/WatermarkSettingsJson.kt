package com.personal.watermarkcamera.data

import com.personal.watermarkcamera.domain.LocationDetail
import com.personal.watermarkcamera.domain.WatermarkSettings
import com.personal.watermarkcamera.domain.WatermarkTemplate
import org.json.JSONObject

/**
 * 水印设置的 JSON 编解码。当前设置与「我的模板」快照共用这一份实现，
 * 保证模板能完整还原（模板样式、各开关、文案、地点粒度、字号、颜色、透明度、位置）。
 * 字段缺失时逐个回落到 [WatermarkSettings] 的默认值，便于后续加字段而不破坏旧数据。
 */
internal object WatermarkSettingsJson {

    fun encode(settings: WatermarkSettings): JSONObject = JSONObject().apply {
        put("template", settings.template.id)
        put("showTime", settings.showTime)
        put("showDate", settings.showDate)
        put("showWeather", settings.showWeather)
        put("showLocation", settings.showLocation)
        put("showCoordinates", settings.showCoordinates)
        put("showCustom", settings.showCustom)
        put("customText", settings.customText)
        put("titleSize", settings.titleSizeFraction.toDouble())
        put("colorArgb", settings.colorArgb)
        put("alpha", settings.alpha.toDouble())
        put("coordinateBackdropColor", settings.coordinateBackdropColorArgb)
        put("coordinateBackdropAlpha", settings.coordinateBackdropAlpha.toDouble())
        put("customBackdropColor", settings.customBackdropColorArgb)
        put("customBackdropAlpha", settings.customBackdropAlpha.toDouble())
        put("x", settings.xFraction.toDouble())
        put("y", settings.yFraction.toDouble())
        put("locationDetail", settings.locationDetail.id)
    }

    fun decode(json: JSONObject): WatermarkSettings {
        val defaults = WatermarkSettings()
        return WatermarkSettings(
            template = WatermarkTemplate.fromId(json.optString("template", defaults.template.id)),
            showTime = json.optBoolean("showTime", defaults.showTime),
            showDate = json.optBoolean("showDate", defaults.showDate),
            showWeather = json.optBoolean("showWeather", defaults.showWeather),
            showLocation = json.optBoolean("showLocation", defaults.showLocation),
            showCoordinates = json.optBoolean("showCoordinates", defaults.showCoordinates),
            showCustom = json.optBoolean("showCustom", defaults.showCustom),
            customText = json.optString("customText", defaults.customText),
            titleSizeFraction = json.optDouble("titleSize", defaults.titleSizeFraction.toDouble())
                .toFloat(),
            colorArgb = json.optInt("colorArgb", defaults.colorArgb),
            alpha = json.optDouble("alpha", defaults.alpha.toDouble()).toFloat(),
            coordinateBackdropColorArgb = json.optInt(
                "coordinateBackdropColor",
                defaults.coordinateBackdropColorArgb
            ),
            coordinateBackdropAlpha = json.optDouble(
                "coordinateBackdropAlpha",
                defaults.coordinateBackdropAlpha.toDouble()
            ).toFloat(),
            customBackdropColorArgb = json.optInt(
                "customBackdropColor",
                defaults.customBackdropColorArgb
            ),
            customBackdropAlpha = json.optDouble(
                "customBackdropAlpha",
                defaults.customBackdropAlpha.toDouble()
            ).toFloat(),
            xFraction = json.optDouble("x", defaults.xFraction.toDouble()).toFloat(),
            yFraction = json.optDouble("y", defaults.yFraction.toDouble()).toFloat(),
            locationDetail = LocationDetail.fromId(
                json.optString("locationDetail", defaults.locationDetail.id)
            )
        )
    }
}
