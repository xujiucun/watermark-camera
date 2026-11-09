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
 * 当前版本条目（0.0.2）。版本号直接取自构建产物 [BuildConfig.VERSION_NAME]，
 * 不在 UI 里再写一遍，避免和 app/build.gradle.kts 对不上。
 * 历史版本在 [versionNotes] 里按新 → 旧排列，页面会自动渲染。
 */
val currentVersionNote = VersionNote(
    version = BuildConfig.VERSION_NAME,
    date = "2026-11-09",
    sections = listOf(
        VersionSection(
            title = "模板",
            items = listOf(
                "模板页独立新建与编辑：独立页面、完整参数、3:4 实时预览、可拖动位置，未保存返回前确认",
                "拍摄页水印设置弹窗「存为模板」：保存当前设置的完整快照，保存后立即出现在快速套用",
                "编辑或保存模板都不会改动当前拍摄水印"
            )
        ),
        VersionSection(
            title = "图片页",
            items = listOf(
                "给手机已有照片加水印：图片页右上角入口（空相册也保留），系统 Photo Picker / SAF 选图，不申请相册读取权限，复用同一套水印编辑界面",
                "外部照片的水印时间取原图 EXIF 拍摄时间（无 EXIF 退回文件修改时间），地点优先 EXIF GPS、无 GPS 退回当前定位",
                "保存为另存的新成片到 Pictures/WatermarkCamera，原图不覆盖、不修改",
                "成片重新编辑水印：基于私有原图覆盖相册中的同一条成片，可反复编辑",
                "分享单张 / 多选照片，支持「移除位置信息」开关（默认开启，剥离 EXIF GPS）",
                "全屏查看，左右滑动切换上一张 / 下一张",
                "长按进入多选，删除带二次确认",
                "归属被系统清空的老照片需在系统确认框授权后删除",
                "图片列表改为查询系统 MediaStore，并支持按需授予相册读取权限找回清除数据 / 重装前的旧照片"
            )
        ),
        VersionSection(
            title = "拍摄与预览",
            items = listOf(
                "点按空白区域对焦，白色四角框约 1.6 秒淡出；不支持时给出提示",
                "倍率滑杆显示可用档位，支持点击档位直达、松手吸附与点击数值回到 1x",
                "水印需按在水印块上才能拖动，块外拖动不改变位置",
                "双指缩放",
                "自动 / 强制闪光 / 关闭三档闪光灯；无闪光灯摄像头置灰提示"
            )
        ),
        VersionSection(
            title = "方向与布局",
            items = listOf(
                "页面固定竖屏，横屏时只横置预览画面与水印，不重排页面",
                "方向切换带滞回与驻留稳定器，临界角度不反复抖动"
            )
        ),
        VersionSection(
            title = "水印设置与样式",
            items = listOf(
                "水印设置弹窗改为分组卡片新版式，包含快速套用与「存为模板」入口",
                "自定义文案底板与经纬度底板可各自独立设置颜色与透明度"
            )
        )
    )
)

/** 0.0.1 首个可用版本，作为历史记录保留在版本页下方。 */
val versionNote001 = VersionNote(
    version = "0.0.1",
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

/** 版本记录列表：新版本在最上面。 */
val versionNotes: List<VersionNote> = listOf(currentVersionNote, versionNote001)

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
