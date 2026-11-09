package com.personal.watermarkcamera.ui.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.personal.watermarkcamera.AppContainer
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.data.FlashMode
import com.personal.watermarkcamera.domain.CaptureDirection
import com.personal.watermarkcamera.domain.OrientationStabilizer
import com.personal.watermarkcamera.ui.StatusBarStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

@Composable
fun CaptureScreen(container: AppContainer, onOpenTemplates: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    if (granted) {
        CameraContent(container, onOpenTemplates)
    } else {
        PermissionGate(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun CameraContent(container: AppContainer, onOpenTemplates: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 页面固定竖屏（与参考的水印相机一致），横屏时只把预览与成片里的水印「横置」。
    // 锁定竖屏后 display.rotation 恒为 0，物理方向只能从重力传感器读；
    // 判定走 domain 层的滞回 + 驻留状态机，避免在边界抖动时反复切向、反复重建 CameraX。
    val stabilizer = remember { OrientationStabilizer() }
    var direction by remember { mutableStateOf(CaptureDirection.PORTRAIT) }
    var pendingCommitAt by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                val angle = if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    null
                } else {
                    orientation.toFloat()
                }
                stabilizer.update(angle, SystemClock.uptimeMillis())?.let { direction = it }
                pendingCommitAt = stabilizer.pendingCommitAtMillis
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    // 手机静止后传感器不再回调，这里在驻留到期时补一次提交
    LaunchedEffect(pendingCommitAt) {
        val at = pendingCommitAt ?: return@LaunchedEffect
        delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        stabilizer.tick(SystemClock.uptimeMillis())?.let { direction = it }
        pendingCommitAt = stabilizer.pendingCommitAtMillis
    }
    // 预览区始终是竖屏 3:4 画幅；横屏时 4:3 的画面连同水印一起旋转后正好铺满该画幅。
    val previewAspect = 3f / 4f
    // 竖屏页里把画面转正所需的角度（View.rotation 以顺时针为正）。
    // 数值与 Surface.ROTATION_* 对应：设备左/右侧朝上时，系统本会把整页内容顺时针转
    // 90°/270°；页面锁竖屏后只把预览按同一角度转，用户在横屏观看时画面才是正的。
    val previewRotation = when (direction) {
        CaptureDirection.LANDSCAPE_LEFT -> 270
        CaptureDirection.LANDSCAPE_RIGHT -> 90
        CaptureDirection.PORTRAIT -> 0
    }
    // CameraX 的 targetRotation（决定成片 EXIF 方向），与预览旋转同源
    val deviceRotation = when (direction) {
        CaptureDirection.LANDSCAPE_LEFT -> Surface.ROTATION_270
        CaptureDirection.LANDSCAPE_RIGHT -> Surface.ROTATION_90
        CaptureDirection.PORTRAIT -> Surface.ROTATION_0
    }
    val scope = rememberCoroutineScope()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var bindAttempt by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("正在生成水印照片…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    // 旋转会重建 Activity：这两个状态用 rememberSaveable 保留，避免弹窗被意外关闭
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    // 最近一次应用的自定义模板，用于「更新当前模板」的覆盖目标
    var activePresetId by rememberSaveable { mutableStateOf<String?>(null) }

    // 弹窗打开时，返回键先关弹窗，不直接退出拍摄页
    BackHandler(enabled = sheetOpen) { sheetOpen = false }
    var previewBoxSize by remember { mutableStateOf(IntSize.Zero) }

    val session = remember { CameraSession(context, lifecycleOwner) }
    DisposableEffect(session) {
        onDispose { session.release() }
    }

    val zoomInfo by session.zoomInfo.collectAsState()
    val flashAvailable by session.flashAvailable.collectAsState()
    // 当前镜头是否支持点按对焦（固定焦前摄为 false，不显示对焦框）
    val focusSupported by session.focusSupported.collectAsState()
    // 相机被系统禁用/被占用时 CameraX 会异步报错，这里把它直接呈现在预览区上方
    val runtimeCameraError by session.cameraError.collectAsState()
    // 闪光档位来自 App 级设置：重启保留，点击循环切换并立即写盘
    val flashMode = container.appSettings.flashMode
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    val minZoom = zoomInfo?.minRatio ?: 1f
    val maxZoom = zoomInfo?.maxRatio ?: 1f

    // 水印参数：与设置页共用同一份 Compose 状态，改动即写盘（拖动后立刻持久化）
    val settings = container.settingsStore.settings

    // 本次手势是否出现过第二根手指：双指手势只缩放，不再拖水印
    var multiTouch by remember { mutableStateOf(false) }

    // 本次手势的第一根手指是否落在水印块内：只有块内按下才允许拖动水印
    var gestureStartedOnWatermark by remember { mutableStateOf(false) }

    // 拖动时的描边高亮：最后一次移动后 ~320ms 自动恢复（视为松手）
    var dragging by remember { mutableStateOf(false) }
    var dragPulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(dragPulse) {
        if (dragPulse > 0) {
            delay(320)
            dragging = false
        }
    }

    // 点按对焦：焦点位置（预览手势层坐标）+ 约 1.6s 的淡入/淡出动画，不长期挂着
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusPulse by remember { mutableIntStateOf(0) }
    val focusAlpha = remember { Animatable(0f) }
    LaunchedEffect(focusPulse) {
        if (focusPulse > 0) {
            focusAlpha.snapTo(0f)
            focusAlpha.animateTo(1f, tween(160))
            delay(900)
            focusAlpha.animateTo(0f, tween(550))
            focusPoint = null
        }
    }

    // 定位：每次进入拍摄页都检查权限（应对「仅本次允许」）
    var locationGranted by remember { mutableStateOf(container.locationProvider.hasPermission()) }
    var address by remember { mutableStateOf(container.locationProvider.cached()) }
    var locating by remember { mutableStateOf(false) }
    var locationRefresh by remember { mutableIntStateOf(0) }
    var requestedLocation by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationGranted = result.values.any { it }
        locationRefresh++
    }

    LaunchedEffect(Unit) {
        if (!locationGranted && !requestedLocation) {
            requestedLocation = true
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(locationGranted, locationRefresh) {
        if (locationGranted) {
            locating = true
            address = container.locationProvider.fetch()
            locating = false
        } else {
            address = null
        }
    }

    // 从系统设置回来时重新检查权限并刷新地址；闪光只在快门瞬间触发，无需在后台常亮
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = container.locationProvider.hasPermission()
                locationRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 预览水印每秒刷新一次，避免每帧重组
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000L - nowMillis % 1000L)
        }
    }
    val previewSpec = remember(nowMillis, settings, address, locating) {
        settings.toSpec(nowMillis, address, locating)
    }

    // 只在「真正的相机重建」场景重绑：首次出图、切前后摄、失败重试。
    // 方向变化不在其中——页面锁竖屏，预览与 overlay 都是自己旋转的，
    // 重绑会销毁 PreviewView 的 surface，切换瞬间出现黑帧。
    LaunchedEffect(previewView, lensFacing, bindAttempt) {
        val view = previewView ?: return@LaunchedEffect
        withFrameNanos { }
        cameraError = null
        try {
            session.bind(
                surfaceSupplier = view.surfaceProvider,
                lens = lensFacing,
                rotation = deviceRotation,
                flashMode = flashMode
            )
            zoomRatio = 1f
            session.setZoomRatio(1f)
        } catch (t: Throwable) {
            cameraError = t.message ?: t.javaClass.simpleName
        }
    }

    // 方向变化：只更新 use case 的 targetRotation（成片 EXIF 方向），不重建会话
    LaunchedEffect(direction) {
        session.setTargetRotation(deviceRotation)
    }

    // zoomState 到达后按当前摄像头的实际范围钳制
    LaunchedEffect(zoomInfo?.minRatio, zoomInfo?.maxRatio) {
        val info = zoomInfo ?: return@LaunchedEffect
        val clamped = zoomRatio.coerceIn(info.minRatio, info.maxRatio)
        if (clamped != zoomRatio) {
            zoomRatio = clamped
            session.setZoomRatio(clamped)
        }
    }

    LaunchedEffect(success) {
        if (success) {
            delay(1800)
            success = false
        }
    }

    fun shoot() {
        if (busy) return
        // 相机不可用时不再进入拍摄流程，避免卡在「正在拍摄」
        if (runtimeCameraError != null) {
            Toast.makeText(context, "相机不可用，请先按提示恢复相机", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        success = false
        errorMessage = null
        busyText = "正在拍摄…"
        scope.launch {
            var shot: CapturedPhoto? = null
            var composedFile: File? = null
            var originalFile: File? = null
            try {
                val captured = session.takePicture()
                shot = captured
                busyText = "正在生成水印照片…"

                val spec = settings.toSpec(captured.timestampMillis, address, locating)
                val result = withContext(Dispatchers.Default) {
                    container.photoComposer.compose(
                        source = captured.file,
                        spec = spec,
                        mirrorHorizontally = captured.mirrorHorizontally
                    )
                }
                composedFile = result.watermarked
                originalFile = result.original

                container.photoRepository.save(
                    watermarked = result.watermarked,
                    original = result.original,
                    timestampMillis = captured.timestampMillis,
                    spec = spec
                )
                success = true
            } catch (t: Throwable) {
                errorMessage = "拍摄失败：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                shot?.file?.delete()
                composedFile?.delete()
                originalFile?.delete()
                busy = false
            }
        }
    }

    fun openLocationSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }
    }

    // 拍摄页始终黑底，状态栏固定浅色图标（其他页面跟随主题）
    StatusBarStyle(lightIcons = false, color = Color.Black)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // 3:4 画面在竖屏手机上受宽度限制（1080×1440 已是上限），剩余高度由整列居中吸收：
        // 画面上下各留一小段黑边，既不把留白全堆在滑杆上方，也不会让快门悬在半空。
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            // 预览区固定为竖屏 3:4 画幅：竖屏直接铺满，横屏把 4:3 画面转 90° 后正好填满同一画幅。
            // 高度直接由宽度按 3:4 推导（而不是 weight 撑满），避免画面上下各留一条黑边，
            // 也让倍率滑杆紧贴画面下沿，收紧「预览 → 滑杆」的多余留白。
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewAspect)
            ) {
                val imageBounds = imageRect(maxWidth.value, maxHeight.value, previewAspect)
                val rotated = previewRotation % 180 != 0
                // 旋转后边界必须是 3:4：竖屏直接取 3:4 矩形；横屏用正方形容器，让旋转后的画面刚好铺满
                val previewWidth = if (rotated) imageBounds.height else imageBounds.width
                val previewHeight = imageBounds.height
                AndroidView(
                    factory = { ctx -> RotatingPreviewContainer(ctx) },
                    modifier = Modifier
                        .align(Alignment.Center)
                        // requiredSize：正方形容器会比预览区宽，必须绕开父级约束
                        .requiredSize(width = previewWidth.dp, height = previewHeight.dp),
                    update = { previewContainer ->
                        previewContainer.boxAspect = previewAspect
                        previewContainer.rotationDegrees = previewRotation
                        previewView = previewContainer.previewView
                    }
                )

                WatermarkOverlay(
                    spec = previewSpec,
                    renderer = container.renderer,
                    imageAspect = previewAspect,
                    contentRotation = previewRotation,
                    highlight = dragging,
                    modifier = Modifier.fillMaxSize()
                )

                // 点按对焦框：白色四角括号（类似系统相机取景框，中间留空），约 1.6s 淡入淡出，
                // 只在本次点击位置短暂显示；白色线条后垫一层半透明黑边，浅色画面也看得清
                focusPoint?.let { point ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val alpha = focusAlpha.value
                        if (alpha <= 0.01f) return@Canvas
                        val half = 34.dp.toPx()
                        val corner = 14.dp.toPx()
                        val stroke = 1.8.dp.toPx()
                        // 贴边点击时把框整体收进画面内，避免四个角被预览边界裁掉
                        val bounds = imageRect(size.width, size.height, previewAspect)
                        val inset = half + 2.dp.toPx()
                        val center = if (bounds.width > inset * 2 && bounds.height > inset * 2) {
                            Offset(
                                point.x.coerceIn(bounds.left + inset, bounds.right - inset),
                                point.y.coerceIn(bounds.top + inset, bounds.bottom - inset)
                            )
                        } else {
                            point
                        }
                        val shadow = Color.Black.copy(alpha = 0.45f * alpha)
                        val line = Color.White.copy(alpha = alpha)
                        val corners = listOf(
                            Offset(center.x - half, center.y - half) to (1f to 1f),
                            Offset(center.x + half, center.y - half) to (-1f to 1f),
                            Offset(center.x - half, center.y + half) to (1f to -1f),
                            Offset(center.x + half, center.y + half) to (-1f to -1f)
                        )
                        // 先画略粗的阴影，再压上纯白细线
                        for ((start, dir) in corners) {
                            drawLine(
                                color = shadow,
                                start = start,
                                end = Offset(start.x + dir.first * corner, start.y),
                                strokeWidth = stroke + 1.6.dp.toPx()
                            )
                            drawLine(
                                color = shadow,
                                start = start,
                                end = Offset(start.x, start.y + dir.second * corner),
                                strokeWidth = stroke + 1.6.dp.toPx()
                            )
                        }
                        for ((start, dir) in corners) {
                            drawLine(
                                color = line,
                                start = start,
                                end = Offset(start.x + dir.first * corner, start.y),
                                strokeWidth = stroke
                            )
                            drawLine(
                                color = line,
                                start = start,
                                end = Offset(start.x, start.y + dir.second * corner),
                                strokeWidth = stroke
                            )
                        }
                    }
                }

                // 拖动命中判定：用渲染层实际绘制的水印块 bbox，而不是整块预览。
                // 10dp 容差让手指不必精准压到边缘；块外按下完全不动水印，也不吞手势。
                val hitTolerancePx = with(LocalDensity.current) { 10.dp.toPx() }

                fun watermarkHitRect(): Rect? {
                    val boxWidth = previewBoxSize.width.toFloat()
                    val boxHeight = previewBoxSize.height.toFloat()
                    if (boxWidth <= 0f || boxHeight <= 0f) return null
                    val image = imageRect(boxWidth, boxHeight, previewAspect)
                    val blockWidth = container.renderer.lastBlockWidth
                    val blockHeight = container.renderer.lastBlockHeight
                    if (image.width <= 0f || blockWidth <= 0f || blockHeight <= 0f) return null

                    // 命中区与 WatermarkOverlay 用同一套变换：横屏时画面坐标要逆旋转回屏幕坐标
                    val rotated = previewRotation % 180 != 0
                    val logicalWidth = if (rotated) image.height else image.width
                    val logicalHeight = if (rotated) image.width else image.height
                    val blockCenterX = container.renderer.lastBlockLeft + blockWidth / 2f
                    val blockCenterY = container.renderer.lastBlockTop + blockHeight / 2f
                    val relX = blockCenterX - logicalWidth / 2f
                    val relY = blockCenterY - logicalHeight / 2f
                    val screenDx = when (previewRotation) {
                        90 -> -relY
                        270 -> relY
                        180 -> -relX
                        else -> relX
                    }
                    val screenDy = when (previewRotation) {
                        90 -> relX
                        270 -> -relX
                        180 -> -relY
                        else -> relY
                    }
                    val halfWidth = (if (rotated) blockHeight else blockWidth) / 2f
                    val halfHeight = (if (rotated) blockWidth else blockHeight) / 2f
                    val centerX = image.center.x + screenDx
                    val centerY = image.center.y + screenDy
                    return Rect(
                        left = centerX - halfWidth - hitTolerancePx,
                        top = centerY - halfHeight - hitTolerancePx,
                        right = centerX + halfWidth + hitTolerancePx,
                        bottom = centerY + halfHeight + hitTolerancePx
                    )
                }

                // 点击/拖动的判定阈值（与拖动水印的 touch slop 分开，点击要求基本不动）
                val tapSlopPx = with(LocalDensity.current) { 8.dp.toPx() }

                /**
                 * 屏幕坐标 → PreviewView 自身未旋转的本地坐标。
                 * 与拖动水印共用同一套「屏幕坐标 → 画面坐标」逆变换：
                 * 90/270 时先把位移绕画面中心逆时针转回去，再换算到 PreviewView 的测量尺寸。
                 * meteringPointFactory 会在此基础上再处理 targetRotation 与前摄镜像。
                 */
                fun previewLocalPoint(screen: Offset): Offset? {
                    val view = previewView ?: return null
                    val boxWidth = previewBoxSize.width.toFloat()
                    val boxHeight = previewBoxSize.height.toFloat()
                    if (boxWidth <= 0f || boxHeight <= 0f) return null
                    val image = imageRect(boxWidth, boxHeight, previewAspect)
                    return previewLocalPointForScreen(
                        screen = screen,
                        image = image,
                        rotation = previewRotation,
                        viewWidth = view.width.toFloat(),
                        viewHeight = view.height.toFloat()
                    )
                }

                fun triggerFocus(screen: Offset) {
                    if (busy) return
                    if (!focusSupported) {
                        Toast.makeText(context, "当前摄像头不支持点按对焦", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val view = previewView ?: return
                    val local = previewLocalPoint(screen) ?: return
                    val point = runCatching {
                        view.meteringPointFactory.createPoint(local.x, local.y)
                    }.getOrNull() ?: return
                    focusPoint = screen
                    focusPulse++
                    session.focus(point) { ok ->
                        if (!ok) {
                            Toast.makeText(context, "对焦未完成，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 手势层：按在水印块内才能单指拖水印，双指始终只缩放；两者互斥，不会误触
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { previewBoxSize = it }
                        // 记录第一根手指落点、手指数量与是否移动：
                        // 块内按下才拖水印；块外「按下即抬起」才聚焦；出现第二指本次只缩放
                        .pointerInput(
                            previewAspect,
                            previewRotation,
                            previewBoxSize,
                            previewView,
                            focusSupported,
                            busy
                        ) {
                            awaitPointerEventScope {
                                var gestureActive = false
                                var downPosition: Offset? = null
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    val first = event.changes.firstOrNull { it.pressed }
                                    if (!gestureActive && pressed > 0) {
                                        gestureActive = true
                                        downPosition = first?.position
                                        moved = false
                                        gestureStartedOnWatermark = first
                                            ?.let { watermarkHitRect()?.contains(it.position) == true }
                                            ?: false
                                    } else if (gestureActive && pressed > 0 && !moved) {
                                        val down = downPosition
                                        if (first != null && down != null) {
                                            moved = (first.position - down).getDistance() > tapSlopPx
                                        }
                                    }
                                    if (pressed >= 2 && !multiTouch) {
                                        multiTouch = true
                                        // 第二根手指出现后本次手势只缩放，松手前不再拖水印
                                        gestureStartedOnWatermark = false
                                    }
                                    if (pressed == 0) {
                                        // 点击（未移动、未落在水印块、未出现第二指）对焦点位置聚焦
                                        if (gestureActive && !moved &&
                                            !gestureStartedOnWatermark && !multiTouch
                                        ) {
                                            downPosition?.let { triggerFocus(it) }
                                        }
                                        gestureActive = false
                                        downPosition = null
                                        moved = false
                                        gestureStartedOnWatermark = false
                                        if (multiTouch) multiTouch = false
                                        dragging = false
                                        // 松手时把拖动后的位置写盘一次
                                        container.settingsStore.persist()
                                    }
                                }
                            }
                        }
                        .pointerInput(minZoom, maxZoom, previewAspect, previewBoxSize, previewRotation) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    val next = (zoomRatio * zoom).coerceIn(minZoom, maxZoom)
                                    if (next != zoomRatio) {
                                        zoomRatio = next
                                        session.setZoomRatio(next)
                                    }
                                } else if (!multiTouch && gestureStartedOnWatermark &&
                                    (pan.x != 0f || pan.y != 0f)
                                ) {
                                    val rect = imageRect(
                                        previewBoxSize.width.toFloat(),
                                        previewBoxSize.height.toFloat(),
                                        previewAspect
                                    )
                                    if (rect.width > 0f && rect.height > 0f) {
                                        // 画面横置时水印坐标系也被旋转，屏幕位移要先转回画面坐标系
                                        val rotated = previewRotation % 180 != 0
                                        val logicalWidth = if (rotated) rect.height else rect.width
                                        val logicalHeight = if (rotated) rect.width else rect.height
                                        val dx = when (previewRotation) {
                                            90 -> pan.y
                                            270 -> -pan.y
                                            180 -> -pan.x
                                            else -> pan.x
                                        }
                                        val dy = when (previewRotation) {
                                            90 -> -pan.x
                                            270 -> pan.x
                                            180 -> -pan.y
                                            else -> pan.y
                                        }
                                        // 按水印块的实际尺寸做边界钳制，避免整块被拖出画面
                                        val halfX = (container.renderer.lastBlockWidth / logicalWidth / 2f)
                                            .takeIf { it > 0f }?.coerceAtMost(0.45f)
                                        val halfY = (container.renderer.lastBlockHeight / logicalHeight / 2f)
                                            .takeIf { it > 0f }?.coerceAtMost(0.45f)
                                        val marginX = (halfX ?: 0.06f) + 0.01f
                                        val marginY = (halfY ?: 0.08f) + 0.01f
                                        val minX = marginX
                                        val maxX = 1f - marginX
                                        val minY = marginY
                                        val maxY = 1f - marginY
                                        val current = container.settingsStore.settings
                                        val nextX = (current.xFraction + dx / logicalWidth)
                                            .let { if (minX <= maxX) it.coerceIn(minX, maxX) else 0.5f }
                                        val nextY = (current.yFraction + dy / logicalHeight)
                                            .let { if (minY <= maxY) it.coerceIn(minY, maxY) else 0.5f }
                                        container.settingsStore.update(
                                            current.copy(xFraction = nextX, yFraction = nextY),
                                            persist = false
                                        )
                                        dragging = true
                                        dragPulse++
                                    }
                                }
                            }
                        }
                )

                // 相机不可用（系统策略禁用 / 被其它应用占用 / 绑定失败）时，给出明确文案与重试入口，
                // 不能停在黑屏或无限 loading
                (runtimeCameraError ?: cameraError)?.let { message ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "相机不可用：$message",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { bindAttempt++ }) {
                            Text("重试")
                        }
                        TextButton(onClick = { openLocationSettings() }) {
                            Text("打开系统设置")
                        }
                    }
                }

                // 闪光灯三档：自动 / 强制闪光 / 关闭；无闪光灯的前摄显示置灰的「无闪光灯」
                FlashButton(
                    mode = flashMode,
                    available = flashAvailable,
                    enabled = !busy,
                    onClick = {
                        if (!flashAvailable) {
                            Toast.makeText(
                                context,
                                "当前摄像头没有闪光灯，切换后摄后再试",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val next = flashMode.next()
                            container.appSettings.updateFlashMode(next)
                            session.setFlashMode(next)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 14.dp)
                )

                // 当前倍率；点击回到 1x。与左上闪光灯共用同一枚胶囊样式，高度/圆角/描边对齐
                CapturePill(
                    onClick = {
                        val reset = 1f.coerceIn(minZoom, maxZoom)
                        zoomRatio = reset
                        session.setZoomRatio(reset)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 14.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fx", zoomRatio),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 只收上方：滑杆紧贴画面下沿；下方留一点，快门不至于贴住底部导航
                    .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                errorMessage?.let { text ->
                    Text(
                        text = text,
                        color = Color(0xFFFF8A80),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // 快门上方空余区：倍率滑杆。镜头不支持变焦时只给说明，不显示无效控件
                if (zoomInfo != null) {
                    ZoomControl(
                        ratio = zoomRatio,
                        minRatio = minZoom,
                        maxRatio = maxZoom,
                        onChange = { value ->
                            val next = value.coerceIn(minZoom, maxZoom)
                            if (next != zoomRatio) {
                                zoomRatio = next
                                session.setZoomRatio(next)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LensSwitchButton(
                        isFront = lensFacing == CameraSelector.LENS_FACING_FRONT,
                        enabled = !busy,
                        onClick = {
                            // 切镜头：重置为「自动」，新摄像头能力在 bind 后重新查询
                            container.appSettings.updateFlashMode(FlashMode.AUTO)
                            lensFacing =
                                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                    CameraSelector.LENS_FACING_BACK
                                } else {
                                    CameraSelector.LENS_FACING_FRONT
                                }
                            zoomRatio = 1f
                        }
                    )

                    ShutterButton(busy = busy, onClick = { shoot() })

                    WatermarkToolButton(
                        open = sheetOpen,
                        enabled = !busy,
                        onClick = { sheetOpen = true }
                    )
                }
            }
        }

        // 水印设置浮层：遮罩只做视觉压暗，不拦截触摸，弹窗外的预览仍可单指拖动水印
        if (sheetOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
            )
        }
        // 页面固定竖屏，弹窗高度上限直接沿用竖屏值；横屏时整页只是被「横置」，版式不变。
        AnimatedVisibility(
            visible = sheetOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            WatermarkEditPanel(
                settings = settings,
                presets = container.presetStore.presets,
                activePresetId = activePresetId,
                locationDenied = !locationGranted,
                locating = locating,
                onChange = { container.settingsStore.update(it) },
                onApplyPreset = { preset ->
                    container.settingsStore.update(preset.settings)
                    activePresetId = preset.id
                },
                onManageTemplates = {
                    // 去独立页面管理模板；先收起弹窗，返回时回到拍摄页
                    sheetOpen = false
                    onOpenTemplates()
                },
                // 把当前设置的完整快照存成模板；不改当前拍摄水印
                onSaveAsTemplate = { name ->
                    val saved = container.presetStore.saveNew(name, settings)
                    activePresetId = saved.id
                    Toast.makeText(context, "已保存为模板：${saved.name}", Toast.LENGTH_SHORT).show()
                },
                defaultTemplateName = container.presetStore.defaultName(),
                onOpenLocationSettings = { openLocationSettings() },
                onDone = { sheetOpen = false },
                maxHeight = 520.dp,
                modifier = Modifier
                    // 只拦住落在弹窗上的手势，避免拖动弹窗时误拖预览里的水印
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = {}
                    )
            )
        }

        // 合成遮罩：拦截触摸，禁止重复按快门
        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.ic_compose_progress),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color(0xFFFF6B35)),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = Color(0xFFFF6B35),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = busyText,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }

        // 短暂成功提示
        if (success && !busy) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "已保存到相册",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

/**
 * 屏幕坐标 → PreviewView 未旋转本地坐标（点按对焦用）。
 *
 * [image] 是画面在屏幕上的实际矩形，[rotation] 是 PreviewView 的 View.rotation（0/90/270）。
 * 与拖动水印共用同一套逆变换：90 时屏幕位移 (dx,dy) 对应本地 (dy,-dx)，
 * 270 时对应 (-dy,dx)；之后按 PreviewView 的测量尺寸平移到本地坐标。
 * 之后交给 PreviewView 的 meteringPointFactory，由它处理 targetRotation 与前后摄镜像。
 */
internal fun previewLocalPointForScreen(
    screen: Offset,
    image: Rect,
    rotation: Int,
    viewWidth: Float,
    viewHeight: Float
): Offset? {
    if (viewWidth <= 0f || viewHeight <= 0f) return null
    if (image.width <= 0f || image.height <= 0f || !image.contains(screen)) return null
    val dx = screen.x - image.center.x
    val dy = screen.y - image.center.y
    val halfWidth = viewWidth / 2f
    val halfHeight = viewHeight / 2f
    return when (rotation) {
        90 -> Offset(halfWidth + dy, halfHeight - dx)
        270 -> Offset(halfWidth - dy, halfHeight + dx)
        else -> Offset(halfWidth + dx, halfHeight + dy)
    }
}

/**
 * 倍率控制条：左侧滑杆（1x/2x/3x/5x 等刻度 + 吸附 + 点击直达），右侧当前倍率。
 * 前摄与后摄各自按 CameraX 上报的 min/max 钳制；不支持变焦时只显示说明。
 */
@Composable
private fun ZoomControl(
    ratio: Float,
    minRatio: Float,
    maxRatio: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val zoomable = maxRatio - minRatio >= 0.01f
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (zoomable) {
            ZoomSlider(
                ratio = ratio,
                minRatio = minRatio,
                maxRatio = maxRatio,
                onChange = onChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Text(
                text = "当前镜头不支持变焦",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
        }
        // 当前倍率数值；点击回到 1x（范围不含 1x 时停在最近的合法值）。
        // 外层宽度按「最大倍率文本」固定（用同款文字做透明占位），否则 1.0x → 10.0x
        // 时文字变宽会把左侧滑杆挤窄，拖动过程中轨道长度跟着变，观感像抖动。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33000000))
                .clickable(enabled = zoomable) {
                    onChange(1f.coerceIn(minRatio, maxRatio))
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // 透明占位：只参与测量，不显示也不读屏
            Text(
                text = String.format(Locale.US, "%.1fx", maxRatio),
                color = Color.Transparent,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { }
            )
            Text(
                text = String.format(Locale.US, "%.1fx", ratio),
                color = if (zoomable) Color.White else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ZoomSlider(
    ratio: Float,
    minRatio: Float,
    maxRatio: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val span = (maxRatio - minRatio).coerceAtLeast(0.0001f)
    val stops = remember(minRatio, maxRatio) {
        listOf(0.5f, 1f, 2f, 3f, 5f, 10f)
            .filter { it >= minRatio && it <= maxRatio }
            .distinct()
    }
    val accent = Color(0xFFFF6B35)
    val trackColor = Color.White.copy(alpha = 0.32f)
    val tickColor = Color.White.copy(alpha = 0.55f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = Color.White.copy(alpha = 0.72f),
        fontSize = 9.sp
    )
    val textMeasurer = rememberTextMeasurer()
    val padding = with(LocalDensity.current) { 14.dp.toPx() }
    // 松手吸附范围取全量程的 5%，避免停在 1.97x 这类无意义数值
    val snapRange = span * 0.05f
    val tapSlop = with(LocalDensity.current) { 14.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(minRatio, maxRatio, stops, padding, snapRange, tapSlop) {
                val usable = (size.width - padding * 2).coerceAtLeast(1f)
                fun ratioAt(x: Float): Float =
                    (minRatio + ((x - padding) / usable) * span).coerceIn(minRatio, maxRatio)

                fun snapAt(value: Float): Float {
                    val nearest = stops.minByOrNull { abs(it - value) } ?: return value
                    return if (abs(nearest - value) <= snapRange) nearest else value
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // 点在档位标签附近（14dp 内）= 点击该档位，直接跳到目标倍率
                    val stopAtDown = stops.firstOrNull {
                        abs((padding + (it - minRatio) / span * usable) - down.position.x) <= tapSlop
                    }
                    onChange(stopAtDown ?: ratioAt(down.position.x))
                    var lastX = down.position.x
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position.x != lastX) {
                            lastX = change.position.x
                            change.consume()
                            onChange(ratioAt(lastX))
                        }
                    }
                    onChange(snapAt(ratioAt(lastX)))
                }
            }
    ) {
        // 轨道靠上放，把滑杆上方的空白压到最小（下方刻度文字仍完整可见）
        val trackY = size.height * 0.30f
        val left = padding
        val right = size.width - padding
        val usable = (right - left).coerceAtLeast(1f)
        fun xOf(value: Float) = left + ((value - minRatio) / span) * usable

        drawLine(trackColor, Offset(left, trackY), Offset(right, trackY), strokeWidth = 2.dp.toPx())
        drawLine(
            accent.copy(alpha = 0.9f),
            Offset(left, trackY),
            Offset(xOf(ratio), trackY),
            strokeWidth = 2.dp.toPx()
        )

        var lastLabelX = Float.NEGATIVE_INFINITY
        stops.forEach { stop ->
            val x = xOf(stop)
            drawLine(
                tickColor,
                Offset(x, trackY - 3.dp.toPx()),
                Offset(x, trackY + 3.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
            // 刻度太密时不画标签，避免糊成一片
            if (x - lastLabelX >= 22.dp.toPx()) {
                val layout = textMeasurer.measure(AnnotatedString(formatZoomStop(stop)), labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(x - layout.size.width / 2f, trackY + 8.dp.toPx())
                )
                lastLabelX = x
            }
        }

        drawCircle(accent, radius = 7.dp.toPx(), center = Offset(xOf(ratio), trackY))
        drawCircle(Color.White, radius = 2.5.dp.toPx(), center = Offset(xOf(ratio), trackY))
    }
}

private fun formatZoomStop(value: Float): String =
    String.format(Locale.US, if (abs(value - Math.round(value)) < 0.01f) "%.0fx" else "%.1fx", value)

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, if (busy) Color(0xFF9E9E9E) else Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_shutter),
            contentDescription = "拍照",
            colorFilter = ColorFilter.tint(if (busy) Color(0xFF9E9E9E) else Color.White),
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun LensSwitchButton(isFront: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0x33000000))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_camera_flip),
                contentDescription = "切换前后摄像头",
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (isFront) "前摄" else "后摄",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FlashButton(
    mode: FlashMode,
    available: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = when {
        !available -> Color(0xFF8A8A8A)
        mode == FlashMode.ON -> Color(0xFFFFC107)
        else -> Color.White
    }
    CapturePill(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Image(
            painter = painterResource(
                when {
                    !available -> R.drawable.ic_flash_off
                    mode == FlashMode.AUTO -> R.drawable.ic_flash_auto
                    mode == FlashMode.ON -> R.drawable.ic_flash_on
                    else -> R.drawable.ic_flash_off
                }
            ),
            contentDescription = if (available) "闪光灯：${mode.label}" else "无闪光灯",
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (available) mode.label else "无闪光灯",
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * 拍摄页顶部的圆角胶囊：闪光灯与倍率共用同一套高度下限、圆角、底色与描边，
 * 左右对齐、视觉权重一致；高度用下限而非固定值，界面字号放大时能随文字一起长高。
 */
@Composable
private fun CapturePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun WatermarkToolButton(open: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (open) Color(0x33FF6B35) else Color(0x33000000))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_watermark),
                contentDescription = "打开水印设置",
                colorFilter = ColorFilter.tint(if (open) Color(0xFFFF6B35) else Color.White),
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "水印",
                color = if (open) Color(0xFFFF6B35) else Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "需要相机权限才能拍摄",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) {
            Text("授予权限")
        }
    }
}

/**
 * 预览容器：按设备物理方向把 PreviewView 旋转 90°/270°。
 *
 * 竖屏时页面固定为 3:4 画幅；横屏时 4:3 的相机画面旋转后正好铺满同一画幅，
 * 画面里的水印由 [WatermarkOverlay] 按相同角度旋转，因此预览与成片方向一致。
 * 旋转后画面会超出容器，这里关闭子 View 裁剪，避免上下边被切掉。
 */
private class RotatingPreviewContainer(context: Context) : FrameLayout(context) {

    val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        // TextureView：屏幕旋转由 View 自身完成，避免 SurfaceView 的变换问题
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    /**
     * 旋转过渡用的「上一帧」遮罩。
     *
     * 3:4 与 4:3 两种画幅要铺满同一个竖屏预览框，PreviewView 的测量尺寸必须随之变化
     * （固定尺寸必然导致信箱黑边或裁切）。尺寸变化后 CameraX 的变换矩阵要晚 1~2 帧才生效，
     * 这几帧里会看到画面被压扁/错位——这就是切换时那下闪动的来源。
     * 这里把切换瞬间已有的一帧截图钉在原位置，等新画面稳定后再淡出，把这段过渡抹平。
     */
    private val coverView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }

    /** 被冻结帧对应的 PreviewView 尺寸（切换前的旧尺寸）。 */
    private var coverWidth = 0
    private var coverHeight = 0

    /** 画面旋转角度（0 / 90 / 180 / 270，顺时针为正）。 */
    var rotationDegrees: Int = 0
        set(value) {
            if (field == value) return
            // 先冻结当前帧，再改角度和尺寸：遮罩内容与切换前看到的画面完全一致
            val frozen = runCatching { previewView.bitmap }.getOrNull()
            val oldRotation = field
            field = value
            if (frozen != null) showCover(frozen, oldRotation)
            previewView.rotation = value.toFloat()
            requestLayout()
        }

    private fun showCover(frame: android.graphics.Bitmap, oldRotation: Int) {
        if (previewView.width <= 0 || previewView.height <= 0) return
        coverWidth = previewView.width
        coverHeight = previewView.height
        coverView.setImageBitmap(frame)
        coverView.rotation = oldRotation.toFloat()
        coverView.alpha = 1f
        coverView.visibility = View.VISIBLE
        coverView.animate().cancel()
        // 不透明保持 180ms（等 CameraX 变换追上），再用 60ms 淡出，总计 240ms
        coverView.animate()
            .alpha(0f)
            .setStartDelay(180)
            .setDuration(60)
            .withEndAction {
                coverView.visibility = View.GONE
                coverView.setImageDrawable(null)
            }
            .start()
        requestLayout()
    }

    /** 旋转后画幅的宽高比（固定 3:4，与叠加层的水印矩形一致）。 */
    var boxAspect: Float = 3f / 4f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        clipChildren = false
        clipToPadding = false
        // 与预览底色一致：变换瞬间露出的也是黑，不出现白/灰底
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(coverView, LayoutParams(0, 0))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = measuredWidth
        val height = measuredHeight
        if (width <= 0 || height <= 0) return
        if (rotationDegrees % 180 == 0) {
            previewView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
        } else {
            // 旋转 90° 后画面宽 = 容器高、画面高 = 容器宽；预旋转尺寸因此是「宽不变、高按 3:4 收到 75%」
            previewView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((width * boxAspect).toInt(), View.MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        if (rotationDegrees % 180 == 0) {
            previewView.layout(0, 0, width, height)
        } else {
            val childHeight = (width * boxAspect).toInt()
            val top = (height - childHeight) / 2
            previewView.layout(0, top, width, top + childHeight)
        }
        // 遮罩用旧尺寸、按容器中心摆放：新旧两种画幅都居中，旋转中心一致，看起来就是原画面没动
        if (coverView.visibility == View.VISIBLE && coverWidth > 0 && coverHeight > 0) {
            val left = (width - coverWidth) / 2
            val top = (height - coverHeight) / 2
            coverView.layout(left, top, left + coverWidth, top + coverHeight)
        }
    }
}
