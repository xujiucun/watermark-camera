package com.personal.watermarkcamera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.watermarkcamera.BuildConfig

/** 一个功能分组，例如「拍摄与预览」及其条目。 */
data class VersionSection(
    val title: String,
    val items: List<String>
)

/** 一条版本记录：版本号 + 发布日期 + 分组功能清单。 */
data class VersionNote(
    val version: String,
    val date: String,
    val sections: List<VersionSection>
)

/**
 * 当前版本条目。版本号直接取自构建产物 [BuildConfig.VERSION_NAME]，
 * 不在 UI 里再写一遍，避免和 app/build.gradle.kts 对不上。
 * 后续版本在 [versionNotes] 列表里继续追加即可，页面会自动渲染。
 */
val currentVersionNote = VersionNote(
    version = BuildConfig.VERSION_NAME,
    date = "2026-11-05",
    sections = listOf(
        VersionSection(
            title = "拍摄与预览",
            items = listOf(
                "CameraX 实时取景，水印预览与成片所见即所得",
                "前后摄像头切换与画面缩放",
                "自动 / 强制闪光 / 关闭三档闪光灯；无闪光灯摄像头置灰提示"
            )
        ),
        VersionSection(
            title = "水印信息",
            items = listOf(
                "居中时间、日期、星期与天气",
                "地点（省 / 市 / 区县粒度）与经纬度",
                "自定义文案（最多 20 字）"
            )
        ),
        VersionSection(
            title = "水印样式",
            items = listOf(
                "「时间地点」与「简洁时间」两套内置样式",
                "水印字号、文字颜色与整体透明度",
                "拖动调整水印位置",
                "经纬度底板、自定义文案底板可分别设置颜色与透明度"
            )
        ),
        VersionSection(
            title = "模板与图片",
            items = listOf(
                "我的模板：新建、套用、改名、更新与删除",
                "图片页浏览成片，支持多选删除（二次确认）"
            )
        ),
        VersionSection(
            title = "外观",
            items = listOf(
                "深色、浅色、跟随系统主题",
                "界面字号与水印字号独立调节"
            )
        )
    )
)

/** 版本记录列表：新版本加在最上面（当前只有 0.0.1 一条）。 */
val versionNotes: List<VersionNote> = listOf(currentVersionNote)

/**
 * 「版本说明」独立页面：返回按钮 + 版本号 / 发布日期 + 按功能分组的清单。
 * 数据来自 [versionNotes]，后续追加版本无需改这里。
 */
@Composable
fun VersionNotesScreen(darkTheme: Boolean, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BackHandler(onBack = onBack)
    StatusBarStyle(lightIcons = !darkTheme, color = scheme.background)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text(
                    "返回",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "版本说明",
                color = scheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            versionNotes.forEach { note ->
                VersionNoteContent(note)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VersionNoteContent(note: VersionNote) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "v${note.version}",
            color = scheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "发布日期 ${note.date}",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        note.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = section.title,
                    color = scheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
                section.items.forEach { item ->
                    Row {
                        Text(
                            text = "· ",
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = item,
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
