package com.cablebee.assistant.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 数据模型 ─────────────────────────────────────────────────────────────────

private enum class SortBy { Name, Size, Modified }
private enum class SortOrder { Asc, Desc }

private data class LocalFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * 本机文件选择器（对应 Flutter lib/widgets/local_file_picker.dart）。
 *
 * 底部弹出面板，支持：
 *  - 目录浏览 + 面包屑导航（/storage/emulated/0、/sdcard、/storage 根）
 *  - 按名称/大小/时间排序（升序/降序），文件夹始终在前
 *  - 多选 / 全选（allowMultiple）
 *  - 选择文件夹（allowFolders）
 *  - 隐藏文件（. 开头）默认跳过
 *
 * 权限：Android 11+ 需要 MANAGE_EXTERNAL_STORAGE（跳系统设置页授权），
 *       Android 10- 申请 READ_EXTERNAL_STORAGE。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilePickerSheet(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    allowMultiple: Boolean = false,
    allowFolders: Boolean = true,
    initialPath: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * 0.88f).dp

    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    val breadcrumbs = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }

    var sortBy by remember { mutableStateOf(SortBy.Name) }
    var sortOrder by remember { mutableStateOf(SortOrder.Asc) }
    var showSortMenu by remember { mutableStateOf(false) }

    // 首次进入：申请权限并定位起始目录
    LaunchedEffect(Unit) {
        ensureStoragePermission(context)
        val roots = listOf("/storage/emulated/0", "/sdcard", "/storage")
        val start = initialPath?.takeIf { File(it).isDirectory }
            ?: roots.firstOrNull { File(it).isDirectory }
            ?: "/storage/emulated/0"
        currentPath = start
        breadcrumbs.clear()
        breadcrumbs.add(start)
        loading = true
        entries = withContext(Dispatchers.IO) { listDir(start) } ?: emptyList()
        loading = false
    }

    fun loadDir(path: String) {
        scope.launch {
            loading = true
            error = null
            val list = withContext(Dispatchers.IO) { listDir(path) }
            loading = false
            if (list == null) {
                error = "无法访问该目录"
            } else {
                entries = list
                currentPath = path
            }
        }
    }

    fun applySort(list: List<LocalFileEntry>): List<LocalFileEntry> {
        val sorted = list.toMutableList()
        sorted.sortWith { a, b ->
            // 文件夹始终在前
            if (a.isDirectory && !b.isDirectory) return@sortWith -1
            if (!a.isDirectory && b.isDirectory) return@sortWith 1
            val cmp = when (sortBy) {
                SortBy.Name -> a.name.lowercase().compareTo(b.name.lowercase())
                SortBy.Size -> a.size.compareTo(b.size)
                SortBy.Modified -> a.modified.compareTo(b.modified)
            }
            if (sortOrder == SortOrder.Asc) cmp else -cmp
        }
        return sorted
    }

    val sortedEntries = remember(entries, sortBy, sortOrder) { applySort(entries) }
    val selectable = sortedEntries.filter { !it.isDirectory || allowFolders }
    val allSelected = selectable.isNotEmpty() && selectable.all { selected.contains(it.path) }

    val sheetContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 标题栏（把手由 ModalBottomSheet 统一提供，避免双条）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 0.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (breadcrumbs.size <= 1) {
                            onDismiss()
                        } else {
                            breadcrumbs.removeAt(breadcrumbs.lastIndex)
                            loadDir(breadcrumbs.last())
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "选择文件",
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W700,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                // 排序菜单
                Box {
                    IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Sort,
                            contentDescription = "排序",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        val sortItems = listOf(
                            Triple("名称↑", SortBy.Name, SortOrder.Asc),
                            Triple("名称↓", SortBy.Name, SortOrder.Desc),
                            Triple("大小↑", SortBy.Size, SortOrder.Asc),
                            Triple("大小↓", SortBy.Size, SortOrder.Desc),
                            Triple("时间↑", SortBy.Modified, SortOrder.Asc),
                            Triple("时间↓", SortBy.Modified, SortOrder.Desc),
                        )
                        sortItems.forEach { (label, by, order) ->
                            val active = sortBy == by && sortOrder == order
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (active) Icons.Rounded.Check else Icons.Rounded.Circle,
                                            contentDescription = null,
                                            tint = if (active) Primary else TextMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            label,
                                            fontFamily = JetBrainsMono,
                                            fontSize = 12.sp,
                                            color = if (active) Primary else TextPrimary
                                        )
                                    }
                                },
                                onClick = {
                                    sortBy = by
                                    sortOrder = order
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
                if (allowMultiple) {
                    TextButton(
                        onClick = {
                            if (allSelected) selected.removeAll(selectable.map { it.path }.toSet())
                            else selectable.forEach { if (!selected.contains(it.path)) selected.add(it.path) }
                        }
                    ) {
                        Text(
                            if (allSelected) "取消全选" else "全选",
                            fontFamily = SpaceMono,
                            fontSize = 12.sp,
                            color = Primary
                        )
                    }
                }
            }

            // ── 面包屑 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                breadcrumbs.forEachIndexed { index, path ->
                    val isLast = index == breadcrumbs.lastIndex
                    val label = when {
                        path == "/storage/emulated/0" || path == "/sdcard" -> "内部存储"
                        else -> path.trimEnd('/').substringAfterLast('/').ifEmpty { "根目录" }
                    }
                    Text(
                        label,
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        fontWeight = if (isLast) FontWeight.W600 else FontWeight.W400,
                        color = if (isLast) TextPrimary else TextMuted,
                        modifier = Modifier
                            .clickable(enabled = !isLast) {
                                breadcrumbs.removeRange(index + 1, breadcrumbs.size)
                                loadDir(path)
                            }
                            .padding(vertical = 8.dp)
                    )
                    if (!isLast) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Divider(color = Bg3, thickness = 1.dp)

            // ── 文件列表 ──
            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Primary)
                    }
                    error != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "无法访问\n$error",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                    sortedEntries.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "空目录",
                            fontFamily = SpaceMono,
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(sortedEntries) { index, entry ->
                            if (index > 0) {
                                Divider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = Bg3,
                                    thickness = 1.dp
                                )
                            }
                            val isSelected = selected.contains(entry.path)
                            val canSelect = !entry.isDirectory || allowFolders
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) Primary.copy(alpha = 0.08f) else Color.Transparent
                                    )
                                    .clickable {
                                        if (entry.isDirectory) {
                                            breadcrumbs.add(entry.path)
                                            loadDir(entry.path)
                                        } else if (canSelect) {
                                            if (selected.contains(entry.path)) selected.remove(entry.path)
                                            else {
                                                if (!allowMultiple) selected.clear()
                                                selected.add(entry.path)
                                                if (!allowMultiple) {
                                                    // 单选模式：点击文件直接确认
                                                    onConfirm(listOf(entry.path))
                                                    return@clickable
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (allowMultiple && canSelect) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            if (selected.contains(entry.path)) selected.remove(entry.path)
                                            else selected.add(entry.path)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = Primary),
                                        modifier = Modifier.padding(end = 10.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(entryColor(entry).copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        entryIcon(entry),
                                        contentDescription = null,
                                        tint = entryColor(entry),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        fontFamily = JetBrainsMono,
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        if (entry.isDirectory) formatDate(entry.modified)
                                        else "${formatSize(entry.size)}  ${formatDate(entry.modified)}",
                                        fontFamily = JetBrainsMono,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                                if (entry.isDirectory) {
                                    Icon(
                                        Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (!allowMultiple && !entry.isDirectory && isSelected) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 底部操作栏 ──
            Divider(color = Bg3, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selected.isEmpty()) "未选择任何文件" else "已选 ${selected.size} 个",
                    modifier = Modifier.weight(1f),
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    color = TextMuted
                )
                TextButton(onClick = onDismiss) {
                    Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (selected.isNotEmpty()) onConfirm(selected.toList())
                    },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Bg0,
                        disabledContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("确认", fontFamily = SpaceMono, fontSize = 13.sp, fontWeight = FontWeight.W700)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Bg0,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 不显示拖动手柄，去掉顶部小白条和多余空白
        dragHandle = null,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = if (configuration.screenWidthDp >= 600) {
            // 平板上限制弹窗宽度并居中（对应 Flutter ConstrainedBox maxWidth 560）
            Modifier.width(560.dp)
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        Box(modifier = Modifier.height(sheetHeight)) {
            sheetContent()
        }
    }
}

// ── 工具函数 ─────────────────────────────────────────────────────────────────

private fun listDir(path: String): List<LocalFileEntry>? {
    return try {
        val dir = File(path)
        if (!dir.isDirectory) return null
        val list = dir.listFiles() ?: return emptyList()
        list.mapNotNull { f ->
            try {
                val name = f.name
                if (name.startsWith(".")) return@mapNotNull null // 跳过隐藏文件
                LocalFileEntry(
                    name = name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0 else f.length(),
                    modified = f.lastModified(),
                )
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { null }
}

private fun ensureStoragePermission(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        }
    } else {
        // Android 10-：申请 READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2fGB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

private fun entryIcon(e: LocalFileEntry): ImageVector {
    if (e.isDirectory) return Icons.Rounded.Folder
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "img", "bin", "zip", "tar", "gz", "br" -> Icons.Rounded.Archive
        "apk" -> Icons.Rounded.Android
        "jpg", "jpeg", "png", "gif", "webp" -> Icons.Rounded.Image
        "mp4", "mkv", "avi" -> Icons.Rounded.Movie
        "mp3", "aac", "flac" -> Icons.Rounded.AudioFile
        "pdf" -> Icons.Rounded.PictureAsPdf
        "txt", "log", "xml", "json" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun entryColor(e: LocalFileEntry): Color {
    if (e.isDirectory) return Warning
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "img", "bin" -> Primary
        "apk" -> Success
        "zip", "tar", "gz" -> Secondary
        "jpg", "jpeg", "png", "gif" -> Color(0xFF9B59B6)
        "mp4", "mkv" -> Color(0xFFE74C3C)
        "pdf" -> Danger
        else -> TextMuted
    }
}
