package com.personal.watermarkcamera.data

import android.content.IntentSender
import android.net.Uri
import android.util.Log
import com.personal.watermarkcamera.domain.WatermarkSettings
import com.personal.watermarkcamera.domain.WatermarkSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一张成片的展示信息。私有原图不可用时 [hasOriginal] 为 false，图片页会禁用重新编辑。 */
data class PhotoItem(
    val uri: Uri,
    val timestampMillis: Long,
    val dateModifiedMillis: Long = timestampMillis,
    val hasOriginal: Boolean = false,
    /** 私有原图实际使用的毫秒时间戳；老照片可能通过文件名秒级回退匹配到。 */
    val originalTimestampMillis: Long? = null
)

/** 重新编辑所需的素材：私有原图 + 当时的水印快照。 */
data class EditSource(
    val timestampMillis: Long,
    val original: File,
    val spec: WatermarkSpec?
)

/**
 * 删除结果：
 * - [deletedCount] 已经真正删掉的张数；
 * - [failedNames] 走完系统确认框后仍未能删除的文件名；
 * - [consentItems]/[consentIntentSender] 非空表示剩余项需要用户在系统确认框里允许，
 *   界面拉起 [consentIntentSender]，用户操作后再调用 [PhotoRepository.verifyConsent] 核对。
 */
data class DeleteOutcome(
    val deletedCount: Int,
    val failedNames: List<String> = emptyList(),
    val consentItems: List<PhotoItem> = emptyList(),
    val consentIntentSender: IntentSender? = null
)

/**
 * 数据层门面：以系统 MediaStore 为唯一照片来源 + 维护应用占有的原图缓存。
 *
 * 不再依赖 SharedPreferences 里的私有索引：
 * - 每次进入/切回图片页都重新查询 `OWNER_PACKAGE_NAME` 为当前包名、位于
 *   `Pictures/WatermarkCamera` 的成片，系统相册删除后立即消失（不会留空白格）；
 * - 清除应用数据 / 卸载重装后，只要媒体行还在，图片页仍能列出旧照片；
 * - 私有原图只是「可重新编辑」的附加能力，缺失时入口置灰，不影响展示与删除。
 */
