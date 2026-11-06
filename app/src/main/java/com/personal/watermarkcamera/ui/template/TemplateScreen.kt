package com.personal.watermarkcamera.ui.template

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.watermarkcamera.AppContainer
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.domain.WatermarkPreset
import com.personal.watermarkcamera.domain.WatermarkSettings
import com.personal.watermarkcamera.ui.StatusBarStyle

private val Accent = Color(0xFFFF6B35)

/**
 * 「我的模板」独立页面：
 * 列表（名称 + 样式 + 可见项摘要）、新建（保存当前设置）、套用、改名、删除（二次确认）、
 * 用当前设置覆盖某个模板。模板数据由 [AppContainer.presetStore] 持久化，重启后仍在。
 */
@Composable
fun TemplateScreen(container: AppContainer, darkTheme: Boolean, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val presets = container.presetStore.presets
    val current = container.settingsStore.settings

    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WatermarkPreset?>(null) }
    var deleteTarget by remember { mutableStateOf<WatermarkPreset?>(null) }
    var updateTarget by remember { mutableStateOf<WatermarkPreset?>(null) }
    var appliedId by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)
    StatusBarStyle(lightIcons = !darkTheme, color = scheme.background)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
    ) {
        // 顶部栏：返回 / 标题 / 新建
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text("返回", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "我的模板",
                color = scheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center)
            )
            TextButton(
                onClick = { createOpen = true },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_template),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Accent),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("新建", color = Accent, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            text = "模板保存当时的全部水印设置（含位置），点「套用」即可整体还原；「更新」会用当前设置覆盖该模板。",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (presets.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "还没有模板",
                    color = scheme.onBackground,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "把当前的样式、显示项、字号、颜色与位置存成模板，以后一键切回。",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { createOpen = true }) {
                    Text("从当前设置新建模板", color = Accent)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(presets, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        applied = preset.id == appliedId,
                        onApply = {
                            container.settingsStore.update(preset.settings)
                            appliedId = preset.id
                            Toast.makeText(context, "已套用「${preset.name}」", Toast.LENGTH_SHORT)
                                .show()
                        },
                        onUpdate = { updateTarget = preset },
                        onRename = { renameTarget = preset },
                        onDelete = { deleteTarget = preset }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (createOpen) {
        NameDialog(
            title = "新建模板",
            initialName = container.presetStore.defaultName(),
            confirmLabel = "保存",
            onDismiss = { createOpen = false },
            onConfirm = { name ->
                createOpen = false
                val saved = container.presetStore.saveNew(name, current)
                appliedId = saved.id
                Toast.makeText(context, "已保存为「${saved.name}」", Toast.LENGTH_SHORT).show()
            }
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "重命名模板",
            initialName = target.name,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                container.presetStore.rename(target.id, name)
                Toast.makeText(context, "已改名", Toast.LENGTH_SHORT).show()
            }
        )
    }

    updateTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { updateTarget = null },
            title = { Text("更新模板「${target.name}」？") },
            text = { Text("会用当前的全部水印设置（含位置）覆盖这个模板，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    updateTarget = null
                    container.presetStore.updateSettings(
                        target.id,
                        container.settingsStore.settings
                    )
                    Toast.makeText(context, "已更新「${target.name}」", Toast.LENGTH_SHORT).show()
                }) { Text("更新", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { updateTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模板「${target.name}」？") },
            text = { Text("只删除这个模板，当前水印设置不会改动。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    container.presetStore.delete(target.id)
                    if (appliedId == target.id) appliedId = null
                    Toast.makeText(context, "已删除模板", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PresetCard(
    preset: WatermarkPreset,
    applied: Boolean,
    onApply: () -> Unit,
    onUpdate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .border(
                width = 1.dp,
                color = if (applied) Accent.copy(alpha = 0.65f) else scheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onApply)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = preset.name,
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (applied) {
                Text(
                    text = "已套用",
                    color = Accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = presetSummary(preset.settings),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onApply) {
                Text("套用", color = Accent, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onUpdate) {
                Text("更新", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRename) {
                Text("改名", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "删除模板",
                    colorFilter = ColorFilter.tint(scheme.onSurfaceVariant),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 示例：「时间地点 · 时间、日期、地点 · 含文案「记录生活」」 */
private fun presetSummary(settings: WatermarkSettings): String {
    val visible = buildList {
        if (settings.showTime) add("时间")
        if (settings.showDate) add("日期")
        if (settings.showWeather) add("天气")
        if (settings.showLocation) add("地点")
        if (settings.showCoordinates) add("经纬度")
    }
    val items = if (visible.isEmpty()) "无显示项" else visible.joinToString("、")
    val custom = if (settings.showCustom && settings.customText.isNotBlank()) {
        " · 含文案「${settings.customText}」"
    } else {
        ""
    }
    return "${settings.template.label} · $items$custom"
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                label = { Text("模板名称") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = scheme.outlineVariant,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = scheme.onSurfaceVariant,
                    cursorColor = Accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim().ifBlank { initialName }) }
            ) { Text(confirmLabel, color = if (name.isNotBlank()) Accent else scheme.onSurfaceVariant) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
