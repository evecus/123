package com.cablebee.assistant.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.BorderStroke
import com.cablebee.assistant.models.FileEntry
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.ui.components.CbeeLoader
import com.cablebee.assistant.ui.components.EmptyState
import com.cablebee.assistant.ui.components.FloatingToast
import com.cablebee.assistant.ui.components.LocalFilePickerSheet
import com.cablebee.assistant.ui.components.WidePageBody
import com.cablebee.assistant.ui.components.toastIsOk
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

// ── 排序方式枚举 ──────────────────────────────────────────────────────────────
enum class FileSortMode { Name, Time, Size }

// ── FilesScreen ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var transferring by remember { mutableStateOf(false) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(FileSortMode.Name) }
    var showHidden by remember { mutableStateOf(false) }

    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    var mountedPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    // 单文件操作 / 多选操作 / 信息 / 目录选择 / 编辑器 状态
    var actionTarget by remember { mutableStateOf<FileEntry?>(null) }
    var multiActionVisible by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<FileEntry?>(null) }
    var dirPickerCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var editorTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }

    val atRoot = currentPath == "/" || currentPath.isEmpty()

    // ── 本机保存目录 ──────────────────────────────────────────────────────────
    // 与 Flutter 一致：优先设置中的 local_save_path，默认 /sdcard/Download/CableBee
    fun localSaveDir(): File {
        val prefs = context.getSharedPreferences("cablebee", Context.MODE_PRIVATE)
        val p = prefs.getString("local_save_path", null)
        val dir = if (!p.isNullOrEmpty()) File(p) else File("/sdcard/Download/CableBee")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── 加载目录 ──────────────────────────────────────────────────────────────
    fun applySortAndSet(raw: List<FileEntry>, newPath: String? = null) {
        val visible = if (showHidden) raw else raw.filter { !it.name.startsWith(".") }
        val sorted = visible.toMutableList()
        sorted.sortWith { a, b ->
            if (a.isDirectory && !b.isDirectory) -1
            else if (!a.isDirectory && b.isDirectory) 1
            else when (sortMode) {
                FileSortMode.Name -> a.name.lowercase().compareTo(b.name.lowercase())
                FileSortMode.Time -> b.date.compareTo(a.date)
                FileSortMode.Size -> {
                    val sa = a.size.toIntOrNull() ?: 0
                    val sb = b.size.toIntOrNull() ?: 0
                    sb.compareTo(sa)
                }
            }
        }
        if (newPath != null) currentPath = newPath
        entries = sorted
        loading = false
    }

    fun loadDir(path: String) {
        scope.launch {
            loading = true
            multiSelect = false
            selected.clear()
            val res = adbService.listFiles(path)
            applySortAndSet(res, newPath = path)
        }
    }

    fun navigate(entry: FileEntry) {
        if (!entry.isDirectory) return
        val newPath = "$currentPath/${entry.name}".replace("//", "/")
        loadDir(newPath)
    }

    fun navigateTo(path: String) = loadDir(path)

    // ── AppBar 动作：快捷入口 + 更多菜单（对应 Flutter _pushActions）──────────
    LaunchedEffect(actionsVersion) {
        onActionsChanged {
            QuickEntryButton(mountedPaths = mountedPaths, onNavigate = { loadDir(it) })
            MoreMenuButton(
                sortMode = sortMode,
                showHidden = showHidden,
                onRefresh = { loadDir(currentPath) },
                onSortChanged = { sortMode = it; applySortAndSet(entries) },
                onHiddenChanged = { showHidden = it; applySortAndSet(entries) }
            )
        }
    }

    fun navigateUp() {
        if (atRoot) return
        val parts = currentPath.split("/").toMutableList()
        if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        val parent = if (parts.isEmpty() || parts.joinToString("/").isEmpty()) "/" else parts.joinToString("/")
        loadDir(parent)
    }

    // ── 挂载点检测 ────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        try {
            val res = adbService.shell("cat /proc/mounts 2>/dev/null")
            val mounts = linkedSetOf<String>()
            for (line in res.stdout.split("\n")) {
                val p = line.trim().split(Regex("\\s+"))
                if (p.size < 2) continue
                val mp = p[1]
                if (mp.startsWith("/storage/") &&
                    mp != "/storage/self" &&
                    mp != "/storage/emulated" &&
                    !mp.contains("emulated")
                ) {
                    mounts.add(mp)
                }
            }
            mountedPaths = mounts.toList()
        } catch (_: Exception) {}
        loadDir(currentPath)
    }

    // ── 上传 / 下载 / 删除 / 移动 / 复制 ───────────────────────────────────────

    var showUploadPicker by remember { mutableStateOf(false) }

    // 选择本机文件上传（本机文件选择器，对应 Flutter showLocalFilePicker）
    fun pushFile() {
        showUploadPicker = true
    }

    fun pullFile(entry: FileEntry) {
        scope.launch {
            val saveDir = localSaveDir()
            val localSave = File(saveDir, entry.name).absolutePath
            transferring = true
            transferMessage = "下载 ${entry.name}..."
            val remotePath = "$currentPath/${entry.name}"
            val res = adbService.pull(remotePath, localSave)
            transferring = false
            transferMessage = if (res.isSuccess) "✓ 已保存到 $localSave" else "✗ ${res.stderr}"
        }
    }

    fun deleteEntries(names: List<String>) {
        scope.launch {
            for (name in names) {
                adbService.shell("rm -rf \"$currentPath/$name\"")
            }
            loadDir(currentPath)
        }
    }

    fun moveEntries(names: List<String>, destDir: String) {
        scope.launch {
            for (name in names) {
                adbService.shell("mv \"$currentPath/$name\" \"$destDir/\"")
            }
            loadDir(currentPath)
        }
    }

    fun copyEntries(names: List<String>, destDir: String) {
        scope.launch {
            for (name in names) {
                adbService.shell("cp -r \"$currentPath/$name\" \"$destDir/\"")
            }
            loadDir(currentPath)
        }
    }

    fun showMultiSelectActions() {
        multiActionVisible = true
    }

    fun showDeleteConfirm(names: List<String>) {
        dirPickerCallback = null
        // 用 confirm 状态
        // 这里复用一个简单的内嵌确认对话框
        pendingDelete = names
    }

    fun showDeviceDirPicker(onResult: (String?) -> Unit) {
        dirPickerCallback = onResult
    }

    // ── 返回键处理 ────────────────────────────────────────────────────────────
    BackHandler(enabled = multiSelect || !atRoot) {
        when {
            multiSelect -> {
                multiSelect = false
                selected.clear()
            }
            !atRoot -> navigateUp()
        }
    }

    // ── 构建 ──────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Bg0,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                if (!multiSelect) {
                    FloatingActionButton(
                        onClick = { pushFile() },
                        containerColor = Primary,
                        contentColor = Bg0
                    ) {
                        Icon(Icons.Rounded.Upload, contentDescription = "上传文件到设备", modifier = Modifier.size(22.dp))
                    }
                }
            }
        ) { padding ->
            // WidePageBody：平板限制 1200dp 居中（对应 Flutter WidePageBody）
            WidePageBody(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 路径栏（含动作按钮）
                    PathBar(
                        currentPath = currentPath,
                        multiSelect = multiSelect,
                        selectedCount = selected.size,
                        onUp = { navigateUp() },
                        onConfirmMulti = { showMultiSelectActions() },
                        onCancelMulti = {
                            multiSelect = false
                            selected.clear()
                        }
                    )
                Divider(color = Bg3, thickness = 1.dp)

                // 文件列表
                if (loading) {
                    CbeeLoader("读取目录...")
                } else if (entries.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.FolderOpen,
                        title = "空目录",
                        message = currentPath
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(entries) { i, entry ->
                            if (i > 0) {
                                Divider(
                                    modifier = Modifier.padding(start = 60.dp),
                                    color = Bg3,
                                    thickness = 1.dp
                                )
                            }
                            val isSelected = selected.contains(entry.name)
                            FileRow(
                                entry = entry,
                                multiSelect = multiSelect,
                                isSelected = isSelected,
                                onTap = {
                                    if (multiSelect) {
                                        if (isSelected) selected.remove(entry.name)
                                        else selected.add(entry.name)
                                    } else if (entry.isDirectory) {
                                        navigate(entry)
                                    } else {
                                        actionTarget = entry
                                    }
                                },
                                onLongPress = {
                                    multiSelect = true
                                    selected.add(entry.name)
                                }
                            )
                        }
                    }
                }
            }
            }
        }

        // ── 单文件操作 BottomSheet ─────────────────────────────────────────────
        actionTarget?.let { entry ->
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { actionTarget = null },
                sheetState = sheetState,
                containerColor = Bg1,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 0.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.name,
                            modifier = Modifier.weight(1f),
                            fontFamily = JetBrainsMono,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Divider(color = Bg3, thickness = 1.dp)
                    SheetItem(Icons.Rounded.Info, "信息", Secondary) {
                        actionTarget = null
                        infoTarget = entry
                    }
                    SheetItem(Icons.Rounded.EditNote, "编辑", Primary) {
                        actionTarget = null
                        editorTarget = "$currentPath/${entry.name}" to entry.name
                    }
                    SheetItem(Icons.Rounded.Download, "下载", Success) {
                        actionTarget = null
                        pullFile(entry)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // ── 多选操作 BottomSheet ───────────────────────────────────────────────
        if (multiActionVisible) {
            val names = selected.toList()
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { multiActionVisible = false },
                sheetState = sheetState,
                containerColor = Bg1,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "对 ${names.size} 个项目执行操作",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 0.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                        fontFamily = SpaceMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Divider(color = Bg3, thickness = 1.dp)
                    SheetItem(Icons.Rounded.Download, "下载", Success) {
                        multiActionVisible = false
                        scope.launch {
                            val saveDir = localSaveDir()
                            for (name in names) {
                                val localSave = File(saveDir, name).absolutePath
                                transferring = true
                                transferMessage = "下载 $name..."
                                val res = adbService.pull("$currentPath/$name", localSave)
                                transferring = false
                                transferMessage = if (res.isSuccess) "✓ 已下载 $name" else "✗ ${res.stderr}"
                            }
                            multiSelect = false
                            selected.clear()
                        }
                    }
                    SheetItem(Icons.Rounded.DriveFileMove, "移动", Primary) {
                        multiActionVisible = false
                        showDeviceDirPicker { dest ->
                            if (dest != null) {
                                moveEntries(names, dest)
                            }
                            multiSelect = false
                            selected.clear()
                        }
                    }
                    SheetItem(Icons.Rounded.ContentCopy, "复制", Secondary) {
                        multiActionVisible = false
                        showDeviceDirPicker { dest ->
                            if (dest != null) {
                                copyEntries(names, dest)
                            }
                            multiSelect = false
                            selected.clear()
                        }
                    }
                    SheetItem(Icons.Rounded.DeleteOutline, "删除", Danger) {
                        multiActionVisible = false
                        showDeleteConfirm(names)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // ── 文件信息对话框 ─────────────────────────────────────────────────────
        infoTarget?.let { entry ->
            FileInfoDialog(
                entry = entry,
                currentPath = currentPath,
                onDismiss = { infoTarget = null }
            )
        }

        // ── 删除确认对话框 ─────────────────────────────────────────────────────
        pendingDelete?.let { names ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = Bg1,
                shape = RoundedCornerShape(14.dp),
                title = {
                    Text(
                        "确认删除",
                        fontFamily = SpaceMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        "将删除 ${names.size} 个项目，此操作不可撤销。",
                        fontFamily = SpaceMono,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingDelete = null
                            deleteEntries(names)
                            multiSelect = false
                            selected.clear()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
                    ) { Text("删除", fontFamily = SpaceMono, fontSize = 12.sp) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text("取消", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
                    }
                }
            )
        }

        // ── 目标目录选择对话框 ────────────────────────────────────────────────
        dirPickerCallback?.let { cb ->
            DeviceDirPickerDialog(
                initialPath = currentPath,
                onDismiss = { dirPickerCallback = null; cb(null) },
                onConfirm = { path -> dirPickerCallback = null; cb(path) }
            )
        }

        // ── 文件编辑器全屏覆盖 ─────────────────────────────────────────────────
        editorTarget?.let { (remotePath, fileName) ->
            FileEditorOverlay(
                remotePath = remotePath,
                fileName = fileName,
                adbService = adbService,
                onBack = { editorTarget = null }
            )
        }

        // ── 本机文件选择（上传，对应 Flutter showLocalFilePicker）──────────────
        if (showUploadPicker) {
            LocalFilePickerSheet(
                onDismiss = { showUploadPicker = false },
                onConfirm = { paths ->
                    showUploadPicker = false
                    scope.launch {
                        for (localPath in paths) {
                            val fileName = localPath.substringAfterLast('/')
                            val remotePath = "$currentPath/$fileName"
                            transferring = true
                            transferMessage = "上传 $fileName..."
                            val res = adbService.push(localPath, remotePath)
                            transferring = false
                            transferMessage =
                                if (res.isSuccess) "✓ 已上传 $fileName" else "✗ ${res.stderr}"
                        }
                        loadDir(currentPath)
                    }
                },
                allowMultiple = true,
                allowFolders = false
            )
        }

        // 底部悬浮提示（上传/下载结果等），位于底栏上方，3 秒自动消失
        FloatingToast(
            message = transferMessage,
            ok = transferMessage?.let { toastIsOk(it) } ?: true,
            onDismiss = { transferMessage = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

// ── 路径栏 ────────────────────────────────────────────────────────────────────
@Composable
private fun PathBar(
    currentPath: String,
    multiSelect: Boolean,
    selectedCount: Int,
    onUp: () -> Unit,
    onConfirmMulti: () -> Unit,
    onCancelMulti: () -> Unit
) {
    if (multiSelect) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Bg1)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancelMulti) {
                Text("取消", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
            }
            Text(
                "已选 $selectedCount 项",
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontFamily = SpaceMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            TextButton(
                onClick = { if (selectedCount > 0) onConfirmMulti() },
                enabled = selectedCount > 0
            ) {
                Text(
                    "操作",
                    fontFamily = SpaceMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedCount > 0) Primary else TextMuted
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Bg1)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            currentPath,
            modifier = Modifier.weight(1f),
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onUp,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
        ) {
            Text(
                "上一级",
                fontFamily = SpaceMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primary
            )
        }
    }
}

// ── 快捷入口按钮 ──────────────────────────────────────────────────────────────
@Composable
private fun QuickEntryButton(mountedPaths: List<String>, onNavigate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.BookmarkBorder, contentDescription = "快捷入口", tint = TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    MenuRow(Icons.Rounded.PhoneAndroid, "/sdcard", Primary)
                },
                onClick = { expanded = false; onNavigate("/sdcard") }
            )
            if (mountedPaths.isNotEmpty()) {
                Divider(thickness = 1.dp, color = Bg3)
            }
            mountedPaths.forEach { p ->
                DropdownMenuItem(
                    text = { MenuRow(Icons.Rounded.SdCard, p, Success) },
                    onClick = { expanded = false; onNavigate(p) }
                )
            }
        }
    }
}