class PhotoRepository(
    private val mediaStore: MediaStorePhotoRepository,
    private val originalStore: PhotoOriginalStore,
    private val composer: PhotoComposer,
    private val external: ExternalPhotoSource
) {

    /**
     * 相册只落一张带水印成片；[original] 是已旋正的纯净原图，保存到应用私有目录，
     * 供以后「重新编辑水印」使用（相册里看不到）。
     */
    suspend fun save(
        watermarked: File,
        original: File?,
        timestampMillis: Long,
        spec: WatermarkSpec?
    ): PhotoItem = withContext(Dispatchers.IO) {
        val uri = mediaStore.save(watermarked, fileName(timestampMillis), timestampMillis)
        if (original != null) originalStore.saveOriginal(original, timestampMillis)
        if (spec != null) originalStore.saveSpec(timestampMillis, spec)
        val hasOriginal = originalStore.hasOriginal(timestampMillis)
        PhotoItem(
            uri = uri,
            timestampMillis = timestampMillis,
            dateModifiedMillis = timestampMillis,
            hasOriginal = hasOriginal,
            originalTimestampMillis = timestampMillis.takeIf { hasOriginal }
        )
    }

    /** 「找回历史照片」需要用户授予相册读取权限；界面据此显示引导入口。 */
    fun canReadMedia(): Boolean = mediaStore.canReadMedia()

    suspend fun loadPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val records = mediaStore.queryOwned()
        val items = records.mapNotNull { record ->
            // 文件名由本应用写入时的时间戳生成，是最可靠的依据；
            // DATE_TAKEN 会被部分厂商按秒/按写入完成时间改写，只作兜底。
            val preferred = parseDisplayNameTimestamp(record.displayName)
                ?: record.dateTakenMillis
                ?: record.dateAddedMillis
            if (preferred <= 0L) return@mapNotNull null

            // 老照片的 DATE_TAKEN 可能只到秒，用该秒附近的私有原图找回精确毫秒
            val originalTimestamp = originalStore.findOriginalNear(preferred)
            if (originalTimestamp == null) {
                Log.i(TAG, "缺少私有原图：${record.displayName}（定位时间=$preferred）")
            }
            PhotoItem(
                uri = record.uri,
                timestampMillis = originalTimestamp ?: preferred,
                dateModifiedMillis = record.dateModifiedMillis.takeIf { it > 0L }
                    ?: preferred,
                hasOriginal = originalTimestamp != null,
                originalTimestampMillis = originalTimestamp
            )
        }.sortedByDescending { it.timestampMillis }

        logInconsistencies(items)
        items
    }

    /**
     * 按输入来源载入编辑素材：自家成片沿用私有原图 + 快照；
     * 外部照片由 [ExternalPhotoSource] 复制副本、读 EXIF 时间与 GPS 后构建初始规格。
     */
    suspend fun openEdit(target: EditTarget, settings: WatermarkSettings): EditLoad =
        when (target) {
            is EditTarget.Owned -> {
                val source = loadEditSource(target.item)
                if (source == null) {
                    EditLoad.Failed("这张没有原始照片，无法重新编辑")
                } else {
                    EditLoad.Ready(
                        EditSession(
                            target = target,
                            timestampMillis = source.timestampMillis,
                            imageFile = source.original,
                            spec = source.spec
                        )
                    )
                }
            }

            is EditTarget.Picked -> external.open(target.uri, settings)
        }

    /** 按来源保存：自家成片覆盖原记录，外部照片另存为新成片（原图不动）。 */
    suspend fun saveEdit(session: EditSession, spec: WatermarkSpec): Boolean =
        when (val target = session.target) {
            is EditTarget.Owned -> replaceWatermark(target.item, spec)
            is EditTarget.Picked -> saveAsNew(session, spec)
        }

    /**
     * 外部照片另存为新成片：按 [PhotoComposer.composeExternal] 的降采样策略合成，
     * 走与拍照相同的 MediaStore 写入（IS_PENDING）与命名规则，不触碰原图。
     */
    private suspend fun saveAsNew(session: EditSession, spec: WatermarkSpec): Boolean =
        withContext(Dispatchers.IO) {
            val composed = try {
                composer.composeExternal(session.imageFile, spec)
            } catch (t: Throwable) {
                Log.w(TAG, "外部照片合成失败：${session.imageFile.name}", t)
                return@withContext false
            }
            try {
                // 同一秒内选中多张老照片时错开时间戳，避免成片重名、私有原图互相覆盖
                var timestamp = session.timestampMillis
                while (originalStore.findOriginalNear(timestamp) != null) timestamp += 1_000L
                save(composed.watermarked, composed.original, timestamp, spec)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "外部照片保存失败：${session.imageFile.name}", t)
                false
            } finally {
                composed.watermarked.delete()
                composed.original.delete()
            }
        }

    /** 编辑页退出时清理外部照片的缓存副本；自家成片的私有原图必须保留。 */
    fun releaseSession(session: EditSession) {
        if (session.target is EditTarget.Picked) {
            runCatching { session.imageFile.delete() }
        }
    }

    /** 取重新编辑所需素材；没有私有原图时返回 null，UI 据此提示而不是静默失败。 */
    suspend fun loadEditSource(item: PhotoItem): EditSource? = withContext(Dispatchers.IO) {
        val timestamp = item.originalTimestampMillis
            ?: originalStore.findOriginalNear(item.timestampMillis)
            ?: return@withContext null
        val file = originalStore.originalFile(timestamp)
        if (!file.exists()) return@withContext null
        EditSource(
            timestampMillis = timestamp,
            original = file,
            spec = originalStore.loadSpec(timestamp)
        )
    }

    /**
     * 用私有原图 + 新规格重新合成，并覆盖相册里的同一条成片记录。
     * 原图文件本身保持不变，因此可以反复编辑；成功后才更新快照。
     */
    suspend fun replaceWatermark(item: PhotoItem, spec: WatermarkSpec): Boolean =
        withContext(Dispatchers.IO) {
            val timestamp = item.originalTimestampMillis
                ?: originalStore.findOriginalNear(item.timestampMillis)
                ?: return@withContext false
            val original = originalStore.originalFile(timestamp)
            if (!original.exists()) return@withContext false

            var temp: File? = null
            try {
                temp = composer.renderOverOriginal(original, spec)
                val overwritten = mediaStore.overwrite(item.uri, temp)
                if (overwritten) {
                    originalStore.saveSpec(timestamp, spec)
                    // 老照片补上精确拍摄时间，后续查询能稳定对应到这张原图
                    mediaStore.updateDateTaken(item.uri, timestamp)
                }
                overwritten
            } catch (t: Throwable) {
                Log.w(TAG, "重新编辑水印失败：${item.uri}", t)
                false
            } finally {
                temp?.delete()
            }
        }

    fun originalFor(timestampMillis: Long): File = originalStore.originalFile(timestampMillis)

    /**
     * 分级删除：
     * 1. 先逐张直接删除（自有媒体，系统不弹框）；
     * 2. 直接删除没删掉的（典型是清应用数据/重装后 OWNER_PACKAGE_NAME 被置空），
     *    汇总成一次 `MediaStore.createDeleteRequest`，由界面拉起系统确认框；
     * 3. 连系统确认框都拿不到时，如实记为失败，由界面给出可执行的操作指引。
     *
     * 已删掉的项立即清理私有原图与快照；待确认的项在用户允许后由 [verifyConsent] 核对清理。
     */
    suspend fun deletePhotos(items: List<PhotoItem>): DeleteOutcome =
        withContext(Dispatchers.IO) {
            var deleted = 0
            val failed = mutableListOf<String>()
            val consent = mutableListOf<PhotoItem>()
            var recoverableSender: IntentSender? = null

            items.forEach { item ->
                val result = runCatching { mediaStore.deleteDirect(item.uri) }
                    .onFailure { Log.w(TAG, "直接删除异常：${item.uri}", it) }
                    .getOrDefault(DirectDeleteResult.NeedsConsent(null))
                when (result) {
                    DirectDeleteResult.Deleted -> {
                        deleted++
                        deleteOriginal(item)
                    }

                    is DirectDeleteResult.NeedsConsent -> {
                        consent += item
                        if (recoverableSender == null) recoverableSender = result.intentSender
                    }
                }
            }

            if (consent.isEmpty()) return@withContext DeleteOutcome(deleted, failed)

            // API 30+ 优先用批量删除请求：一次系统框允许全部剩余项
            val sender = mediaStore.createDeleteRequest(consent.map { it.uri })
                ?: recoverableSender
            if (sender == null) {
                failed += consent.map { fileName(it.timestampMillis) }
                DeleteOutcome(deleted, failed)
            } else {
                DeleteOutcome(deleted, failed, consent, sender)
            }
        }

    /**
     * 用户在系统确认框里操作后调用：逐张回查 MediaStore，只有确实消失的才算删除成功，
     * 并同步清理对应私有原图与快照。用户在系统框点「取消」时这里会返回全失败，
     * 界面按「已取消」提示，不报错、不产生删除记录。
     */
    suspend fun verifyConsent(items: List<PhotoItem>): DeleteOutcome =
        withContext(Dispatchers.IO) {
            var deleted = 0
            val failed = mutableListOf<String>()
            items.forEach { item ->
                if (mediaStore.stillExists(item.uri)) {
                    failed += fileName(item.timestampMillis)
                } else {
                    deleted++
                    deleteOriginal(item)
                }
            }
            DeleteOutcome(deleted, failed)
        }

    private fun deleteOriginal(item: PhotoItem) {
        item.originalTimestampMillis?.let { originalStore.delete(it) }
    }

    /**
     * MediaStore 与私有原图不一致时只记录日志，绝不擅自删除用户文件：
     * - 有原图无成片：可能是保存失败或系统相册手动删除，保留原图等待用户处理；
     * - 有成片无原图：老照片或应用数据被清理，入口置灰即可。
     */
    private fun logInconsistencies(items: List<PhotoItem>) {
        val matched = items.mapNotNull { it.originalTimestampMillis }.toSet()
        val orphans = originalStore.allTimestamps() - matched
        if (orphans.isNotEmpty()) {
            Log.w(
                TAG,
                "存在无对应成片的私有原图（保留不删）：${orphans.sorted().take(5)}" +
                    if (orphans.size > 5) " 等 ${orphans.size} 个" else ""
            )
        }
        val withoutOriginal = items.count { !it.hasOriginal }
        if (withoutOriginal > 0) {
            Log.i(TAG, "有 $withoutOriginal 张成片缺少私有原图，重新编辑入口将置灰")
        }
    }

    companion object {
        private const val TAG = "PhotoRepository"

        /** 单一命名规则：WMC_yyyyMMdd_HHmmss.jpg */
        fun fileName(timestampMillis: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMillis))
            return "WMC_$stamp.jpg"
        }

        /** 老照片没有 DATE_TAKEN 时，从本应用固定文件名解析到秒级时间。 */
        private fun parseDisplayNameTimestamp(displayName: String): Long? {
            val base = displayName.substringBeforeLast('.')
            if (!base.startsWith("WMC_")) return null
            return runCatching {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .parse(base.removePrefix("WMC_"))
                    ?.time
            }.getOrNull()
        }
    }
}
