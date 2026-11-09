package com.personal.watermarkcamera.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.domain.LocationDetail
import com.personal.watermarkcamera.domain.WatermarkPreset
import com.personal.watermarkcamera.domain.WatermarkSettings
import com.personal.watermarkcamera.domain.WatermarkTemplate
import com.personal.watermarkcamera.ui.template.TemplateNameDialog

private val Accent = Color(0xFFFF6B35)

private val COLOR_SWATCHES = listOf(
    0xFFFFFFFF.toInt() to "白",
    0xFFFFD54F.toInt() to "黄",
    0xFFFF6B35.toInt() to "橙",
    0xFF000000.toInt() to "黑"
)

/** 底板颜色：黑 / 蓝 / 白 / 橙，覆盖最常用的深色压字与彩色标签场景。 */
private val BACKDROP_COLORS = listOf(
    0xFF000000.toInt() to "黑",
    0xFF2D6CDF.toInt() to "蓝",
    0xFFFFFFFF.toInt() to "白",
    0xFFFF6B35.toInt() to "橙"
)

/**
 * 水印设置弹窗。
 *
 * 版式参考系统级设置面板：固定头部 + 分区标题 + 一行一项的设置列表 + 发丝分隔线，
 * 每行至少 52dp 高、整行可点，控件不再是挤在一起的彩色小胶囊。
 * 头部（标题 + 完成）固定不滚动，只有下方设置区滚动；高度上限 520dp，始终留出预览区域。
 *
 * 改动通过 [onChange] 立即回写设置，预览由同一份设置实时重绘。
 * 「我的模板」的完整管理（新建 / 改名 / 删除 / 更新）在独立页面
 * [com.personal.watermarkcamera.ui.template.TemplateScreen]；这里保留快速套用，
 * 以及「把当前设置存为模板」的快捷入口（仅在 showPresets 为 true 的拍摄页出现）。
 *
 * 字号全部基于 MaterialTheme.typography 的 sp 值，因此会随「App 界面字号」整体缩放；
 * 不受「水印字号」影响。分区标题与行文案都可换行/省略，大字号下不会被截断。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatermarkEditPanel(
    settings: WatermarkSettings,
    presets: List<WatermarkPreset>,
    activePresetId: String?,
    locationDenied: Boolean,
    locating: Boolean,
    onChange: (WatermarkSettings) -> Unit,
    onApplyPreset: (WatermarkPreset) -> Unit,
    onManageTemplates: () -> Unit,
    /** 非空时在「快速套用」区显示「存为模板」：由调用方把当前设置快照写进模板库并提示。 */
    onSaveAsTemplate: ((String) -> Unit)? = null,
    /** 「存为模板」命名对话框的默认名称。 */
    defaultTemplateName: String = "",
    onOpenLocationSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** 图片页「重新编辑水印」复用本面板时不展示模板快捷区（模板属于拍摄侧全局设置）。 */
    showPresets: Boolean = true,
    /** 高度上限：横屏整体高度很小，由调用方按可用高度收紧，底部弹窗仍保持头部固定 + 内容滚动。 */
    maxHeight: Dp = 520.dp,
    /** 头部标题；模板编辑页复用本面板时换成「模板参数」。 */
    title: String = "水印设置",
    /** 模板编辑页把保存放在顶栏，可隐藏这里的「完成」。 */
    showDone: Boolean = true
) {
    var saveTemplateDialog by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val titleStyle = MaterialTheme.typography.titleMedium
    val rowStyle = MaterialTheme.typography.bodyLarge
    val labelStyle = MaterialTheme.typography.bodyMedium
    val hintStyle = MaterialTheme.typography.bodySmall

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(scheme.surfaceContainerHigh)
    ) {
        // ---- 固定头部：不参与滚动 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = scheme.onSurface,
                style = titleStyle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showDone) {
                TextButton(onClick = onDone) {
                    Text(
                        text = "完成",
                        color = Accent,
                        style = titleStyle,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant)

        // ---- 可滚动内容区 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            // ---- 样式 ----
            SectionTitle("样式")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WatermarkTemplate.entries.forEach { template ->
                    ChoiceChip(
                        text = template.label,
                        selected = settings.template == template,
                        onClick = { onChange(settings.copy(template = template)) }
                    )
                }
            }

            // ---- 显示内容：一行一项，整行可点 ----
            SectionTitle("显示内容")
            SettingSwitchRow("时间", settings.showTime) {
                onChange(settings.copy(showTime = !settings.showTime))
            }
            RowDivider()
            SettingSwitchRow("日期", settings.showDate) {
                onChange(settings.copy(showDate = !settings.showDate))
            }
            RowDivider()
            SettingSwitchRow("天气", settings.showWeather) {
                onChange(settings.copy(showWeather = !settings.showWeather))
            }
            RowDivider()
            SettingSwitchRow("地点", settings.showLocation) {
                onChange(settings.copy(showLocation = !settings.showLocation))
            }
            RowDivider()
            SettingSwitchRow("经纬度", settings.showCoordinates) {
                onChange(settings.copy(showCoordinates = !settings.showCoordinates))
            }
            RowDivider()
            SettingSwitchRow("文字", settings.showCustom) {
                onChange(settings.copy(showCustom = !settings.showCustom))
            }

            if (settings.showCustom) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = settings.customText,
                    onValueChange = { onChange(settings.copy(customText = it.take(20))) },
                    singleLine = true,
                    textStyle = rowStyle,
                    label = { Text("自定义文字", style = hintStyle) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = scheme.onSurface,
                        unfocusedTextColor = scheme.onSurface,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = scheme.outlineVariant,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = scheme.onSurfaceVariant,
                        cursorColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 地点粒度 ----
            if (settings.showLocation) {
                SectionTitle("地点粒度")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocationDetail.entries.forEach { detail ->
                        ChoiceChip(
                            text = detail.label,
                            selected = settings.locationDetail == detail,
                            onClick = { onChange(settings.copy(locationDetail = detail)) }
                        )
                    }
                }
                if (locationDenied) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "定位未授权，水印暂时显示「未获取位置」",
                            color = scheme.onSurfaceVariant,
                            style = hintStyle,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenLocationSettings) {
                            Text("开启定位", color = Accent, style = labelStyle)
                        }
                    }
                } else if (locating) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "正在获取位置…",
                        color = scheme.onSurfaceVariant,
                        style = hintStyle
                    )
                }
            }

            // ---- 文字样式 ----
            SectionTitle("文字样式")
            SliderSetting(
                label = "水印字号",
                value = settings.titleSizeFraction,
                range = 0.045f..0.11f,
                valueText = String.format("%.1f", settings.titleSizeFraction * 1000),
                onValueChange = { onChange(settings.copy(titleSizeFraction = it)) }
            )
            SliderSetting(
                label = "透明度",
                value = settings.alpha,
                range = 0.25f..1f,
                valueText = "${(settings.alpha * 100).toInt()}%",
                onValueChange = { onChange(settings.copy(alpha = it)) }
            )
            Spacer(Modifier.height(10.dp))
            ColorSwatchRow(
                label = "文字颜色",
                options = COLOR_SWATCHES,
                selectedArgb = settings.colorArgb,
                onSelect = { onChange(settings.copy(colorArgb = it)) }
            )

            // ---- 底板样式：自定义文案底板与经纬度底板各自独立 ----
            SectionTitle("底板样式")
            Text(
                text = "仅「时间地点」样式使用底板；透明度 0% 即不画底板，简洁时间样式不受影响。",
                color = scheme.onSurfaceVariant,
                style = hintStyle
            )
            BackdropSetting(
                label = "文案底板",
                colorArgb = settings.customBackdropColorArgb,
                alpha = settings.customBackdropAlpha,
                onColor = { onChange(settings.copy(customBackdropColorArgb = it)) },
                onAlpha = { onChange(settings.copy(customBackdropAlpha = it)) }
            )
            BackdropSetting(
                label = "经纬度底板",
                colorArgb = settings.coordinateBackdropColorArgb,
                alpha = settings.coordinateBackdropAlpha,
                onColor = { onChange(settings.copy(coordinateBackdropColorArgb = it)) },
                onAlpha = { onChange(settings.copy(coordinateBackdropAlpha = it)) }
            )

            // ---- 快速套用（模板管理已移到独立页面）----
            if (showPresets) {
                SectionTitleWithAction(
                    text = "快速套用",
                    action = {
                        TextButton(onClick = onManageTemplates) {
                            Image(
                                painter = painterResource(R.drawable.ic_template),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(Accent),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("管理模板", color = Accent, style = labelStyle)
                        }
                    }
                )
                // 与模板页「独立新建」并存：这里把当前设置的完整快照直接存成模板（不改当前设置）
                if (onSaveAsTemplate != null) {
                    TextButton(onClick = { saveTemplateDialog = true }) {
                        Image(
                            painter = painterResource(R.drawable.ic_template),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Accent),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("存为模板", color = Accent, style = labelStyle)
                    }
                }
                if (presets.isEmpty()) {
                    Text(
                        text = "还没有自定义模板。点「存为模板」保存当前设置，或去「管理模板」独立新建。",
                        color = scheme.onSurfaceVariant,
                        style = hintStyle
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            ChoiceChip(
                                text = if (preset.id == activePresetId) "✓ ${preset.name}" else preset.name,
                                selected = preset.id == activePresetId,
                                onClick = { onApplyPreset(preset) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "关闭弹窗后也能直接在预览上单指拖动水印；相机缩放不会改变水印坐标与字号。",
                color = scheme.onSurfaceVariant,
                style = hintStyle
            )
        }
    }

    // 「存为模板」命名：确认后由调用方用当前设置的完整快照写库，本面板不直接改设置
    if (saveTemplateDialog && onSaveAsTemplate != null) {
        TemplateNameDialog(
            title = "存为模板",
            initialName = defaultTemplateName,
            confirmLabel = "保存",
            onDismiss = { saveTemplateDialog = false },
            onConfirm = { name ->
                saveTemplateDialog = false
                onSaveAsTemplate(name)
            }
        )
    }
}

/** 分区标题：小号、次级色，与行内容形成清晰层级。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

/** 分区标题 + 右侧操作（如「管理模板」）。 */
@Composable
private fun SectionTitleWithAction(text: String, action: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.weight(1f))
        action()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 开关行：标签在左、开关在右，整行可点，命中区不小于 52dp。 */
@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = scheme.onSurfaceVariant,
                uncheckedTrackColor = scheme.surfaceVariant,
                uncheckedBorderColor = scheme.outlineVariant
            )
        )
    }
}

