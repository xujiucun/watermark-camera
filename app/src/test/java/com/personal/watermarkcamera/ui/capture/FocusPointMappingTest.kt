package com.personal.watermarkcamera.ui.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 点按对焦的屏幕 → PreviewView 本地坐标换算：横竖屏都不点偏。 */
class FocusPointMappingTest {

    // 竖屏预览区 1080x1650，3:4 画面居中 → 1080x1440
    private val portraitImage = Rect(0f, 105f, 1080f, 1545f)
    private val portraitView = 1080f to 1440f

    // 横屏时 PreviewView 被测量成 4:3（1440x1080），整块 View 再旋转 90/270
    private val landscapeView = 1440f to 1080f

    @Test
    fun portrait_center_mapsToViewCenter() {
        assertEquals(
            Offset(540f, 720f),
            previewLocalPointForScreen(Offset(540f, 825f), portraitImage, 0, portraitView.first, portraitView.second)
        )
    }

    @Test
    fun portrait_keepsOffsetFromImageOrigin() {
        // 画面左上角在屏幕 (0,105)，点 (200,400) → 本地 (200,295)
        assertEquals(
            Offset(200f, 295f),
            previewLocalPointForScreen(Offset(200f, 400f), portraitImage, 0, portraitView.first, portraitView.second)
        )
    }

    @Test
    fun landscape90_inverseRotatesOffset() {
        // 屏幕位移 (-340,-425) → 本地位移 (-425,+340)
        assertEquals(
            Offset(295f, 880f),
            previewLocalPointForScreen(
                Offset(200f, 400f), portraitImage, 90,
                landscapeView.first, landscapeView.second
            )
        )
        // 右下角对称点
        assertEquals(
            Offset(1145f, 200f),
            previewLocalPointForScreen(
                Offset(880f, 1250f), portraitImage, 90,
                landscapeView.first, landscapeView.second
            )
        )
    }

    @Test
    fun landscape270_inverseRotatesOffset() {
        // 270 与 90 对称：屏幕 (-340,-425) → 本地 (+425,-340)
        assertEquals(
            Offset(1145f, 200f),
            previewLocalPointForScreen(
                Offset(200f, 400f), portraitImage, 270,
                landscapeView.first, landscapeView.second
            )
        )
        assertEquals(
            Offset(295f, 880f),
            previewLocalPointForScreen(
                Offset(880f, 1250f), portraitImage, 270,
                landscapeView.first, landscapeView.second
            )
        )
    }

    @Test
    fun tapOutsideImage_returnsNull() {
        assertNull(
            previewLocalPointForScreen(Offset(0f, 0f), portraitImage, 0, portraitView.first, portraitView.second)
        )
        assertNull(
            previewLocalPointForScreen(Offset(540f, 1600f), portraitImage, 90, landscapeView.first, landscapeView.second)
        )
    }

    @Test
    fun zeroSizedView_returnsNull() {
        assertNull(
            previewLocalPointForScreen(Offset(540f, 825f), portraitImage, 0, 0f, 0f)
        )
    }
}
