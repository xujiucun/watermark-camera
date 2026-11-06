package com.personal.watermarkcamera.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

import com.personal.watermarkcamera.AppContainer
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.ui.capture.CaptureScreen
import com.personal.watermarkcamera.ui.gallery.GalleryScreen
import com.personal.watermarkcamera.ui.template.TemplateScreen

/**
 * 三个页面用简单状态切换，不引入 navigation 依赖。
 * 切走时页面会离开组合，拍摄页的 CameraSession 随之释放。
 * 「我的模板」是独立页面（设置页 / 水印设置弹窗均可进入），返回后回到原 tab。
 */
@Composable
fun MainScreen(container: AppContainer, darkTheme: Boolean) {
    var tab by remember { mutableIntStateOf(TAB_CAPTURE) }
    var templatesOpen by remember { mutableStateOf(false) }
    var versionNotesOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    if (templatesOpen) {
        TemplateScreen(
            container = container,
            darkTheme = darkTheme,
            onBack = { templatesOpen = false }
        )
        return
    }

    if (versionNotesOpen) {
        VersionNotesScreen(
            darkTheme = darkTheme,
            onBack = { versionNotesOpen = false }
        )
        return
    }

    if (tab != TAB_CAPTURE) {
        // 拍摄页会自己把状态栏压成黑底浅图标；其余页面跟随主题
        StatusBarStyle(lightIcons = !darkTheme, color = scheme.background)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (tab) {
                TAB_CAPTURE -> CaptureScreen(
                    container = container,
                    onOpenTemplates = { templatesOpen = true }
                )
                TAB_GALLERY -> GalleryScreen(container = container)
                else -> SettingsScreen(
                    container = container,
                    onOpenTemplates = { templatesOpen = true },
                    onOpenVersionNotes = { versionNotesOpen = true }
                )
            }
        }

        BottomTabs(selected = tab, onSelect = { tab = it })
    }
}

private const val TAB_CAPTURE = 0
private const val TAB_GALLERY = 1
private const val TAB_SETTINGS = 2

@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .navigationBarsPadding()
            .padding(vertical = 6.dp)
    ) {
        TabItem(
            label = "拍照",
            iconRes = R.drawable.ic_nav_capture,
            selected = selected == TAB_CAPTURE,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(TAB_CAPTURE) }
        )
        TabItem(
            label = "图片",
            iconRes = R.drawable.ic_nav_gallery,
            selected = selected == TAB_GALLERY,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(TAB_GALLERY) }
        )
        TabItem(
            label = "设置",
            iconRes = R.drawable.ic_settings,
            selected = selected == TAB_SETTINGS,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(TAB_SETTINGS) }
        )
    }
}

@Composable
private fun TabItem(
    label: String,
    iconRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (selected) scheme.onSurface else scheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(contentColor),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) scheme.primary else Color.Transparent)
        )
    }
}