// ── 三点菜单按钮 ──────────────────────────────────────────────────────────────
@Composable
private fun MoreMenuButton(
    sortMode: FileSortMode,
    showHidden: Boolean,
    onRefresh: () -> Unit,
    onSortChanged: (FileSortMode) -> Unit,
    onHiddenChanged: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { MenuRow(Icons.Rounded.Refresh, "刷新列表", TextSecondary) },
                onClick = { expanded = false; onRefresh() }
            )
            Divider(thickness = 1.dp, color = Bg3)
            DropdownMenuItem(
                text = {
                    MenuRow(
                        if (sortMode == FileSortMode.Name) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        "按名称排序",
                        if (sortMode == FileSortMode.Name) Primary else TextMuted
                    )
                },
                onClick = { expanded = false; onSortChanged(FileSortMode.Name) }
            )
            DropdownMenuItem(
                text = {
                    MenuRow(
                        if (sortMode == FileSortMode.Time) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        "按时间排序",
                        if (sortMode == FileSortMode.Time) Primary else TextMuted
                    )
                },
                onClick = { expanded = false; onSortChanged(FileSortMode.Time) }
            )
            DropdownMenuItem(
                text = {
                    MenuRow(
                        if (sortMode == FileSortMode.Size) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        "按大小排序",
                        if (sortMode == FileSortMode.Size) Primary else TextMuted
                    )
                },
                onClick = { expanded = false; onSortChanged(FileSortMode.Size) }
            )
            Divider(thickness = 1.dp, color = Bg3)
            DropdownMenuItem(
                text = {
                    MenuRow(
                        if (showHidden) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        "显示隐藏文件",
                        if (showHidden) Primary else TextMuted
                    )
                },
                onClick = { expanded = false; onHiddenChanged(!showHidden) }
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, iconColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontFamily = JetBrainsMono,
            fontSize = 13.sp,
            color = TextPrimary
        )
    }
}

