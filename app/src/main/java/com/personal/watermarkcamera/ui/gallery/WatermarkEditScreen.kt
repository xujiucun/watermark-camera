package com.personal.watermarkcamera.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.data.EditSource
import com.personal.watermarkcamera.data.PhotoItem
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
 * 图片页「重新编辑水印」独立页面。
 *
 * - 初始参数来自该照片保存的 [WatermarkSpec] 快照（时间/地点等文本不再重新取，保持原样）；
 * - 预览用私有原图 + 当前参数实时重绘，与成片走同一个 [WatermarkRenderer]；
 * - 只有点「保存」并确认后才写文件：用原图重新合成并覆盖相册里的同一条成片，原图不动；
 * - 未保存退出不会改动任何文件。
 */
@Composable
fun WatermarkEditScreen(
    repository: PhotoRepository,
    renderer: WatermarkRenderer,
    item: PhotoItem,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var source by remember(item.uri) { mutableStateOf<EditSource?>(null) }
    var loaded by remember(item.uri) { mutableStateOf(false) }
    var settings by remember(item.uri) { mutableStateOf<WatermarkSettings?>(null) }
    var initialSettings by remember(item.uri) { mutableStateOf<WatermarkSettings?>(null) }
    var baseSpec by remember(item.uri) { mutableStateOf<WatermarkSpec?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val loadedSource = repository.loadEditSource(item)
        source = loadedSource
        if (loadedSource != null) {
            val base = loadedSource.spec ?: fallbackSpec(item.timestampMillis)
            baseSpec = base
            val initial = base.toEditSettings()
            settings = initial
            initialSettings = initial
        }
        loaded = true
    }

    val dirty = settings != null && initialSettings != null && settings != initialSettings

    BackHandler {
        when {
            sheetOpen -> sheetOpen = false
            dirty -> confirmDiscard = true
            else -> onBack()
        }
    }

    val current = settings
    val currentBase = baseSpec
    val editedSpec = if (current != null && currentBase != null) {
        buildEditedSpec(current, currentBase, item.timestampMillis)
    } else {
        null
    }
    val image by produceState<Bitmap?>(initialValue = null, source?.original?.absolutePath) {
        val file = source?.original ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { loadSampledFile(file, maxDimension = 1600) }.getOrNull()
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
                    text = "编辑水印",
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
                    source == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "这张没有原始照片，无法重新编辑",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "原始照片可能已被清理；相册成片仍然保留。",
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
                    text = "保存会覆盖相册成片，原图保持不变，可再次编辑。",
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
                        text = "正在更新水印…",
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
            title = { Text("覆盖这张照片？") },
            text = {
                Text("将用原始照片按新参数重新合成，覆盖相册里的成片；原图保持不变，之后还能再次编辑。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    val spec = editedSpec ?: return@TextButton
                    saving = true
                    sheetOpen = false
                    scope.launch {
                        val ok = repository.replaceWatermark(item, spec)
                        saving = false
                        if (ok) {
                            Toast.makeText(context, "已更新水印", Toast.LENGTH_SHORT).show()
                            onSaved()
                        } else {
                            Toast.makeText(
                                context,
                                "保存失败，原照片未改动",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) { Text("覆盖保存", color = Accent) }
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
