package com.personal.watermarkcamera.ui.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.personal.watermarkcamera.data.FlashMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CapturedPhoto(
    val file: File,
    val timestampMillis: Long,
    val mirrorHorizontally: Boolean
)

/** 当前摄像头的缩放状态，供界面显示倍率与钳制范围。 */
data class ZoomInfo(
    val ratio: Float,
    val minRatio: Float,
    val maxRatio: Float
)

/**
 * CameraX 绑定与拍摄。预览与拍照都锁定 4:3，保证预览矩形与成片比例一致。
 * 缩放只作用于 cameraControl，不改变预览覆盖层与成片水印的坐标系。
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var targetRotation: Int = Surface.ROTATION_0

    private var observedZoomState: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { state ->
        _zoomInfo.value = ZoomInfo(
            ratio = state.zoomRatio,
            minRatio = state.minZoomRatio,
            maxRatio = state.maxZoomRatio
        )
    }
    private val _zoomInfo = MutableStateFlow<ZoomInfo?>(null)
    val zoomInfo: StateFlow<ZoomInfo?> = _zoomInfo.asStateFlow()

    // ---- 闪光灯（ImageCapture 三档：自动 / 强制闪光 / 关闭）----
    private val _flashAvailable = MutableStateFlow(false)
    /** 当前摄像头是否有可用的闪光灯单元（前摄通常没有）。 */
    val flashAvailable: StateFlow<Boolean> = _flashAvailable.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    /** 相机的异步错误（系统策略禁用相机、相机被其他应用占用等），null 表示正常。 */
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    private val _focusSupported = MutableStateFlow(false)
    /** 当前镜头是否支持 AF+AE 点按测光（固定焦前摄为 false，界面不显示对焦框）。 */
    val focusSupported: StateFlow<Boolean> = _focusSupported.asStateFlow()

    private var observedCameraState: LiveData<CameraState>? = null
    private val cameraStateObserver = Observer<CameraState> { state ->
        // 错误只在「重新绑定/重试」时清除：CameraX 报错后紧接着还会发一个 error=null 的 CLOSED，
        // 若跟着清空，提示会一闪而过，用户看到的仍然是黑屏。
        state.error?.let { _cameraError.value = describeCameraError(it) }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bind(
        surfaceSupplier: Preview.SurfaceProvider,
        lens: Int,
        rotation: Int,
        flashMode: FlashMode
    ) {
        withContext(Dispatchers.Main) {
            val provider = awaitCameraProvider()

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val previewUseCase = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .build()
            previewUseCase.setSurfaceProvider(surfaceSupplier)

            val capture = ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val selector = CameraSelector.Builder().requireLensFacing(lens).build()

            provider.unbindAll()
            val boundCamera =
                provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, capture)

            preview = previewUseCase
            imageCapture = capture
            cameraProvider = provider
            camera = boundCamera
            lensFacing = lens
            targetRotation = rotation

            // 切换镜头后重新挂载缩放状态：不同摄像头的倍率范围可能不同
            observedZoomState?.removeObserver(zoomObserver)
            observedZoomState = boundCamera.cameraInfo.zoomState
            observedZoomState?.observe(lifecycleOwner, zoomObserver)
            _zoomInfo.value = boundCamera.cameraInfo.zoomState.value?.let { state ->
                ZoomInfo(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
            }

            // 重新查询新摄像头是否有闪光灯，并把当前档位应用到新的 ImageCapture
            _flashAvailable.value = boundCamera.cameraInfo.hasFlashUnit()
            applyFlashMode(flashMode)

            // AF 能力随镜头变化：固定焦前摄的 AF 模式只有 OFF，界面据此隐藏对焦框
            _focusSupported.value = runCatching {
                val modes = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                modes?.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF } == true
            }.getOrDefault(false)

            // 绑定成功不代表相机可用（例如系统策略禁用时会异步报错），这里持续观察相机状态
            _cameraError.value = null
            observedCameraState?.removeObserver(cameraStateObserver)
            observedCameraState = boundCamera.cameraInfo.cameraState
            observedCameraState?.let { state ->
                state.observe(lifecycleOwner, cameraStateObserver)
                state.value?.let { cameraStateObserver.onChanged(it) }
            }
        }
    }

    /**
     * 把 CameraX 的相机状态错误转成用户能看懂的文案。
     * 系统级禁用（appops=ignore、MIUI 相机开关关闭）会以 ERROR_CAMERA_DISABLED 的形式异步出现。
     */
    private fun describeCameraError(error: CameraState.StateError): String = when (error.code) {
        CameraState.ERROR_CAMERA_DISABLED -> "系统已禁用摄像头，请在系统设置中开启相机权限"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "勿扰模式已开启，系统禁止使用相机，请先关闭勿扰模式"
        CameraState.ERROR_MAX_CAMERAS_IN_USE, CameraState.ERROR_CAMERA_IN_USE ->
            "相机正被其他应用占用，请关闭其他相机应用后重试"
        CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_CAMERA_REMOVED ->
            "相机发生致命错误，重试后仍失败请重启应用"
        CameraState.ERROR_STREAM_CONFIG -> "相机输出配置失败，请重试"
        else -> "相机不可用（错误码 ${error.code}），请重试"
    }

    /** 设置闪光模式：只改 ImageCapture.flashMode，不影响预览（不做常亮）。 */
    fun setFlashMode(mode: FlashMode) {
        applyFlashMode(mode)
    }

    private fun applyFlashMode(mode: FlashMode) {
        val capture = imageCapture ?: return
        val cam = camera ?: return
        // 无闪光灯时不设置，避免调用不可用的闪光单元
        if (!cam.cameraInfo.hasFlashUnit()) return
        capture.flashMode = when (mode) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
    }

    /**
     * 方向变化时只更新两个 use case 的 targetRotation，不重建会话。
     *
     * 页面锁竖屏，方向变化只影响「画面相对重力方向怎么摆」，没有必要解绑相机；
     * 重建会话会销毁 PreviewView 的 surface，切换瞬间就会黑一下。
     */
    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    /** 只改变取景与拍摄画面，不影响预览/成片水印的归一化坐标。 */
    fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /**
     * 点按对焦：对指定测光点做一次 AF + AE，由 CameraX 在动作结束后自动回到连续自动对焦。
     * [onResult] 回调是否对焦成功（不支持/失败都会回调 false，由界面给一次轻提示）。
     */
    fun focus(point: MeteringPoint, onResult: (Boolean) -> Unit) {
        val control = camera?.cameraControl
        if (control == null) {
            onResult(false)
            return
        }
        val action = runCatching {
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                // 2s 后自动取消，避免个别机型对焦动作卡在手动模式
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()
        }.getOrNull() ?: run {
            onResult(false)
            return
        }
        runCatching {
            val future = control.startFocusAndMetering(action)
            future.addListener({
                val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                onResult(ok)
            }, ContextCompat.getMainExecutor(context))
        }.onFailure { onResult(false) }
    }

    suspend fun takePicture(): CapturedPhoto = withContext(Dispatchers.Main) {
        val capture = imageCapture ?: throw IllegalStateException("相机尚未就绪")

        // 快门瞬间的时间戳，预览与成片之间的时间差由此消除
        val timestamp = System.currentTimeMillis()
        val file = File(context.cacheDir, "capture_$timestamp.jpg")

        // 相机被系统禁用/被其它应用占用时，takePicture 的回调可能永远不来；
        // 加超时把界面从「正在拍摄」里捞出来，同时明确提示原因。
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<File> { continuation ->
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            continuation.resume(file)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }
        } ?: run {
            file.delete()
            throw IllegalStateException("相机没有响应，请检查相机是否被系统禁用或被其他应用占用")
        }

        CapturedPhoto(
            file = file,
            timestampMillis = timestamp,
            mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    fun release() {
        observedZoomState?.removeObserver(zoomObserver)
        observedZoomState = null
        observedCameraState?.removeObserver(cameraStateObserver)
        observedCameraState = null
        _cameraError.value = null
        _flashAvailable.value = false
        _focusSupported.value = false
        cameraProvider?.unbindAll()
        preview = null
        imageCapture = null
        camera = null
        _zoomInfo.value = null
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                }
            }, ContextCompat.getMainExecutor(context))
        }
}
