package com.personal.watermarkcamera.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.watermarkcamera.domain.LocationSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 系统定位 + 逆地理（高德 Web API 为主 → 系统 Geocoder 兜底）+ 实况天气。
 *
 * - 定位：系统 LocationManager（GPS + Network），超时 3.5s 后退回最近一次已知位置；
 * - 逆地理：高德 4s 超时，失败回落系统 Geocoder（3.5s），再失败只显示经纬度；
 * - 缓存：地址缓存 30 分钟且移动 ≤300m 时直接复用；天气按 adcode 缓存 15 分钟；
 * - 天气拿不到就是 null，水印整段不显示，不阻塞拍照；
 * - 无权限、无 key、无网、配额超限都只返回可降级的数据，绝不抛异常。
 */
class LocationProvider(
    private val context: Context,
    private val amapClient: AmapClient
) {

    private val prefs = context.getSharedPreferences("location_cache", Context.MODE_PRIVATE)
    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 缓存的上次成功地址，用于首帧先显示旧地址（30 分钟内有效）。 */
    fun cached(): LocationSnapshot? {
        val entry = readAddressCache() ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ADDRESS_CACHE_TTL_MS) return null
        val weather = cachedWeather(entry.adcode)
        return LocationSnapshot(
            district = entry.district,
            detail = entry.detail,
            coordinates = formatCoordinates(entry.latitude, entry.longitude),
            weatherText = weather
        )
    }

    /** [forceRefresh] 为 true 时跳过地址缓存（下拉刷新/重进设置等场景）。 */
    suspend fun fetch(forceRefresh: Boolean = false): LocationSnapshot = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext LocationSnapshot()
        val locationManager = manager ?: return@withContext LocationSnapshot()

        val fix = awaitFix(locationManager) ?: lastKnown(locationManager)
            ?: return@withContext LocationSnapshot()

        val coordinates = formatCoordinates(fix.latitude, fix.longitude)
        val cachedAddress = readAddressCache()
        val reusable = cachedAddress?.takeIf { entry ->
            !forceRefresh &&
                System.currentTimeMillis() - entry.timestamp <= ADDRESS_CACHE_TTL_MS &&
                distanceMeters(entry.latitude, entry.longitude, fix.latitude, fix.longitude) <=
                ADDRESS_CACHE_DISTANCE_M &&
                (entry.adcode != null || !amapClient.isConfigured)
        }

        Log.d(TAG, "fetch: force=$forceRefresh reuseAddress=${reusable != null}")
        val address = reusable ?: resolveAddress(fix)?.also { entry ->
            saveAddressCache(entry)
        }
        val weather = address?.adcode?.let { adcode -> resolveWeather(adcode) }

        if (address == null) {
            LocationSnapshot(coordinates = coordinates, weatherText = weather)
        } else {
            LocationSnapshot(
                district = address.district,
                detail = address.detail,
                coordinates = coordinates,
                weatherText = weather
            )
        }
    }

    /**
     * 按指定坐标解析地址与天气，用于「手机里已有照片」的 EXIF GPS：
     * 逆地理成功给出省市区与地点；失败时只带经纬度（水印仍能显示坐标行）；
     * 天气拿不到就为 null，整段不显示。不读写「当前定位」的地址缓存。
     */
    suspend fun snapshotAt(latitude: Double, longitude: Double): LocationSnapshot =
        withContext(Dispatchers.IO) {
            val coordinates = formatCoordinates(latitude, longitude)
            val location = Location("exif").apply {
                this.latitude = latitude
                this.longitude = longitude
            }
            val address = resolveAddress(location)
            val weather = address?.adcode?.let { adcode -> resolveWeather(adcode) }
            if (address == null) {
                LocationSnapshot(coordinates = coordinates, weatherText = weather)
            } else {
                LocationSnapshot(
                    district = address.district,
                    detail = address.detail,
                    coordinates = coordinates,
                    weatherText = weather
                )
            }
        }

    private suspend fun resolveAddress(location: Location): AddressEntry? {
        if (amapClient.isConfigured) {
            val amap = amapClient.reverseGeocode(location.latitude, location.longitude)
            if (amap != null) {
                return AddressEntry(
                    district = amap.district,
                    detail = amap.detail,
                    adcode = amap.adcode,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = System.currentTimeMillis()
                )
            }
        }

        val system = reverseGeocodeSystem(location) ?: return null
        return AddressEntry(
            district = system.first,
            detail = system.second,
            adcode = null,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis()
        )
    }

    private suspend fun resolveWeather(adcode: String?): String? {
        if (adcode.isNullOrBlank()) return null
        val cached = readWeatherCache(adcode)
        if (cached != null && System.currentTimeMillis() - cached.second <= WEATHER_CACHE_TTL_MS) {
            return cached.first
        }
        val fetched = if (amapClient.isConfigured) amapClient.weather(adcode) else null
        if (fetched != null) {
            saveWeatherCache(adcode, fetched)
            return fetched
        }
        // 网络失败时如果还有旧缓存（真实数据）就继续用，否则整段不显示
        return cached?.first
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(locationManager: LocationManager): Location? {
        val providers = PROVIDERS.filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null

        var listener: LocationListener? = null
        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val locationListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: Bundle?
                        ) = Unit
                    }
                    listener = locationListener
                    providers.forEach { provider ->
                        runCatching {
                            locationManager.requestLocationUpdates(
                                provider,
                                0L,
                                0f,
                                locationListener,
                                Looper.getMainLooper()
                            )
                        }
                    }
                    continuation.invokeOnCancellation {
                        runCatching { locationManager.removeUpdates(locationListener) }
                    }
                }
            }
        } finally {
            listener?.let { runCatching { locationManager.removeUpdates(it) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(locationManager: LocationManager): Location? =
        PROVIDERS.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

    private suspend fun reverseGeocodeSystem(location: Location): Pair<String, String>? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(SYSTEM_GEOCODE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Geocoder(context, Locale.CHINA)
                        .getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()
                        ?.let { toDistrictAndDetail(it) }
                }.getOrNull()
            }
        }
    }

    private fun toDistrictAndDetail(address: Address): Pair<String, String> {
        val city = address.locality?.trim()?.takeIf { it.isNotEmpty() }
        val rawSubLocality = address.subLocality?.trim()?.takeIf { it.isNotEmpty() }
        val subAdminArea = address.subAdminArea?.trim()?.takeIf { it.isNotEmpty() }
        val districtPart = when {
            rawSubLocality != null && rawSubLocality != city -> rawSubLocality
            subAdminArea != null && subAdminArea != city -> subAdminArea
            else -> null
        }
        val district = when {
            city == null && districtPart == null ->
                address.adminArea?.trim()?.takeIf { it.isNotEmpty() } ?: "未知地区"
            city == null -> districtPart ?: "未知地区"
            districtPart == null -> city
            districtPart.startsWith(city) -> districtPart
            else -> city + districtPart
        }

        val rawPoi = address.featureName?.trim()?.takeIf { it.isNotEmpty() }
            ?: address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() }
            ?: address.subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }

        val poi = rawPoi?.let { original ->
            var text = original
            city?.let { if (text.startsWith(it)) text = text.removePrefix(it) }
            districtPart?.let { if (text.startsWith(it)) text = text.removePrefix(it) }
            text.trim().trimStart('·', '-', ' ').ifBlank { original }
        }
        val detail = if (poi.isNullOrBlank()) district else "$district · $poi"
        return district to detail
    }

    private fun readAddressCache(): AddressEntry? {
        val raw = prefs.getString(KEY_ADDRESS, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val district = json.optString("district").takeIf { it.isNotBlank() }
            val detail = json.optString("detail").takeIf { it.isNotBlank() }
            if (district == null && detail == null) return@runCatching null
            AddressEntry(
                district = district ?: detail.orEmpty(),
                detail = detail ?: district.orEmpty(),
                adcode = json.optString("adcode").takeIf { it.isNotBlank() },
                latitude = json.optDouble("lat", 0.0),
                longitude = json.optDouble("lon", 0.0),
                timestamp = json.optLong("ts", 0L)
            )
        }.getOrNull()
    }

    private fun saveAddressCache(entry: AddressEntry) {
        val json = JSONObject().apply {
            put("district", entry.district)
            put("detail", entry.detail)
            put("adcode", entry.adcode ?: "")
            put("lat", entry.latitude)
            put("lon", entry.longitude)
            put("ts", entry.timestamp)
        }
        prefs.edit().putString(KEY_ADDRESS, json.toString()).apply()
    }

    private fun readWeatherCache(adcode: String): Pair<String, Long>? {
        if (prefs.getString(KEY_WEATHER_ADCODE, null) != adcode) return null
        val text = prefs.getString(KEY_WEATHER_TEXT, null) ?: return null
        return text to prefs.getLong(KEY_WEATHER_TIME, 0L)
    }

    private fun cachedWeather(adcode: String?): String? {
        if (adcode.isNullOrBlank()) return null
        return readWeatherCache(adcode)?.first
    }

    private fun saveWeatherCache(adcode: String, text: String) {
        prefs.edit()
            .putString(KEY_WEATHER_ADCODE, adcode)
            .putString(KEY_WEATHER_TEXT, text)
            .putLong(KEY_WEATHER_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun formatCoordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * earthRadius * Math.asin(Math.sqrt(a))
    }

    private data class AddressEntry(
        val district: String,
        val detail: String,
        val adcode: String?,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    private companion object {
        const val TAG = "WatermarkLocation"
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        const val FIX_TIMEOUT_MS = 3500L
        const val SYSTEM_GEOCODE_TIMEOUT_MS = 3500L
        const val ADDRESS_CACHE_TTL_MS = 30 * 60 * 1000L
        const val ADDRESS_CACHE_DISTANCE_M = 300.0
        const val WEATHER_CACHE_TTL_MS = 15 * 60 * 1000L
        const val KEY_ADDRESS = "address"
        const val KEY_WEATHER_ADCODE = "weather_adcode"
        const val KEY_WEATHER_TEXT = "weather_text"
        const val KEY_WEATHER_TIME = "weather_time"
    }
}
