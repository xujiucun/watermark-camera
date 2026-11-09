package com.personal.watermarkcamera.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.personal.watermarkcamera.domain.WatermarkSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 「手机里已有的照片」的数据来源。
 *
 * 系统 Photo Picker / SAF 返回的是只读 URI，这里把它复制一份到缓存再工作：
 * - 不申请 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`，只依赖系统授予的临时读权限；
 * - 原图不会被修改，缓存副本在编辑页退出时删除；
 * - 水印时间取 EXIF `TAG_DATETIME_ORIGINAL` → `TAG_DATETIME` → 文件修改时间，
 *   不用「当前时间」，保证老照片上的日期是拍摄日期；
 * - 地点优先用 EXIF GPS 反查地址，没有 GPS 才退回当前定位，都没有就显示「未获取位置」。
 */
class ExternalPhotoSource(
    private val context: Context,
    private val locationProvider: LocationProvider
) {

    private val dir = File(context.cacheDir, "external").apply { mkdirs() }

    suspend fun open(uri: Uri, settings: WatermarkSettings): EditLoad = withContext(Dispatchers.IO) {
        val copy = try {
            copyToCache(uri)
        } catch (t: Throwable) {
            Log.w(TAG, "读取所选照片失败：$uri", t)
            null
        } ?: return@withContext EditLoad.Failed("无法读取所选照片，请重新选择一张。")

        if (copy.length() <= 0L) {
            copy.delete()
            return@withContext EditLoad.Failed("所选照片内容为空，无法加水印。")
        }

        // 先只读尺寸做一次可用性检查，避免把损坏文件带进编辑页
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(copy.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            copy.delete()
            return@withContext EditLoad.Failed("所选文件不是受支持的图片格式。")
        }

        val exif = runCatching { ExifInterface(copy.absolutePath) }.getOrNull()
        val exifTime = readExifTime(exif)
        val gps = readExifGps(exif)
        val modifiedTime = copy.lastModified().takeIf { it > 0L }
        val timestamp = exifTime ?: modifiedTime ?: System.currentTimeMillis()

        val location = if (gps != null) {
            locationProvider.snapshotAt(gps.first, gps.second)
        } else {
            // 没有 EXIF GPS：退回当前定位；无权限/无定位时为空快照（水印显示「未获取位置」）
            locationProvider.cached() ?: locationProvider.fetch()
        }

        Log.i(
            TAG,
            "外部照片就绪：size=${bounds.outWidth}x${bounds.outHeight} " +
                "exifTime=${exifTime != null} gps=${gps != null} " +
                "location=${location.detail ?: location.coordinates ?: "无"}"
        )

        EditLoad.Ready(
            EditSession(
                target = EditTarget.Picked(uri),
                timestampMillis = timestamp,
                imageFile = copy,
                spec = settings.toSpec(timestamp, location)
            )
        )
    }

    private fun copyToCache(uri: Uri): File? {
        val target = File(dir, "pick_${System.currentTimeMillis()}_${(100..999).random()}.jpg")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (!copied) {
            target.delete()
            return null
        }
        readModifiedTime(uri)?.let { time -> target.setLastModified(time) }
        return target
    }

    /** Photo Picker 的媒体 URI 有 DATE_MODIFIED，SAF 的文档 URI 用 COLUMN_LAST_MODIFIED。 */
    private fun readModifiedTime(uri: Uri): Long? {
        val mediaSeconds = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        }.getOrNull()
        if (mediaSeconds != null) return mediaSeconds * 1000L

        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        }.getOrNull()
    }

    private fun readExifTime(exif: ExifInterface?): Long? {
        if (exif == null) return null
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())?.time
        }.getOrNull()?.takeIf { it > MIN_VALID_TIME_MS }
    }

    @Suppress("DEPRECATION")
    private fun readExifGps(exif: ExifInterface?): Pair<Double, Double>? {
        val latLong = exif?.latLong ?: return null
        if (latLong.size < 2) return null
        val latitude = latLong[0]
        val longitude = latLong[1]
        if (latitude.isNaN() || longitude.isNaN()) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return latitude to longitude
    }

    private companion object {
        const val TAG = "ExternalPhotoSource"

        /** 早于 1970-01-02 的 EXIF 时间视为无效（部分相机写入 0000:00:00）。 */
        const val MIN_VALID_TIME_MS = 24L * 60 * 60 * 1000
    }
}
