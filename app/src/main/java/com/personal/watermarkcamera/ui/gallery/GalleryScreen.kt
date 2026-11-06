package com.personal.watermarkcamera.ui.gallery

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.personal.watermarkcamera.AppContainer
import com.personal.watermarkcamera.R
import com.personal.watermarkcamera.data.DeleteOutcome
import com.personal.watermarkcamera.data.PhotoItem
import com.personal.watermarkcamera.data.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val Accent = Color(0xFFFF6B35)

/**
 * 只显示本应用归属的照片：查询 MediaStore 中 `OWNER_PACKAGE_NAME` 为当前包名、
 * 位于 `Pictures/WatermarkCamera` 的成片，不申请相册读取权限。
 * 缩略图走 ContentResolver.loadThumbnail，避免自行解码大图。
 * 长按进入多选，删除时同时清理 MediaStore 记录与私有原图。
 * 每次进入/切回都会重新查询，系统相册删除能即时反映。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(container: AppContainer) {
    val repository = container.photoRepository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<PhotoItem>?>(null) }
    var viewingIndex by remember { mutableStateOf<Int?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var deleting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 多选删除的二次确认
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    // 需要在系统确认框里授权的删除项（清数据/重装后归属被清除的老照片）
    var pendingConsent by remember { mutableStateOf<DeleteOutcome?>(null) }
    // 系统确认后仍删不掉的张数：非空时弹出原因 + 可执行指引
    var failedDeleteCount by remember { mutableStateOf<Int?>(null) }
    // 重新编辑水印：非空时显示独立编辑页
    var editingItem by remember { mutableStateOf<PhotoItem?>(null) }
    // 成片被覆盖后，缩略图与大图需要重新加载
    var refreshTick by remember { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(false) }
    val stripLocation = container.appSettings.stripLocationOnShare
    // 相册读取权限：默认不需要；只有「找回清除数据前的历史照片」才引导授予
    var readMediaGranted by remember { mutableStateOf(repository.canReadMedia()) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        readMediaGranted = repository.canReadMedia()
        if (granted) {
            scope.launch {
                photos = repository.loadPhotos()
                refreshTick++
            }
        }
    }

    fun requestMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mediaPermissionLauncher.launch(permission)
    }

    // 每次进入「图片」页都会重新组合，因此刚拍完切过来就能看到最新照片
    LaunchedEffect(Unit) {
        photos = repository.loadPhotos()
        withContext(Dispatchers.IO) { container.shareCache.cleanupOlderThan() }
    }

    // 从系统相册/分享面板切回时重新查询：系统侧删除会立即反映，避免残留空白格
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                readMediaGranted = repository.canReadMedia()
                scope.launch {
                    photos = repository.loadPhotos()
                    refreshTick++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun shareItems(items: List<PhotoItem>) {
        if (items.isEmpty() || sharing) return
        sharing = true
        scope.launch {
            val uris = container.shareCache.prepare(items, stripLocation)
            sharing = false
            if (uris.isEmpty()) {
                Toast.makeText(context, "分享文件准备失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (uris.size < items.size) {
                Toast.makeText(
                    context,
                    "有 ${items.size - uris.size} 张准备失败，已分享其余照片",
                    Toast.LENGTH_SHORT
                ).show()
            }
            val multiple = uris.size > 1
            val clip = ClipData.newRawUri("watermark photos", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            val intent = Intent(
                if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
            ).apply {
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (multiple) {
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList<Uri>(uris)
                    )
                } else {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
                clipData = clip
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "分享照片"))
            }.onFailure {
                Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3200)
            notice = null
        }
    }

    // 删除收尾：重新查询列表（系统侧真正删掉才会消失）、逐张核对结果并刷新界面
    suspend fun finishDelete(outcome: DeleteOutcome, cancelled: Boolean, requestedCount: Int) {
        val wasViewer = viewingIndex != null
        val reloaded = repository.loadPhotos()
        photos = reloaded
        refreshTick++
        selecting = false
        selected = emptySet()
        deleting = false
        viewingIndex = if (reloaded.isEmpty()) null else viewingIndex?.coerceAtMost(reloaded.lastIndex)

        val failedCount = if (cancelled) {
            (requestedCount - outcome.deletedCount).coerceAtLeast(0)
        } else {
            outcome.failedNames.size
        }
        val message = when {
            cancelled && outcome.deletedCount > 0 -> "已删除 ${outcome.deletedCount} 张，其余已取消"
            cancelled -> "已取消删除"
            outcome.deletedCount > 0 && failedCount > 0 ->
                "已删除 ${outcome.deletedCount} 张，$failedCount 张未删除"

            outcome.deletedCount > 0 -> "已删除 ${outcome.deletedCount} 张"
            else -> "删除失败"
        }
        // 全屏查看时列表底部的 notice 被 PhotoViewer 覆盖，改用 Toast 保证用户能看到结果
        if (wasViewer) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            notice = message
        }
        // 用户在系统框里点「取消」不算失败，不弹错误；只有确认后仍删不掉才给原因与指引
        if (!cancelled && failedCount > 0) failedDeleteCount = failedCount
    }

    // 系统确认框（MediaStore.createDeleteRequest）：允许后才真正删除，取消则照片保留
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingConsent ?: return@rememberLauncherForActivityResult
        pendingConsent = null
        val confirmed = result.resultCode == Activity.RESULT_OK
        scope.launch {
            // 无论系统返回什么结果，都以「回查 MediaStore 是否真的消失」为准
            val verified = repository.verifyConsent(pending.consentItems)
            val merged = DeleteOutcome(
                deletedCount = pending.deletedCount + verified.deletedCount,
                failedNames = verified.failedNames
            )
            finishDelete(
                outcome = merged,
                cancelled = !confirmed,
                requestedCount = pending.deletedCount + pending.consentItems.size
            )
        }
    }

    // 分级删除入口：先直接删，删不掉的统一走一次系统确认框
    fun beginDelete(targets: List<PhotoItem>) {
        if (targets.isEmpty() || deleting) return
        deleting = true
        scope.launch {
            val outcome = repository.deletePhotos(targets)
            val sender = outcome.consentIntentSender
            if (outcome.consentItems.isNotEmpty() && sender != null) {
                pendingConsent = outcome
                deleting = false
                consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                finishDelete(outcome, cancelled = false, requestedCount = targets.size)
            }
        }
    }

    val list = photos
    val openIndex = viewingIndex
    editingItem?.let { item ->
        WatermarkEditScreen(
            repository = repository,
            renderer = container.renderer,
            item = item,
            onBack = { editingItem = null },
            onSaved = {
                editingItem = null
                scope.launch {
                    photos = repository.loadPhotos()
                    refreshTick++
                }
            }
        )
        return
    }

    if (list != null && openIndex != null && list.isNotEmpty()) {
        PhotoViewer(
            photos = list,
            initialIndex = openIndex.coerceIn(0, list.lastIndex),
            deleting = deleting,
            version = refreshTick,
            stripLocation = stripLocation,
            sharing = sharing,
            onToggleStrip = { container.appSettings.updateStripLocationOnShare(it) },
            onShare = { item -> shareItems(listOf(item)) },
            onClose = { viewingIndex = null },
            onEdit = { editingItem = it },
            onDelete = { item -> beginDelete(listOf(item)) }
        )
        // 全屏查看时也要能看到「删不掉」的原因与操作指引
        failedDeleteCount?.let { count ->
            DeleteFailureDialog(
                count = count,
                readMediaGranted = readMediaGranted,
                onRequestPermission = {
                    failedDeleteCount = null
                    requestMediaPermission()
                },
                onDismiss = { failedDeleteCount = null }
            )
        }
        return
    }

    fun deleteSelected() {
        val items = (photos ?: emptyList()).filter { it.uri in selected }
        if (items.isEmpty() || deleting) return
        beginDelete(items)
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
    ) {
        when {
            list == null -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Accent
                )
            }

            list.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "还没有照片",
                        color = scheme.onBackground,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "去「拍照」拍一张，就会出现在这里",
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!readMediaGranted) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "清除应用数据或重装后，旧照片需要相册读取权限才能找回",
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { requestMediaPermission() }) {
                            Text(text = "授予权限找回旧照片", color = Accent)
                        }
                    }
                }
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (selecting) {
                        SelectionBar(
                            selectedCount = selected.size,
                            canSelectAll = selected.size < list.size,
                            canDelete = selected.isNotEmpty() && !deleting,
                            canShare = selected.isNotEmpty() && !sharing,
                            stripLocation = stripLocation,
                            sharing = sharing,
                            onToggleStrip = { container.appSettings.updateStripLocationOnShare(it) },
                            onShare = {
                                shareItems(list.filter { it.uri in selected })
                            },
                            onCancel = {
                                selecting = false
                                selected = emptySet()
                            },
                            onSelectAll = { selected = list.map { it.uri }.toSet() },
                            onDelete = { confirmDeleteSelected = true }
                        )
                    } else {
                        Text(
                            text = "共 ${list.size} 张",
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                    LazyVerticalGrid(
                        // 竖屏仍是 3 列；横屏按最小格宽自动增加列数，避免一屏只显示三个巨型格子
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(list, key = { it.uri.toString() }) { item ->
                            PhotoCell(
                                item = item,
                                version = refreshTick,
                                selectionMode = selecting,
                                selected = item.uri in selected,
                                onClick = {
                                    if (selecting) {
                                        selected = if (item.uri in selected) {
                                            selected - item.uri
                                        } else {
                                            selected + item.uri
                                        }
                                    } else {
                                        viewingIndex = list.indexOfFirst { it.uri == item.uri }
                                    }
                                },
                                onLongClick = {
                                    if (!selecting) {
                                        selecting = true
                                        selected = setOf(item.uri)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        notice?.let { text ->
            Text(
                text = text,
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    // 多选删除二次确认：取消不删除，确认后才走 MediaStore + 索引 + 私有原图清理
    if (confirmDeleteSelected) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("删除所选照片？") },
            text = { Text("将删除 $count 张，删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    deleteSelected()
                }) { Text("删除", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("取消") }
            }
        )
    }

    // 实在删不掉时给出用户能看懂的原因 + 可执行操作，而不是只抛文件名
    failedDeleteCount?.let { count ->
        DeleteFailureDialog(
            count = count,
            readMediaGranted = readMediaGranted,
            onRequestPermission = {
                failedDeleteCount = null
                requestMediaPermission()
            },
            onDismiss = { failedDeleteCount = null }
        )
    }
}

@Composable
private fun DeleteFailureDialog(
    count: Int,
    readMediaGranted: Boolean,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有 $count 张照片未能删除") },
        text = {
            Text(
                if (!readMediaGranted) {
                    "这些照片的归属记录已被系统清除（清除应用数据或重装导致），" +
                        "需要授予相册权限后才能删除。"
                } else {
                    "系统仍拒绝删除这些照片，请到系统相册里手动删除，" +
                        "或确认该目录没有被其他应用占用。"
                }
            )
        },
        confirmButton = {
            if (!readMediaGranted) {
                TextButton(onClick = onRequestPermission) {
                    Text("授予权限", color = Accent)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了", color = Accent) }
            }
        },
        dismissButton = if (!readMediaGranted) {
            { TextButton(onClick = onDismiss) { Text("稍后") } }
        } else {
            null
        }
    )
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    canSelectAll: Boolean,
    canDelete: Boolean,
    canShare: Boolean,
    stripLocation: Boolean,
    sharing: Boolean,
    onToggleStrip: (Boolean) -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选 $selectedCount 张",
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text("取消", color = scheme.onSurfaceVariant)
            }
            TextButton(onClick = onSelectAll, enabled = canSelectAll) {
                Text(
                    "全选",
                    color = if (canSelectAll) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
            TextButton(onClick = onShare, enabled = canShare) {
                Image(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        if (canShare) Accent else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (sharing) "准备中" else "分享",
                    color = if (canShare) Accent else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
            TextButton(onClick = onDelete, enabled = canDelete) {
                Image(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        if (canDelete) Accent else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "删除",
                    color = if (canDelete) Accent else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
        // 分享时移除位置信息：与全屏查看共用同一设置，界面上一眼可见
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = stripLocation,
                onCheckedChange = onToggleStrip
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "分享时移除位置信息（移除 GPS）",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    item: PhotoItem,
    version: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.uri, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(item.uri, Size(360, 480), null)
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 全屏查看：左右滑动切换上一张/下一张（Pager 首尾自然停住，不越界），
 * 顶部显示「当前/总数」；删除当前图后列表收拢（还能看下一张/上一张，全删完返回空态）。
 */
