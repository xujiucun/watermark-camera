package com.personal.watermarkcamera.ui.template

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.personal.watermarkcamera.AppContainer
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.domain.WatermarkPreset
import com.personal.watermarkcamera.domain.WatermarkSettings
import com.personal.watermarkcamera.ui.StatusBarStyle
import com.personal.watermarkcamera.ui.capture.WatermarkEditPanel
import com.personal.watermarkcamera.ui.capture.WatermarkOverlay

private val Accent = Color(0xFFFF6B35)

/**
 * 模板编辑页：新建与编辑共用。
 *
 * - 草稿 [draft] 独立于当前拍摄设置，改动只作用于本页；只有保存时才写进「我的模板」，
 *   当前拍摄水印设置在整页操作中始终不被改动（套用才写入）。
 * - 顶部 3:4 预览用与水印设置弹窗同一套面板控件实时重绘，拖动预览可调整落点。
 * - 未保存返回会先确认；删除仅移除该模板。
 */
@Composable
fun TemplateEditScreen(
    container: AppContainer,
    darkTheme: Boolean,
    presetId: String?,
    onDone: () -> Unit,
    onDeleted: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val preset = remember(presetId) {
        container.presetStore.presets.firstOrNull { it.id == presetId }
    }
    var name by remember(presetId) {
        mutableStateOf(preset?.name ?: container.presetStore.defaultName())
    }
    var draft by remember(presetId) {
        mutableStateOf(preset?.settings ?: container.settingsStore.settings)
    }
    val initialSettings = remember(presetId) { draft }
    val initialName = remember(presetId) { name }

    var dragging by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val dirty = draft != initialSettings || name.trim() != initialName.trim()

    BackHandler {
        if (dirty) confirmDiscard = true else onDone()
    }
    StatusBarStyle(lightIcons = !darkTheme, color = scheme.background)

    // 预览用的位置快照：只用本地缓存（30 分钟内的真实地址），拿不到时按「未获取位置」渲染
    val location = remember { container.locationProvider.cached() }
    val timestamp = remember(presetId) { System.currentTimeMillis() }
    val spec = draft.toSpec(timestamp, location)

    fun save() {
        val cleanName = name.trim().ifBlank { container.presetStore.defaultName() }
        if (preset == null) {
            container.presetStore.saveNew(cleanName, draft)
        } else {
            container.presetStore.updateSettings(preset.id, draft)
            if (cleanName != preset.name) container.presetStore.rename(preset.id, cleanName)
        }
        Toast.makeText(context, "已保存「$cleanName」", Toast.LENGTH_SHORT).show()
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
    ) {
        // 顶部：取消 / 标题 / 保存
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (dirty) confirmDiscard = true else onDone() }) {
                Text("取消", color = scheme.onSurfaceVariant)
            }
            Text(
                text = if (preset == null) "新建模板" else "编辑模板",
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { save() }
            ) {
                Text(
                    text = "保存",
                    color = if (name.isNotBlank()) Accent else scheme.onSurfaceVariant
                )
            }
        }

        // 名称 + 删除（仅编辑已有模板时提供）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                label = { Text("模板名称", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = scheme.onSurface,
                    unfocusedTextColor = scheme.onSurface,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = scheme.outlineVariant,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = scheme.onSurfaceVariant,
                    cursorColor = Accent
                ),
                modifier = Modifier.weight(1f)
            )
            if (preset != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surfaceVariant)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
                        .clickable { confirmDelete = true },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "删除模板",
                        colorFilter = ColorFilter.tint(scheme.onSurfaceVariant),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 3:4 预览：黑色底 + 白色水印，拖动可调整位置
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            val previewSize = remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .onSizeChanged { previewSize.value = it }
                    .pointerInput(previewSize.value) {
                        if (previewSize.value.width <= 0 || previewSize.value.height <= 0) {
                            return@pointerInput
                        }
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, dragAmount ->
                            change.consume()
                            val w = previewSize.value.width.toFloat()
                            val h = previewSize.value.height.toFloat()
                            val halfX = (container.renderer.lastBlockWidth / w / 2f)
                                .takeIf { it > 0f }?.coerceAtMost(0.45f)
                            val halfY = (container.renderer.lastBlockHeight / h / 2f)
                                .takeIf { it > 0f }?.coerceAtMost(0.45f)
                            val marginX = (halfX ?: 0.06f) + 0.01f
                            val marginY = (halfY ?: 0.08f) + 0.01f
                            draft = draft.copy(
                                xFraction = (draft.xFraction + dragAmount.x / w)
                                    .coerceIn(marginX, 1f - marginX),
                                yFraction = (draft.yFraction + dragAmount.y / h)
                                    .coerceIn(marginY, 1f - marginY)
                            )
                        }
                    }
            ) {
                WatermarkOverlay(
                    spec = spec,
                    renderer = container.renderer,
                    imageAspect = 3f / 4f,
                    highlight = dragging,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = "拖动水印可调整位置",
                color = Color(0x99FFFFFF),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            )
        }

        // 完整参数：复用拍摄页的水印设置控件，但状态指向模板草稿
        WatermarkEditPanel(
            settings = draft,
            presets = emptyList(),
            activePresetId = null,
            locationDenied = false,
            locating = false,
            onChange = { draft = it },
            onApplyPreset = {},
            onManageTemplates = {},
            onOpenLocationSettings = {},
            onDone = {},
            showPresets = false,
            showDone = false,
            title = "模板参数",
            maxHeight = 2000.dp,
            modifier = Modifier.weight(1f)
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后本次改动不会保存，当前拍摄水印不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text("放弃", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            }
        )
    }

    if (confirmDelete && preset != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除模板「${preset.name}」？") },
            text = { Text("只删除这个模板；当前拍摄水印设置即使正在使用它也不会改动。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    container.presetStore.delete(preset.id)
                    Toast.makeText(context, "已删除模板", Toast.LENGTH_SHORT).show()
                    onDeleted(preset.id)
                }) { Text("删除", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}