/** 滑块行：标签与数值同一行，滑块独占下一行，保证拖动命中区。 */
@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = scheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 色板行：标签在上，色块带名称在下，每个色块有 44dp 的点击区域。 */
@Composable
private fun ColorSwatchRow(
    label: String,
    options: List<Pair<Int, String>>,
    selectedArgb: Int,
    onSelect: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            options.forEach { (argb, name) ->
                SwatchOption(
                    argb = argb,
                    label = name,
                    selected = selectedArgb == argb,
                    onClick = { onSelect(argb) }
                )
            }
        }
    }
}

/** 底板设置：颜色 + 透明度百分比 + 滑块。 */
@Composable
private fun BackdropSetting(
    label: String,
    colorArgb: Int,
    alpha: Float,
    onColor: (Int) -> Unit,
    onAlpha: (Float) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(alpha * 100).toInt()}%",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BACKDROP_COLORS.forEach { (argb, name) ->
                SwatchOption(
                    argb = argb,
                    label = name,
                    selected = colorArgb == argb,
                    onClick = { onColor(argb) }
                )
            }
        }
        Slider(
            value = alpha,
            onValueChange = onAlpha,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = scheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SwatchOption(argb: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Accent else scheme.outline,
                        shape = CircleShape
                    )
            )
        }
        Text(
            text = label,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent.copy(alpha = 0.16f) else scheme.surfaceVariant)
            .border(1.dp, if (selected) Accent else scheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Accent else scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
