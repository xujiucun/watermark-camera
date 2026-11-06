package com.personal.watermarkcamera.data

import android.content.Context
import com.personal.watermarkcamera.domain.WatermarkSpec
import com.personal.watermarkcamera.domain.WatermarkStyle
import org.json.JSONObject
import java.io.File

/**
 * 应用私有目录里的「未加水印原图」：拍完还能改水印的素材来源，
 * 相册里仍然只有一张带水印成片。
 *
 * 命名用拍摄时间戳毫秒：
 * - `files/originals/<timestamp>.jpg`：已旋正/镜像的纯净原图；
 * - `files/originals/<timestamp>.json`：当时的 [WatermarkSpec] 快照（重新编辑时复原参数）。
 *
 * 快照必须包含全部可见字段（含经纬度与两块底板），否则重新编辑会丢行、掉底板颜色。
 * 解码对缺失字段逐个回落默认值，兼容早期版本的旧快照。
 */
class PhotoOriginalStore(context: Context) {

    private val dir = File(context.filesDir, "originals").apply { mkdirs() }

    fun saveOriginal(source: File, timestampMillis: Long): Boolean = runCatching {
        val target = originalFile(timestampMillis)
        source.copyTo(target, overwrite = true)
        source.delete()
        true
    }.getOrDefault(false)

    fun saveSpec(timestampMillis: Long, spec: WatermarkSpec) {
        runCatching {
            specFile(timestampMillis).writeText(encode(spec).toString())
        }
    }

    fun originalFile(timestampMillis: Long): File = File(dir, "$timestampMillis.jpg")

    fun hasOriginal(timestampMillis: Long): Boolean = originalFile(timestampMillis).exists()

    fun loadSpec(timestampMillis: Long): WatermarkSpec? = runCatching {
        val file = specFile(timestampMillis)
        if (!file.exists()) return null
        decode(JSONObject(file.readText()))
    }.getOrNull()

    fun delete(timestampMillis: Long) {
        runCatching { originalFile(timestampMillis).delete() }
        runCatching { specFile(timestampMillis).delete() }
    }

    /**
     * 老照片的 MediaStore 行没有 DATE_TAKEN，只能从文件名解析到「秒」。
     * 在秒级附近寻找真实毫秒命名的原图，避免因为丢毫秒而把原图误判为不存在。
     */
    fun findOriginalNear(millis: Long, toleranceMillis: Long = 1500L): Long? {
        if (hasOriginal(millis)) return millis
        val candidates = allTimestamps().filter { kotlin.math.abs(it - millis) <= toleranceMillis }
        return candidates.minByOrNull { kotlin.math.abs(it - millis) }
    }

    fun allTimestamps(): Set<Long> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".jpg") }
        .mapNotNull { file -> file.name.substringBefore('.').toLongOrNull() }
        .toSet()

    private fun specFile(timestampMillis: Long): File = File(dir, "$timestampMillis.json")

    private fun encode(spec: WatermarkSpec): JSONObject = JSONObject().apply {
        put("time", spec.timeText ?: JSONObject.NULL)
        put("date", spec.dateText ?: JSONObject.NULL)
        put("weekday", spec.weekdayText ?: JSONObject.NULL)
        put("weather", spec.weatherText ?: JSONObject.NULL)
        put("location", spec.locationText ?: JSONObject.NULL)
        put("coordinates", spec.coordinatesText ?: JSONObject.NULL)
        put("custom", spec.customText ?: JSONObject.NULL)
        put("x", spec.xFraction.toDouble())
        put("y", spec.yFraction.toDouble())
        put("titleSize", spec.titleSizeFraction.toDouble())
        put("colorArgb", spec.colorArgb)
        put("alpha", spec.alpha.toDouble())
        put("coordinateBackdropColor", spec.coordinateBackdropColorArgb)
        put("coordinateBackdropAlpha", spec.coordinateBackdropAlpha.toDouble())
        put("customBackdropColor", spec.customBackdropColorArgb)
        put("customBackdropAlpha", spec.customBackdropAlpha.toDouble())
        put("accentArgb", spec.accentArgb)
        put("style", if (spec.style == WatermarkStyle.COMPACT) "compact" else "hero")
        put("rotation", spec.rotationDegrees.toDouble())
    }

    private fun decode(json: JSONObject): WatermarkSpec {
        val defaults = WatermarkSpec()
        return WatermarkSpec(
            timeText = json.nullableString("time"),
            dateText = json.nullableString("date"),
            weekdayText = json.nullableString("weekday"),
            weatherText = json.nullableString("weather"),
            locationText = json.nullableString("location"),
            coordinatesText = json.nullableString("coordinates"),
            customText = json.nullableString("custom"),
            xFraction = json.optDouble("x", defaults.xFraction.toDouble()).toFloat(),
            yFraction = json.optDouble("y", defaults.yFraction.toDouble()).toFloat(),
            titleSizeFraction = json.optDouble(
                "titleSize",
                defaults.titleSizeFraction.toDouble()
            ).toFloat(),
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
            accentArgb = json.optInt("accentArgb", defaults.accentArgb),
            style = if (json.optString("style", "hero") == "compact") {
                WatermarkStyle.COMPACT
            } else {
                WatermarkStyle.HERO
            },
            rotationDegrees = json.optDouble("rotation", 0.0).toFloat()
        )
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifBlank { null }
    }
}