// ── 文件行 ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    multiSelect: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val icon = fileIcon(entry)
    val iconColor = fileIconColor(entry)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconColor.copy(alpha = if (isSelected) 0.25f else 0.1f))
                .then(
                    if (isSelected) Modifier.border(1.5.dp, Primary, RoundedCornerShape(8.dp))
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${entry.permissions}  ${if (entry.isDirectory) "" else formatSize(entry.size)}  ${entry.date}",
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (multiSelect) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onTap() },
                colors = CheckboxDefaults.colors(checkedColor = Primary)
            )
        } else if (entry.isDirectory) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

private fun fileIcon(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Rounded.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk" -> Icons.Rounded.Android
        "zip", "tar", "gz", "rar" -> Icons.Rounded.Archive
        "jpg", "jpeg", "png", "gif", "webp" -> Icons.Rounded.Image
        "mp4", "mkv", "avi" -> Icons.Rounded.Movie
        "mp3", "aac", "flac", "ogg" -> Icons.Rounded.AudioFile
        "pdf" -> Icons.Rounded.PictureAsPdf
        "txt", "log", "xml", "json", "yaml", "sh" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun fileIconColor(entry: FileEntry): Color {
    if (entry.isDirectory) return Warning
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk" -> Success
        "jpg", "jpeg", "png", "gif", "webp" -> Secondary
        "mp4", "mkv", "avi" -> Color(0xFF9B59B6)
        "pdf" -> Danger
        else -> TextMuted
    }
}

