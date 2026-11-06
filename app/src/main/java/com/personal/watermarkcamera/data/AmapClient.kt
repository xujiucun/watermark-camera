package com.personal.watermarkcamera.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 高德逆地理结果（Web 服务）。[adcode] 用于查天气。 */
data class AmapAddress(
    val district: String,
    val detail: String,
    val adcode: String?
)

/**
 * 高德 Web 服务客户端（仅两个接口：逆地理 + 实况天气）。
 * 使用 HttpURLConnection，不引入额外依赖；所有异常都在内部吞掉并返回 null，
 * 由调用方决定降级策略，绝不让网络异常冒到主线程。
 */
class AmapClient(private val webKey: String) {

    val isConfigured: Boolean get() = webKey.isNotBlank()

    suspend fun reverseGeocode(latitude: Double, longitude: Double): AmapAddress? =
        withContext(Dispatchers.IO) {
            val location = "$longitude,$latitude"
            Log.d(TAG, "regeo request: lat=$latitude lon=$longitude")
            val json = get(
                "https://restapi.amap.com/v3/geocode/regeo?key=$webKey" +
                    "&location=${URLEncoder.encode(location, "UTF-8")}&extensions=base"
            ) ?: return@withContext null
            val regeocode = json.optJSONObject("regeocode") ?: return@withContext null
            val component = regeocode.optJSONObject("addressComponent") ?: return@withContext null

            val province = component.text("province")
            val city = component.text("city")
            val districtName = component.text("district")
            val district = buildDistrict(province, city, districtName)

            val building = component.optJSONObject("building")?.text("name")
            val neighborhood = component.optJSONObject("neighborhood")?.text("name")
            val streetNumber = component.optJSONObject("streetNumber")
            val street = streetNumber?.text("street")
            val number = streetNumber?.text("number")
            val township = component.text("township")
            val poi = building ?: neighborhood
                ?: listOfNotNull(street, number).joinToString("").ifBlank { null }
                ?: township
            val detail = if (poi.isNullOrBlank()) district else "$district · $poi"

            AmapAddress(
                district = district,
                detail = detail,
                adcode = component.text("adcode")
            )
        }

    suspend fun weather(adcode: String): String? = withContext(Dispatchers.IO) {
        if (adcode.isBlank()) return@withContext null
        Log.d(TAG, "weather request: adcode=$adcode")
        val json = get(
            "https://restapi.amap.com/v3/weather/weatherInfo?key=$webKey" +
                "&city=${URLEncoder.encode(adcode, "UTF-8")}&extensions=base"
        ) ?: return@withContext null
        val lives = json.optJSONArray("lives") ?: return@withContext null
        if (lives.length() == 0) return@withContext null
        val live = lives.optJSONObject(0) ?: return@withContext null
        val weather = live.text("weather") ?: return@withContext null
        val temperature = live.text("temperature")
        if (temperature.isNullOrBlank()) weather else "$weather ${temperature}°C"
    }

    private fun get(url: String): JSONObject? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            val json = JSONObject(body)
            if (json.optString("status") != "1") null else json
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun buildDistrict(province: String?, city: String?, district: String?): String {
        val cityName = city?.takeIf { it.isNotBlank() }
        val provinceName = province?.takeIf { it.isNotBlank() }
        val districtName = district?.takeIf { it.isNotBlank() }
        return when {
            cityName != null && districtName != null && !districtName.startsWith(cityName) ->
                cityName + districtName
            cityName != null -> cityName
            provinceName != null && districtName != null &&
                !districtName.startsWith(provinceName) -> provinceName + districtName
            provinceName != null -> provinceName
            districtName != null -> districtName
            else -> "未知地区"
        }
    }

    /**
     * 高德在空字段上可能返回空数组（如直辖市 city=[]），这里统一成 String?。
     */
    private fun JSONObject.text(key: String): String? {
        return when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotBlank() }
            is JSONArray -> if (value.length() > 0) {
                value.optString(0).takeIf { it.isNotBlank() }
            } else {
                null
            }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        const val TAG = "WatermarkAmap"
        const val TIMEOUT_MS = 4000
    }
}
