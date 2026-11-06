package com.personal.watermarkcamera.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import com.personal.watermarkcamera.domain.WatermarkRenderer
import com.personal.watermarkcamera.domain.WatermarkSpec

/** 按实际图像宽高比（宽 / 高）在容器内做 FIT_CENTER，供图片页重新编辑复用。 */
fun imageRect(boxWidth: Float, boxHeight: Float, imageAspect: Float): Rect {
    if (boxWidth <= 0f || boxHeight <= 0f || imageAspect <= 0f) return Rect.Zero
    val width: Float
    val height: Float
    if (boxWidth / boxHeight > imageAspect) {
        height = boxHeight
        width = height * imageAspect
    } else {
        width = boxWidth
        height = width / imageAspect
    }
    val offsetX = (boxWidth - width) / 2f
    val offsetY = (boxHeight - height) / 2f
    return Rect(offsetX, offsetY, offsetX + width, offsetY + height)
}

/**
 * 预览层水印：在与成片相同的图像矩形内调用同一个渲染器，实现「所见即所得」。
 *
 * [imageAspect] 是「预览区画幅」的宽 / 高（拍摄页固定竖屏 3:4，图片页按原图尺寸）。
 * [contentRotation] 是画面在画幅内被旋转的角度（0/90/180/270，顺时针为正）：
 * 横屏时相机画面是 4:3 横向，旋转后铺满 3:4 画幅，水印必须用同一角度旋转，
 * 否则预览方向会和成片对不上。
 */
@Composable
fun WatermarkOverlay(
    spec: WatermarkSpec,
    renderer: WatermarkRenderer,
    imageAspect: Float,
    modifier: Modifier = Modifier,
    contentRotation: Int = 0,
    /** 拖动中给水印加描边提示；只在预览层生效，不进入成片。 */
    highlight: Boolean = false
) {
    Canvas(modifier) {
        val rect = imageRect(size.width, size.height, imageAspect)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas

        val angle = contentRotation % 360
        if (angle == 0) {
            withTransform({ translate(rect.left, rect.top) }) {
                // 与成片一致：超出图像矩形的部分要被裁掉（拖动到边缘时预览不能画到黑边上）
                clipRect(0f, 0f, rect.width, rect.height) {
                    drawIntoCanvas { canvas ->
                        renderer.render(
                            canvas.nativeCanvas,
                            rect.width.toInt(),
                            rect.height.toInt(),
                            spec,
                            highlight
                        )
                    }
                }
            }
        } else {
            // 旋转后画面坐标系是横向的：长边取画幅高度，短边取画幅宽度
            val logicalWidth = if (angle % 180 == 0) rect.width else rect.height
            val logicalHeight = if (angle % 180 == 0) rect.height else rect.width
            withTransform({
                translate(rect.center.x, rect.center.y)
                rotate(degrees = angle.toFloat(), pivot = Offset.Zero)
                translate(-logicalWidth / 2f, -logicalHeight / 2f)
            }) {
                clipRect(0f, 0f, logicalWidth, logicalHeight) {
                    drawIntoCanvas { canvas ->
                        renderer.render(
                            canvas.nativeCanvas,
                            logicalWidth.toInt(),
                            logicalHeight.toInt(),
                            spec,
                            highlight
                        )
                    }
                }
            }
        }
    }
}