private fun formatSize(raw: String): String {
    val n = raw.toIntOrNull() ?: return raw
    if (n < 1024) return "$n B"
    if (n < 1024 * 1024) return "%.1f KB".format(n / 1024.0)
    if (n < 1024 * 1024 * 1024) return "%.1f MB".format(n / 1024.0 / 1024.0)
    return "%.1f GB".format(n / 1024.0 / 1024.0 / 1024.0)
}

// ── BottomSheet 项 ────────────────────────────────────────────────────────────
@Composable
private fun SheetItem(icon: ImageVector, label: String, color: Color, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            fontSize = 14.sp,
            color = if (color == Danger) Danger else TextPrimary
        )
    }
}

// ── 文件信息对话框 ────────────────────────────────────────────────────────────
@Composable
private fun FileInfoDialog(entry: FileEntry, currentPath: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg1,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                entry.name,
                fontFamily = SpaceMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column {
                InfoLine("类型", if (entry.isDirectory) "目录" else "文件")
                InfoLine("路径", "$currentPath/${entry.name}")
                InfoLine("权限", entry.permissions)
                if (!entry.isDirectory) InfoLine("大小", entry.size)
                InfoLine("修改时间", entry.date)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", fontFamily = SpaceMono, fontSize = 12.sp, color = Primary)
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(72.dp),
            fontFamily = SpaceMono,
            fontSize = 11.sp,
            color = TextMuted
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            color = TextSecondary
        )
    }
}

