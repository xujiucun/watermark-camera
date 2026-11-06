package com.personal.watermarkcamera

import android.Manifest
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * 真机验证：切换到前摄 → 双指放大 → 截图预览 → 拍照 → 点击倍率复位。
 * UiAutomation 注入真实多点触控，只用于回归，不进正式包。
 */
@RunWith(AndroidJUnit4::class)
class ZoomPinchTest {

    @Test
    fun pinchZoomThenCaptureAndReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        if (instrumentation.targetContext.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.CAMERA
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.findObject(By.text("1.0x")), 8_000)
            device.waitForIdle()
            Thread.sleep(1_000)

            // 切到前摄（后摄当前被遮挡），切换后倍率应重置为 1.0x
            requireNotNull(device.wait(Until.findObject(By.text("后摄")), 5_000))
            device.click(154, 1940)
            requireNotNull(device.wait(Until.findObject(By.text("前摄")), 6_000))
            Thread.sleep(2_000)
            requireNotNull(device.wait(Until.findObject(By.text("1.0x")), 3_000))

            val root = requireNotNull(device.findObject(By.pkg("com.personal.watermarkcamera").depth(0)))
            val before = requireNotNull(currentZoomText(device))
            root.pinchOpen(0.6f, 30)

            val deadline = System.currentTimeMillis() + 4_000
            var after = before
            while (System.currentTimeMillis() < deadline) {
                val text = currentZoomText(device)
                if (text != null && text != before) {
                    after = text
                    break
                }
                Thread.sleep(150)
            }
            assertNotEquals("双指张开后倍率应变化", before, after)

            // 放大后的预览截图，供与成片构图对比
            shell(instrumentation, "screencap -p /sdcard/zoom_preview.png")

            // 拍照（内容描述为「拍照」的可点击区域在上方，排除底部导航同名图标）
            val shutter = device.findObjects(By.desc("拍照"))
                .firstOrNull { it.visibleBounds.top < 2_000 }
            assertTrue("未找到快门按钮", shutter != null)
            val shutterCenter = shutter!!.visibleCenter
            device.click(shutterCenter.x, shutterCenter.y)

            // 等待成功提示，随后立即截图成功反馈
            device.wait(Until.findObject(By.text("已保存到相册")), 10_000)
            shell(instrumentation, "screencap -p /sdcard/zoom_success.png")

            // 点击倍率标签复位到 1x
            val label = requireNotNull(device.findObject(By.text(after)))
            label.click()
            requireNotNull(device.wait(Until.findObject(By.text("1.0x")), 3_000))

            // 再次放大后切回后摄，倍率应重新复位为 1.0x
            val rootAgain = requireNotNull(
                device.findObject(By.pkg("com.personal.watermarkcamera").depth(0))
            )
            rootAgain.pinchOpen(0.6f, 30)
            val zoomDeadline = System.currentTimeMillis() + 3_000
            var secondZoom = "1.0x"
            while (System.currentTimeMillis() < zoomDeadline) {
                val text = currentZoomText(device)
                if (text != null && text != "1.0x") {
                    secondZoom = text
                    break
                }
                Thread.sleep(150)
            }
            assertNotEquals("第二次双指放大应生效", "1.0x", secondZoom)
            // 用控件中心坐标点击（与手动验证一致），避免文本节点点击被手势层吞掉
            device.click(154, 1940)
            requireNotNull(device.wait(Until.findObject(By.text("后摄")), 8_000))
            requireNotNull(device.wait(Until.findObject(By.text("1.0x")), 3_000))
        }
    }

    private fun currentZoomText(device: UiDevice): String? =
        device.findObject(By.text(Pattern.compile("\\d+\\.\\d+x")))?.text

    private fun shell(instrumentation: Instrumentation, command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        Thread.sleep(300)
    }
}
