package com.personal.watermarkcamera.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.personal.watermarkcamera.domain.WatermarkRenderer
import com.personal.watermarkcamera.domain.WatermarkSpec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 合成结果：带水印成片 + 已旋正的纯净原图（后者仅供应用私有目录保存）。 */
data class ComposeResult(
    val watermarked: File,
    val original: File
)

/**
 * 拍照后的离屏合成：
 * 1. 读 EXIF orientation，把图旋正到「人眼看到的正向」；
 * 2. 前置摄像头按镜像翻转（先翻转再画水印，保证水印文字不会反）；
 * 3. 先把纯净原图压一份（供「重新编辑水印」预留）；
 * 4. 在全分辨率 Bitmap 上调用与预览同一个 WatermarkRenderer；
 * 5. 压 JPEG，输出 EXIF orientation 归一化，并回填拍摄时间/机型/GPS。
 */
class PhotoComposer(
    private val context: Context,
    private val renderer: WatermarkRenderer
) {

    fun compose(source: File, spec: WatermarkSpec, mirrorHorizontally: Boolean): ComposeResult =
        composeInternal(source, spec, mirrorHorizontally, maxDimension = 0)

    /**
     * 手机里已有照片的合成：这类图片尺寸不可控（可能有 1 亿像素），
     * 按 [maxDimension] 做 2 的幂次降采样，避免整图解码触发 OOM。
     * 降采样对水印观感没有影响（水印按输出尺寸等比绘制）。
     */
    fun composeExternal(
        source: File,
        spec: WatermarkSpec,
        maxDimension: Int = EXTERNAL_MAX_DIMENSION
    ): ComposeResult = composeInternal(source, spec, mirrorHorizontally = false, maxDimension)

    private fun composeInternal(
        source: File,
        spec: WatermarkSpec,
        mirrorHorizontally: Boolean,
        maxDimension: Int
    ): ComposeResult {
        val orientation = readOrientation(source)
        val decoded = decode(source, maxDimension)
            ?: throw IOException("无法解码照片")

        var upright = rotate(decoded, orientation)
        if (upright !== decoded) decoded.recycle()

        if (mirrorHorizontally) {
            val mirrored = mirror(upright)
            upright.recycle()
            upright = mirrored
        }

        // 纯净原图（不加任何水印），供重新编辑水印使用
        val original = File(context.cacheDir, source.nameWithoutExtension + "_orig.jpg")
        FileOutputStream(original).use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        Canvas(upright).also { canvas ->
            renderer.render(canvas, upright.width, upright.height, spec)
        }

        val output = File(context.cacheDir, source.nameWithoutExtension + "_wm.jpg")
        FileOutputStream(output).use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        upright.recycle()

        copyExif(source, output)
        copyExif(source, original)
        return ComposeResult(watermarked = output, original = original)
    }

    /**
     * 「重新编辑水印」专用：私有原图已经是旋正/镜像后的正向图，
     * 不需要再次旋转或镜像，只需按新规格重画水印并保留原有拍摄信息（含 GPS）。
     */
    fun renderOverOriginal(original: File, spec: WatermarkSpec): File {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val upright = BitmapFactory.decodeFile(original.absolutePath, options)
            ?: throw IOException("无法解码原始照片")

        Canvas(upright).also { canvas ->
            renderer.render(canvas, upright.width, upright.height, spec)
        }

        val output = File(context.cacheDir, original.nameWithoutExtension + "_edit.jpg")
        FileOutputStream(output).use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        upright.recycle()

        copyExif(original, output)
        return output
    }

    /** [maxDimension] 为 0 表示按原尺寸解码；否则按 2 的幂次降采样到不超过该边长。 */
    private fun decode(source: File, maxDimension: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        if (maxDimension > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
                sample *= 2
            }
            options.inSampleSize = sample
        }
        return BitmapFactory.decodeFile(source.absolutePath, options)
    }

    private fun readOrientation(source: File): Int = runCatching {
        ExifInterface(source.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply {
            postRotate(degrees, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun mirror(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            setScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun copyExif(source: File, target: File) {
        val sourceExif = ExifInterface(source.absolutePath)
        val targetExif = ExifInterface(target.absolutePath)
        EXIF_TAGS.forEach { tag ->
            sourceExif.getAttribute(tag)?.let { value -> targetExif.setAttribute(tag, value) }
        }
        targetExif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        targetExif.saveAttributes()
    }

    private companion object {
        /** 外部照片解码上限：12MP 级别的照片通常不会被降采样，超大图最多到约 4000px。 */
        const val EXTERNAL_MAX_DIMENSION = 4096

        val EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP
        )
    }
}
