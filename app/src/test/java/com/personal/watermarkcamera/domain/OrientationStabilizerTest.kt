package com.personal.watermarkcamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationStabilizerTest {

    /** 0° 附近小幅摆动：始终竖屏。 */
    @Test
    fun `small wobble near portrait stays portrait`() {
        val stabilizer = OrientationStabilizer()
        assertNull(stabilizer.update(0f, 0L))
        assertNull(stabilizer.update(3f, 100L))
        assertNull(stabilizer.update(-2f, 200L))
        assertNull(stabilizer.update(5f, 300L))
        assertEquals(CaptureDirection.PORTRAIT, stabilizer.current)
    }

    /** 快速掠过 45~55° 又回到 0°，每步 < 100ms：不触发切换。 */
    @Test
    fun `fast sweep across mid angles does not switch`() {
        val stabilizer = OrientationStabilizer()
        var time = 0L
        listOf(0f, 45f, 50f, 55f, 45f, 0f).forEach { angle ->
            assertNull(stabilizer.update(angle, time))
            time += 80L
        }
        // 越过 60° 但立刻回摆，同样被驻留取消
        assertNull(stabilizer.update(70f, time))
        assertNull(stabilizer.update(0f, time + 80L))
        assertEquals(CaptureDirection.PORTRAIT, stabilizer.current)
    }

    /** 0° → 90° 持续 600ms：切到横屏一次，且只切一次。 */
    @Test
    fun `portrait to landscape commits once after dwell`() {
        val stabilizer = OrientationStabilizer()
        assertNull(stabilizer.update(0f, 0L))
        assertNull(stabilizer.update(90f, 100L))
        assertNull(stabilizer.update(90f, 400L))
        assertEquals(CaptureDirection.LANDSCAPE_LEFT, stabilizer.update(90f, 600L))
        assertNull(stabilizer.update(90f, 800L))
        assertNull(stabilizer.update(92f, 1200L))
        assertEquals(CaptureDirection.LANDSCAPE_LEFT, stabilizer.current)
    }

    /** 横屏状态下 90→40→20→35（短暂进入 30° 以内又回摆）：保持横屏。 */
    @Test
    fun `landscape keeps when portrait candidate bounces back`() {
        val stabilizer = OrientationStabilizer()
        stabilizer.update(0f, 0L)
        stabilizer.update(90f, 100L)
        stabilizer.update(90f, 500L)
        assertTrue(stabilizer.current.isLandscape)

        assertNull(stabilizer.update(40f, 600L))
        assertNull(stabilizer.update(20f, 700L))
        assertNull(stabilizer.update(35f, 800L))
        assertTrue(stabilizer.current.isLandscape)
    }

    /** 横屏状态下回到 10° 并持续：切回竖屏。 */
    @Test
    fun `landscape returns to portrait after dwell`() {
        val stabilizer = OrientationStabilizer()
        stabilizer.update(0f, 0L)
        stabilizer.update(90f, 100L)
        stabilizer.update(90f, 500L)
        assertTrue(stabilizer.current.isLandscape)

        assertNull(stabilizer.update(10f, 600L))
        assertEquals(CaptureDirection.PORTRAIT, stabilizer.update(10f, 1000L))
        assertEquals(CaptureDirection.PORTRAIT, stabilizer.current)
    }

    /** 平放（角度不可判定）：任意变化都维持不变，并能取消待提交的方向。 */
    @Test
    fun `flat posture keeps current direction`() {
        val stabilizer = OrientationStabilizer()
        assertNull(stabilizer.update(null, 0L))
        assertNull(stabilizer.update(null, 200L))
        assertEquals(CaptureDirection.PORTRAIT, stabilizer.current)

        // 横屏待提交期间进入平放，应取消并重新计时
        assertNull(stabilizer.update(90f, 300L))
        assertNull(stabilizer.update(null, 400L))
        assertNull(stabilizer.update(90f, 500L))
        assertNull(stabilizer.update(90f, 700L))
        assertEquals(CaptureDirection.PORTRAIT, stabilizer.current)
    }

    /** 静止在 90° 时传感器不再回调，tick 到点后仍能提交一次。 */
    @Test
    fun `tick commits pending direction when sensor is quiet`() {
        val stabilizer = OrientationStabilizer()
        assertNull(stabilizer.update(90f, 0L))
        assertEquals(400L, stabilizer.pendingCommitAtMillis)
        assertEquals(CaptureDirection.LANDSCAPE_LEFT, stabilizer.tick(400L))
        assertNull(stabilizer.tick(800L))
        assertEquals(CaptureDirection.LANDSCAPE_LEFT, stabilizer.current)
    }
}
