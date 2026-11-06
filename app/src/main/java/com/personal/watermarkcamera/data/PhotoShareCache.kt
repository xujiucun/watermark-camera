package com.personal.watermarkcamera.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 分享用的临时副本：
 * - 成片在 MediaStore 里，先复制到 `cache/shared/`，再用 FileProvider 授权给目标 App；
 * - 勾选「移除位置信息」时，复制后剥离 EXIF 里的 GPS 标签；
 * - 不在缓存里永久堆积：每次分享前与进入图片页时清理超过 1 小时的旧副本。
 * FileProvider 只暴露 `cache/shared/`，不暴露私有原图目录。
 */
class PhotoShareCache(private val context: Context) {

    private val dir = File(context.cacheDir, "shared").apply { mkdirs() }

    /** 返回可供分享的 content:// URI；单张复制失败时返回 null，由调用方提示。 */
    suspend fun prepare(items: List<PhotoItem>, stripLocation: Boolean): List<Uri> =
        withContext(Dispatchers.IO) {
            cleanupOlderThan()
            items.mapNotNull { item ->
                runCatching { copyAndWrap(item, stripLocation) }.getOrNull()
            }
        }

    /** 只清理旧文件，避免误删仍在被接收方读取的副本。 */
    fun cleanupOlderThan(maxAgeMillis: Long = MAX_AGE_MILLIS) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private fun copyAndWrap(item: PhotoItem, stripLocation: Boolean): Uri? {
        val target = File(
            dir,
            "WMC_${item.timestampMillis}_${System.nanoTime()}.jpg"
        )
        val input = context.contentResolver.openInputStream(item.uri) ?: return null
        input.use { source ->
            target.outputStream().use { out -> source.copyTo(out) }
        }
        if (target.length() <= 0L) {
            runCatching { target.delete() }
            return null
        }
        if (stripLocation) stripGps(target)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
    }

    private fun stripGps(file: File) {
        val exif = ExifInterface(file.absolutePath)
        GPS_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
        exif.saveAttributes()
    }

    private companion object {
        const val MAX_AGE_MILLIS = 60L * 60L * 1000L

        /**
         * 这些标签足以移除「拍摄位置」；保存后 EXIF 的 GPS IFD 不再含经纬度/海拔/时间。
         * GPS 标签全集见 ExifInterface 文档，其他派生标签（速度、方向等）在农村场景不写入。
         */
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION
        )
    }
}
