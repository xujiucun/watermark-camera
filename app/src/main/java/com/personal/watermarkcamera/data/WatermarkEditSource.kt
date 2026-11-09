package com.personal.watermarkcamera.data

import android.net.Uri
import com.personal.watermarkcamera.domain.WatermarkSpec
import java.io.File

/**
 * 水印编辑页的输入来源，把「自家成片」和「手机里已有的照片」统一成一种输入。
 *
 * - [Owned]：相册里本应用的成片，素材是应用私有原图 + 当时的水印快照，保存时覆盖同一条成片；
 * - [Picked]：系统 Photo Picker / SAF 选中的照片，只拿着只读 URI，保存时另存为新成片。
 */
sealed interface EditTarget {
    data class Owned(val item: PhotoItem) : EditTarget

    data class Picked(val uri: Uri) : EditTarget
}

/**
 * 载入后的编辑素材。
 *
 * [imageFile] 既是预览的取图来源，也是最终合成的源文件；[EditTarget.Picked] 时它是
 * 复制到缓存里的副本（可能仍带 EXIF orientation，由合成层统一旋正），原图不会被读写。
 * [spec] 为 null 表示自家成片的快照丢失，编辑页按拍摄时间兜底。
 */
data class EditSession(
    val target: EditTarget,
    val timestampMillis: Long,
    val imageFile: File,
    val spec: WatermarkSpec?
) {
    /** 外部照片走「另存为新成片」，自家成片走「覆盖原成片」。 */
    val saveAsNew: Boolean get() = target is EditTarget.Picked
}

/** 编辑素材载入结果；失败时带上可直接展示给用户的原因。 */
sealed interface EditLoad {
    data class Ready(val session: EditSession) : EditLoad

    data class Failed(val message: String) : EditLoad
}
