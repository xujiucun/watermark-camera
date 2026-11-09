package com.personal.watermarkcamera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.watermarkcamera.AppContainer
import com.personal.watermarkcamera.BuildConfig
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.data.ThemeMode
import com.personal.watermarkcamera.data.UiFontScale
import com.personal.watermarkcamera.domain.WatermarkSettings
import java.util.Locale

/** 项目主页地址：只在点击时交给系统浏览器打开，不联网、不内嵌 WebView。 */
private const val PROJECT_URL = "https://github.com/xujiucun/watermark-camera"

/**
 * 设置页：界面字号 / 水印字号（两套互不影响）+ 主题 + 我的模板入口 + 恢复默认水印设置 + 关于（版本说明入口）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenTemplates: () -> Unit,
    onOpenVersionNotes: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val watermark = container.settingsStore.settings
    val themeMode = container.appSettings.themeMode
    val uiScale = container.appSettings.uiFontScale
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "设置",
            color = scheme.onBackground,
            style = MaterialTheme.typography.titleLarge
        )

        Section(title = "界面字号") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiFontScale.entries.forEach { scale ->
                    FilterChip(
                        selected = UiFontScale.nearest(uiScale) == scale,
                        onClick = { container.appSettings.updateUiFontScale(scale.scale) },
                        label = { Text(scale.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = scheme.surfaceVariant,
                            labelColor = scheme.onSurfaceVariant,
                            selectedContainerColor = scheme.primary,
                            selectedLabelColor = scheme.onPrimary
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "当前 ${(uiScale * 100).toInt()}%",
                    color = scheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            Text(
                text = "只作用于界面文字：底部导航、设置页、水印设置弹窗、图片页。不影响成片水印。",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section(title = "水印字号") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = "相对画面宽度",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = String.format(Locale.US, "%.1f", watermark.titleSizeFraction * 1000f),
                    color = scheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Slider(
                value = watermark.titleSizeFraction,
                onValueChange = {
                    // 以 store 里的最新值做 copy，避免用旧快照覆盖掉刚改过的自定义文案等字段
                    container.settingsStore.update(
                        container.settingsStore.settings.copy(titleSizeFraction = it)
                    )
                },
                valueRange = 0.045f..0.11f
            )
            Text(
                text = "只作用于水印文字（预览与成片同步）；与拍摄页「水印设置」里的字号滑块是同一个值。",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section(title = "主题") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { container.appSettings.updateTheme(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = scheme.surfaceVariant,
                            labelColor = scheme.onSurfaceVariant,
                            selectedContainerColor = scheme.primary,
                            selectedLabelColor = scheme.onPrimary
                        )
                    )
                }
            }
            Text(
                text = "只影响界面配色；拍摄预览与水印外观不变。",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section(title = "我的模板") {
            OutlinedButton(
                onClick = onOpenTemplates,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("管理模板")
            }
            Text(
                text = "模板保存完整的样式、显示项、字号、颜色与位置；可新建、套用、改名、更新或删除。",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section(title = "水印设置") {
            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认水印设置")
            }
            Text(
                text = "会同时清空自定义文案，恢复前需要确认。",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section(title = "关于") {
            InfoRow(label = "应用名", value = stringResource(R.string.app_name))
            InfoRow(label = "版本", value = "v${BuildConfig.VERSION_NAME}")
            InfoRow(label = "发布日期", value = currentVersionNote.date)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "项目地址",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = PROJECT_URL,
                    color = scheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(
                            onClick = { openProjectPage(context) },
                            onLongClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("项目地址", PROJECT_URL)
                                )
                                Toast.makeText(context, "已复制项目地址", Toast.LENGTH_SHORT).show()
                            }
                        )
                        .padding(vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenVersionNotes)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "版本说明",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "  ›",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认水印设置？") },
            text = {
                Text("将重置模板、显示项、字号、颜色、透明度、位置，并清空自定义文案。此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        container.settingsStore.update(WatermarkSettings())
                        Toast.makeText(context, "已恢复默认，自定义文案已清空", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )
        content()
    }
}

/** 关于区的一行「标签 + 值」；标签让出空间，长值自然换行，不会与标签重叠。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 交给系统浏览器打开项目主页；无可用浏览器时提示，不崩溃。 */
private fun openProjectPage(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
}
