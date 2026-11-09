package com.personal.watermarkcamera.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.data.EditLoad
import com.personal.watermarkcamera.data.EditSession
import com.personal.watermarkcamera.data.EditTarget
import com.personal.watermarkcamera.data.PhotoRepository
import com.personal.watermarkcamera.domain.WatermarkPreset
import com.personal.watermarkcamera.domain.WatermarkRenderer
import com.personal.watermarkcamera.domain.WatermarkSettings
import com.personal.watermarkcamera.domain.WatermarkSpec
import com.personal.watermarkcamera.domain.WatermarkStyle
import com.personal.watermarkcamera.domain.WatermarkTemplate
import com.personal.watermarkcamera.ui.capture.WatermarkEditPanel
import com.personal.watermarkcamera.ui.capture.WatermarkOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val Accent = Color(0xFFFF6B35)

/**
 * 图片页的水印编辑独立页面，支持两种输入来源（见 [EditTarget]）：
 *
 * - 自家成片：初始参数来自该照片保存的 [WatermarkSpec] 快照，保存时覆盖相册里的同一条成片；
 * - 手机里已有的照片：素材是缓存副本，初始参数用当前水印设置 + 照片 EXIF 拍摄时间 / GPS 生成，
 *   保存时另存为新成片，原图绝不修改。
 *
 * 预览用源图 + 当前参数实时重绘，与成片走同一个 [WatermarkRenderer]；未保存退出不改动任何文件。
 */
