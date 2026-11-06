package com.personal.watermarkcamera.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** 直接删除的结果：已删掉 / 需要用户在系统确认框里授权。 */
sealed interface DirectDeleteResult {
    data object Deleted : DirectDeleteResult

    /** [intentSender] 仅 API 29 的 RecoverableSecurityException 会自带；API 30+ 走批量请求。 */
    data class NeedsConsent(val intentSender: IntentSender?) : DirectDeleteResult
}

/** MediaStore 里属于本应用的一条成片记录。 */
data class MediaPhotoRecord(
    val uri: Uri,
    val displayName: String,
    val dateAddedMillis: Long,
    val dateModifiedMillis: Long,
    /** 写入时由本应用显式记录的精确拍摄毫秒；老照片可能为空。 */
    val dateTakenMillis: Long?
)

/**
 * 只访问本应用自己 contributed 的媒体：
 * - 写入 `Pictures/WatermarkCamera` 不需要任何存储权限；
 * - API 29+ 查询自己写入的媒体也不需要 READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE；
 * - 查询条件用 OWNER_PACKAGE_NAME + RELATIVE_PATH 双重限定，不会看到别的应用的照片。
 */
class MediaStorePhotoRepository(private val context: Context) {

    /** 是否持有相册读取权限；只有「找回历史照片」这个兜底场景才会用到。 */
    fun canReadMedia(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun save(source: File, displayName: String, timestampMillis: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/WatermarkCamera"
            )
            // 精确毫秒：图片页改为查 MediaStore 后，用它找回同名的私有原图
            put(MediaStore.Images.Media.DATE_TAKEN, timestampMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在相册中创建条目")

        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /**
     * 本应用归属 + `Pictures/WatermarkCamera` 的成片列表。
     *
     * 默认（未授予相册读取权限）只返回自己 contributed 的行——API 29+ 无需任何权限；
     * 若用户主动授予了相册读取权限，则改为「目录 + 本应用文件名规则」取回全部历史照片：
     * 清除应用数据 / 卸载重装后，媒体行会失去 OWNER_PACKAGE_NAME 归属，
     * 只有拿到读取权限才可能重新看到它们（真机实测：清数据后 owner 变为 NULL）。
     */
    fun queryOwned(): List<MediaPhotoRecord> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
        )
        val canReadAll = canReadMedia()
        val selection = buildString {
            if (canReadAll) {
                // 有读取权限：目录 + 本应用固定命名（WMC_ 前缀），避免看到别人的文件
                append(MediaStore.Images.Media.RELATIVE_PATH).append(" LIKE ? AND ")
                append(MediaStore.Images.Media.DISPLAY_NAME).append(" LIKE ? AND ")
            } else {
                append(MediaStore.MediaColumns.OWNER_PACKAGE_NAME).append("=? AND ")
                append(MediaStore.Images.Media.RELATIVE_PATH).append(" LIKE ? AND ")
            }
            append(MediaStore.Images.Media.IS_PENDING).append("=0")
        }
        val args = if (canReadAll) {
            arrayOf(
                "${Environment.DIRECTORY_PICTURES}/WatermarkCamera%",
                "WMC_%"
            )
        } else {
            arrayOf(
                context.packageName,
                "${Environment.DIRECTORY_PICTURES}/WatermarkCamera%"
            )
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val rows = runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val result = mutableListOf<MediaPhotoRecord>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val addedSeconds = cursor.getLong(addedIndex)
                    val modifiedSeconds = cursor.getLong(modifiedIndex)
                    val takenMillis = cursor.getLong(takenIndex)
                    result += MediaPhotoRecord(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        displayName = cursor.getString(nameIndex).orEmpty(),
                        dateAddedMillis = addedSeconds * 1000L,
                        dateModifiedMillis = modifiedSeconds * 1000L,
                        dateTakenMillis = takenMillis.takeIf { it > 0L }
                    )
                }
                result
            }.orEmpty()
        }.onFailure {
            // 不吞异常：查询失败时记录日志，便于排查权限/厂商实现差异
            Log.w(TAG, "查询自有媒体失败", it)
        }.getOrElse { emptyList() }
        Log.i(TAG, "查询自有媒体：命中 ${rows.size} 条")
        return rows
    }

    /**
     * 用新文件覆盖已有成片（相册里仍是同一条记录、同一个 URI）。
     *
     * 先把 IS_PENDING 置 1 再写，写完置回 0：媒体库会据此丢弃旧缩略图并重新生成，
     * 避免「图片内容变了、缩略图还是旧的」。任何异常都会把 IS_PENDING 复位。
     */
    fun overwrite(uri: Uri, source: File): Boolean {
        val resolver = context.contentResolver
        val pendingOn = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        resolver.update(uri, pendingOn, null, null)
        return try {
            val output = resolver.openOutputStream(uri, "wt")
                ?: throw IOException("无法打开相册条目写入")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            val ready = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            resolver.update(uri, ready, null, null)
            true
        } catch (t: Throwable) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            false
        }
    }

    /** 重新编辑老照片后补写精确拍摄时间，后续查询就能稳定找回同一张私有原图。 */
    fun updateDateTaken(uri: Uri, timestampMillis: Long) {
        runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, timestampMillis)
                },
                null,
                null
            )
        }
    }

    /**
     * 分级删除第一步：直接删除。
     *
     * API 29+ 对「本应用写入」的媒体行不需要用户确认；但清除应用数据/重装后，
     * 系统会把媒体行的 OWNER_PACKAGE_NAME 置空，此时 delete 返回 0 行（API 29 抛
     * RecoverableSecurityException），必须退到系统确认框，由用户在系统界面点「允许」。
     */
    fun deleteDirect(uri: Uri): DirectDeleteResult {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            Log.i(TAG, "直接删除 $uri：影响 $rows 行")
            if (rows > 0) DirectDeleteResult.Deleted else DirectDeleteResult.NeedsConsent(null)
        } catch (t: RecoverableSecurityException) {
            // API 29：系统给出一次性的授权 IntentSender，交给界面拉起系统确认框
            Log.w(TAG, "直接删除需要用户授权：$uri", t)
            DirectDeleteResult.NeedsConsent(t.userAction.actionIntent.intentSender)
        } catch (t: Throwable) {
            // 归属被清除 / 无权访问时部分厂商会抛 SecurityException 而不是返回 0 行
            Log.w(TAG, "直接删除抛出异常：$uri", t)
            DirectDeleteResult.NeedsConsent(null)
        }
    }

    /**
     * API 30+：为一批媒体行创建系统删除请求（系统确认框里可一次性允许全部）。
     * 返回 null 表示当前系统版本不支持或创建失败，调用方按失败处理。
     */
    fun createDeleteRequest(uris: List<Uri>): IntentSender? {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.onFailure {
            Log.w(TAG, "创建系统删除请求失败（${uris.size} 张）", it)
        }.getOrNull()
    }

    /**
     * 用户在系统确认框操作后，逐张回查这条记录是否真的消失。
     * 查询失败时保守判定为「仍然存在」，避免误报删除成功。
     */
    fun stillExists(uri: Uri): Boolean = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(true)

    private companion object {
        const val TAG = "MediaStorePhotoRepository"
    }
}
