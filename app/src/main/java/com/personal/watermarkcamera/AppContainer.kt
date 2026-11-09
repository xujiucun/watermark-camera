package com.personal.watermarkcamera

import android.content.Context
import com.personal.watermarkcamera.data.AmapClient
import com.personal.watermarkcamera.data.AppSettingsStore
import com.personal.watermarkcamera.data.ExternalPhotoSource
import com.personal.watermarkcamera.data.LocationProvider
import com.personal.watermarkcamera.data.MediaStorePhotoRepository
import com.personal.watermarkcamera.data.PhotoComposer
import com.personal.watermarkcamera.data.PhotoOriginalStore
import com.personal.watermarkcamera.data.PhotoRepository
import com.personal.watermarkcamera.data.PhotoShareCache
import com.personal.watermarkcamera.data.WatermarkPresetStore
import com.personal.watermarkcamera.data.WatermarkSettingsStore
import com.personal.watermarkcamera.domain.WatermarkRenderer

/**
 * 手动依赖容器，替代 Hilt。新增依赖时只在这里加一个字段。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val renderer = WatermarkRenderer()

    val photoComposer = PhotoComposer(appContext, renderer)

    val settingsStore = WatermarkSettingsStore(appContext)

    /** 「我的模板」：用户自定义的水印设置快照。 */
    val presetStore = WatermarkPresetStore(appContext)

    val appSettings = AppSettingsStore(appContext)

    val locationProvider = LocationProvider(
        appContext,
        AmapClient(BuildConfig.AMAP_WEB_KEY)
    )

    val originalStore = PhotoOriginalStore(appContext)

    val shareCache = PhotoShareCache(appContext)

    /** 「手机里已有的照片」：系统选图返回的只读 URI 复制、EXIF 读取与地址解析。 */
    private val externalPhotoSource = ExternalPhotoSource(appContext, locationProvider)

    val photoRepository = PhotoRepository(
        mediaStore = MediaStorePhotoRepository(appContext),
        originalStore = originalStore,
        composer = photoComposer,
        external = externalPhotoSource
    )
}