// ── 设备目录选择对话框 ────────────────────────────────────────────────────────
@Composable
private fun DeviceDirPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg1,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                "选择目标目录",
                fontFamily = SpaceMono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("设备路径", fontSize = 12.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    color = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Bg3,
                    focusedContainerColor = Bg0,
                    unfocusedContainerColor = Bg0
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Bg0)
            ) { Text("确认", fontFamily = SpaceMono, fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
            }
        }
    )
}

// ── 内置文件编辑器（全屏覆盖） ─────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorOverlay(
    remotePath: String,
    fileName: String,
    adbService: AdbService,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(remotePath) {
        loading = true
        error = null
        val res = adbService.shell("cat \"$remotePath\" 2>&1")
        if (res.exitCode != 0 && res.stderr.isNotEmpty() && res.stdout.isEmpty()) {
            error = res.stderr
        } else {
            text = res.stdout
        }
        loading = false
    }

    fun save() {
        scope.launch {
            saving = true
            // Android 5.0 无 base64 工具，echo|base64 -d 方案不可用，统一走 push
            val res = adbService.writeTextFile(remotePath, text)
            saving = false
            // 简单反馈：成功时退出，失败时保留并标记
            if (res.exitCode == 0) {
                onBack()
            } else {
                error = "保存失败: ${res.stderr.ifEmpty { res.stdout }}"
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Bg0
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Bg1)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                Text(
                    fileName,
                    modifier = Modifier.weight(1f),
                    fontFamily = JetBrainsMono,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!loading && error == null) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(horizontal = 14.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                    } else {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Rounded.Save, contentDescription = "保存到设备", tint = Primary)
                        }
                    }
                }
            }
            Divider(color = Bg3, thickness = 1.dp)

            when {
                loading -> CbeeLoader("读取文件...")
                error != null -> Text(
                    error!!,
                    modifier = Modifier.padding(24.dp),
                    color = Danger,
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp
                )
                else -> OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        color = TextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Bg0,
                        unfocusedContainerColor = Bg0,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(0.dp)
                )
            }
        }
    }
}