@Composable
fun WatermarkEditScreen(
    repository: PhotoRepository,
    renderer: WatermarkRenderer,
    target: EditTarget,
    currentSettings: WatermarkSettings,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val targetKey = when (target) {
        is EditTarget.Owned -> "owned:${target.item.uri}"
        is EditTarget.Picked -> "picked:${target.uri}"
    }
    val picking = target is EditTarget.Picked

    var session by remember(targetKey) { mutableStateOf<EditSession?>(null) }
    var loadFailure by remember(targetKey) { mutableStateOf<String?>(null) }
    var loaded by remember(targetKey) { mutableStateOf(false) }
    var settings by remember(targetKey) { mutableStateOf<WatermarkSettings?>(null) }
    var initialEditSettings by remember(targetKey) { mutableStateOf<WatermarkSettings?>(null) }
    var baseSpec by remember(targetKey) { mutableStateOf<WatermarkSpec?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(targetKey) {
        when (val result = repository.openEdit(target, currentSettings)) {
            is EditLoad.Ready -> {
                val ready = result.session
                session = ready
                val base = ready.spec ?: fallbackSpec(ready.timestampMillis)
                baseSpec = base
                val initial = base.toEditSettings()
                settings = initial
                initialEditSettings = initial
            }

            is EditLoad.Failed -> loadFailure = result.message
        }
        loaded = true
    }

    // 退出编辑页时清理外部照片的缓存副本；自家成片的私有原图必须保留。
    // 注意闭包要捕获当时的 session 值，不能读状态，否则 session 由 null 变为 Ready 时
    // 旧 effect 的 onDispose 会把刚载入的副本删掉。
    val loadedSession = session
    DisposableEffect(loadedSession) {
        onDispose { loadedSession?.let { repository.releaseSession(it) } }
    }

    val dirty = settings != null &&
        initialEditSettings != null &&
        settings != initialEditSettings

    BackHandler {
        when {
            sheetOpen -> sheetOpen = false
            dirty -> confirmDiscard = true
            else -> onBack()
        }
    }

    val current = settings
    val currentBase = baseSpec
    val timestamp = session?.timestampMillis
    val editedSpec = if (current != null && currentBase != null && timestamp != null) {
        buildEditedSpec(current, currentBase, timestamp)
    } else {
        null
    }
    val image by produceState<Bitmap?>(
        initialValue = null,
        session?.imageFile?.absolutePath
    ) {
        val file = session?.imageFile ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { loadSampledUpright(file, maxDimension = 1600) }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：取消 / 标题 / 保存
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    if (dirty) confirmDiscard = true else onBack()
                }) {
                    Text("取消", color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (picking) "给照片加水印" else "编辑水印",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = editedSpec != null && !saving,
                    onClick = { confirmSave = true }
                ) {
                    Text(
                        text = "保存",
                        color = if (editedSpec != null && !saving) Accent else scheme.onSurfaceVariant
                    )
                }
            }

            // 预览：图像按原图比例居中，水印按同一坐标系叠加，实时反映参数
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !loaded -> CircularProgressIndicator(color = Accent)
                    session == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = loadFailure ?: "无法读取这张照片",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (picking) {
                                "请返回重新选择一张照片。"
                            } else {
                                "原始照片可能已被清理；相册成片仍然保留。"
                            },
                            color = Color(0xFFBBBBBB),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    else -> {
                        val bitmap = image
                        val spec = editedSpec
                        if (bitmap != null && spec != null) {
                            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                            var previewSize by remember(aspect) { mutableStateOf(IntSize.Zero) }
                            Box(
                                modifier = Modifier
                                    .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                                    .onSizeChanged { previewSize = it }
                                    .pointerInput(aspect, previewSize) {
                                        if (previewSize.width <= 0 || previewSize.height <= 0) {
                                            return@pointerInput
                                        }
                                        detectDragGestures(
                                            onDragStart = { dragging = true },
                                            onDragEnd = { dragging = false },
                                            onDragCancel = { dragging = false }
                                        ) { change, dragAmount ->
                                            change.consume()
                                            val s = settings ?: return@detectDragGestures
                                            val w = previewSize.width.toFloat()
                                            val h = previewSize.height.toFloat()
                                            val halfX = (renderer.lastBlockWidth / w / 2f)
                                                .takeIf { it > 0f }?.coerceAtMost(0.45f)
                                            val halfY = (renderer.lastBlockHeight / h / 2f)
                                                .takeIf { it > 0f }?.coerceAtMost(0.45f)
                                            val marginX = (halfX ?: 0.06f) + 0.01f
                                            val marginY = (halfY ?: 0.08f) + 0.01f
                                            val nextX = (s.xFraction + dragAmount.x / w)
                                                .coerceIn(marginX, 1f - marginX)
                                            val nextY = (s.yFraction + dragAmount.y / h)
                                                .coerceIn(marginY, 1f - marginY)
                                            settings = s.copy(xFraction = nextX, yFraction = nextY)
                                        }
                                    }
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize()
                                )
                                WatermarkOverlay(
                                    spec = spec,
                                    renderer = renderer,
                                    highlight = dragging,
                                    imageAspect = aspect,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            CircularProgressIndicator(color = Accent)
                        }
                    }
                }
            }

            // 底部操作条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (picking) {
                        "保存会另存一张新照片，原图保持不变。"
                    } else {
                        "保存会覆盖相册成片，原图保持不变，可再次编辑。"
                    },
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = editedSpec != null && !saving,
                    onClick = { sheetOpen = true }
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_watermark),
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Accent),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("水印设置", color = Accent)
                }
            }
        }

        // 与拍摄页一致的水印设置面板（不展示模板快捷区，避免改动拍摄侧全局设置）
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 横屏整页高度很小：收紧面板高度，顶部保留可见的预览区域
            val panelMaxHeight = minOf(520.dp, maxHeight * 0.7f)
            AnimatedVisibility(
                visible = sheetOpen && current != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (current != null) {
                    WatermarkEditPanel(
                        settings = current,
                        presets = emptyList<WatermarkPreset>(),
                        activePresetId = null,
                        locationDenied = false,
                        locating = false,
                        onChange = { settings = it },
                        onApplyPreset = { settings = it.settings },
                        onManageTemplates = {},
                        onOpenLocationSettings = {},
                        onDone = { sheetOpen = false },
                        showPresets = false,
                        maxHeight = panelMaxHeight,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                    )
                }
            }
        }

        if (saving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (picking) "正在保存新照片…" else "正在更新水印…",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text(if (picking) "保存为一张新照片？" else "覆盖这张照片？") },
            text = {
                Text(
                    if (picking) {
                        "将在 Pictures/WatermarkCamera 另存一张带水印的新照片，原图不会被修改。"
                    } else {
                        "将用原始照片按新参数重新合成，覆盖相册里的成片；原图保持不变，之后还能再次编辑。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    val ready = session ?: return@TextButton
                    val spec = editedSpec ?: return@TextButton
                    saving = true
                    sheetOpen = false
                    scope.launch {
                        val ok = repository.saveEdit(ready, spec)
                        saving = false
                        if (ok) {
                            Toast.makeText(
                                context,
                                if (picking) "已保存新的水印照片" else "已更新水印",
                                Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        } else {
                            Toast.makeText(
                                context,
                                if (picking) {
                                    "保存失败：照片可能过大或存储空间不足，原图未改动"
                                } else {
                                    "保存失败，原照片未改动"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) { Text(if (picking) "保存" else "覆盖保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSave = false }) { Text("取消") }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次修改？") },
            text = { Text("尚未保存，退出不会改动任何文件。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("放弃", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            }
        )
    }
}

/** 快照 → 可编辑设置：只恢复可见性/样式/位置等参数，文本内容仍以快照为准。 */
private fun WatermarkSpec.toEditSettings(): WatermarkSettings {
    val defaults = WatermarkSettings()
    return WatermarkSettings(
        template = if (style == WatermarkStyle.COMPACT) {
            WatermarkTemplate.COMPACT
        } else {
            WatermarkTemplate.TIME_LOCATION
        },
        showTime = timeText != null,
        showDate = dateText != null || weekdayText != null,
        showWeather = weatherText != null,
        showLocation = locationText != null,
        showCoordinates = coordinatesText != null,
        showCustom = customText != null,
        customText = customText ?: defaults.customText,
        titleSizeFraction = titleSizeFraction,
        colorArgb = colorArgb,
        alpha = alpha,
        coordinateBackdropColorArgb = coordinateBackdropColorArgb,
        coordinateBackdropAlpha = coordinateBackdropAlpha,
        customBackdropColorArgb = customBackdropColorArgb,
        customBackdropAlpha = customBackdropAlpha,
        xFraction = xFraction,
        yFraction = yFraction,
        locationDetail = defaults.locationDetail
    )
}

/**
 * 设置 + 快照 → 新规格。
 * 时间/日期/星期按拍摄时间重新生成（开关能生效）；天气/地点/经纬度沿用快照文本，
 * 避免重新看照片时地点变成「未获取位置」。
 */
private fun buildEditedSpec(
    settings: WatermarkSettings,
    base: WatermarkSpec,
    timestampMillis: Long
): WatermarkSpec {
    val timeSpec = settings.toSpec(timestampMillis, null)
    return base.copy(
        timeText = timeSpec.timeText,
        dateText = timeSpec.dateText,
        weekdayText = timeSpec.weekdayText,
        weatherText = if (settings.showWeather) base.weatherText else null,
        locationText = if (settings.showLocation) base.locationText else null,
        coordinatesText = if (settings.showCoordinates) base.coordinatesText else null,
        customText = if (settings.showCustom) {
            settings.customText.trim().ifBlank { null }
        } else {
            null
        },
        xFraction = settings.xFraction.coerceIn(0f, 1f),
        yFraction = settings.yFraction.coerceIn(0f, 1f),
        titleSizeFraction = settings.titleSizeFraction,
        colorArgb = settings.colorArgb,
        alpha = settings.alpha,
        coordinateBackdropColorArgb = settings.coordinateBackdropColorArgb,
        coordinateBackdropAlpha = settings.coordinateBackdropAlpha,
        customBackdropColorArgb = settings.customBackdropColorArgb,
        customBackdropAlpha = settings.customBackdropAlpha,
        style = if (settings.template == WatermarkTemplate.COMPACT) {
            WatermarkStyle.COMPACT
        } else {
            WatermarkStyle.HERO
        }
    )
}

/** 快照丢失时的兜底：只按拍摄时间恢复时间/日期，地点等无法复原的行保持关闭。 */
private fun fallbackSpec(timestampMillis: Long): WatermarkSpec =
    WatermarkSettings(
        showWeather = false,
        showLocation = false,
        showCoordinates = false,
        showCustom = false
    ).toSpec(timestampMillis, null)

/** 预览取图：按 EXIF orientation 旋正，外部照片（可能横着存）在预览里也是正的。 */
private fun loadSampledUpright(file: java.io.File, maxDimension: Int): Bitmap? {
    val bitmap = loadSampledFile(file, maxDimension) ?: return null
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val rotated = runCatching {
        val matrix = Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrNull() ?: return bitmap
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

private fun loadSampledFile(file: java.io.File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