@Composable
private fun PhotoViewer(
    photos: List<PhotoItem>,
    initialIndex: Int,
    deleting: Boolean,
    version: Int,
    stripLocation: Boolean,
    sharing: Boolean,
    onToggleStrip: (Boolean) -> Unit,
    onShare: (PhotoItem) -> Unit,
    onClose: () -> Unit,
    onEdit: (PhotoItem) -> Unit,
    onDelete: (PhotoItem) -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    var confirmDelete by remember { mutableStateOf(false) }

    // 删除后列表变短时把页码收拢到有效范围，避免停在空白页
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty() && pagerState.currentPage > photos.lastIndex) {
            pagerState.scrollToPage(photos.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ViewerPage(item = photos[page], version = version)
        }

        // 顶部：页码角标 + 删除当前 / 关闭
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pagerState.currentPage + 1} / ${photos.size}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !deleting,
                onClick = {
                    val item = photos.getOrNull(pagerState.currentPage) ?: return@TextButton
                    if (item.hasOriginal) {
                        onEdit(item)
                    } else {
                        Toast.makeText(
                            context,
                            "这张没有原始照片，无法重新编辑",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                val item = photos.getOrNull(pagerState.currentPage)
                val available = item?.hasOriginal == true && !deleting
                Image(
                    painter = painterResource(R.drawable.ic_edit_watermark),
                    contentDescription = "编辑水印",
                    colorFilter = ColorFilter.tint(
                        if (available) Color.White else Color(0xFF9E9E9E)
                    ),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "编辑水印",
                    color = if (available) Color.White else Color(0xFF9E9E9E),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(
                enabled = !deleting,
                onClick = { confirmDelete = true }
            ) {
                Text(
                    text = "删除",
                    color = if (deleting) Color(0xFF9E9E9E) else Color(0xFFFF8A65),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onClose) {
                Text("关闭", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 底部：分享 + 「分享时移除位置信息」开关（默认开启，界面常驻可见）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = stripLocation,
                    onCheckedChange = onToggleStrip
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "分享时移除位置信息",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (stripLocation) {
                            "已开启：分享副本会剥离 GPS EXIF"
                        } else {
                            "已关闭：分享副本可能包含拍摄位置"
                        },
                        color = if (stripLocation) Color(0xFFBBBBBB) else Color(0xFFFFB74D),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(
                    enabled = !sharing,
                    onClick = {
                        val item = photos.getOrNull(pagerState.currentPage) ?: return@TextButton
                        onShare(item)
                    }
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = "分享",
                        colorFilter = ColorFilter.tint(
                            if (sharing) Color(0xFF9E9E9E) else Color.White
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (sharing) "准备中" else "分享",
                        color = if (sharing) Color(0xFF9E9E9E) else Color.White
                    )
                }
            }
        }
    }

    // 全屏查看的删除也先确认；取消不删
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张照片？") },
            text = { Text("将删除 1 张，删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val index = pagerState.currentPage
                    if (index in photos.indices) onDelete(photos[index])
                }) { Text("删除", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ViewerPage(item: PhotoItem, version: Int) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                loadSampledBitmap(context.contentResolver, item.uri, maxDimension = 1600)
            }.getOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 按目标长边做采样解码，避免全尺寸 Bitmap 造成 OOM */
private fun loadSampledBitmap(
    resolver: ContentResolver,
    uri: Uri,
    maxDimension: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val stream = resolver.openInputStream(uri) ?: return null
    stream.use { BitmapFactory.decodeStream(it, null, bounds) }

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
}
