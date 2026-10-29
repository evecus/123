package com.cablebee.assistant.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cablebee.assistant.models.PkgInfo
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.services.PkgServerService
import com.cablebee.assistant.ui.components.CbeeLoader
import com.cablebee.assistant.ui.components.EmptyState
import com.cablebee.assistant.ui.components.FloatingToast
import com.cablebee.assistant.ui.components.LocalFilePickerSheet
import com.cablebee.assistant.ui.components.WidePageBody
import com.cablebee.assistant.ui.components.toastIsOk
import com.cablebee.assistant.ui.theme.*
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── 排序方式枚举 ──────────────────────────────────────────────────────────────
enum class AppsSortMode { PackageName, AppName }

// ── 扩展 PkgInfo，包含可变 UI 状态 ─────────────────────────────────────────────
data class AppInfoEx(
    val pkg: PkgInfo,
    val isDisabled: Boolean = false,
    val isFrozen: Boolean = false
) {
    val packageName: String get() = pkg.packageName
    val apkPath: String get() = pkg.apkPath
    val isSystem: Boolean get() = pkg.isSystem
    val displayName: String
        get() = if (pkg.label.isNotEmpty()) pkg.label else packageName.substringAfterLast('.')
    val appLabel: String? get() = if (pkg.label.isNotEmpty()) pkg.label else null
}

// ── 对话框状态 ────────────────────────────────────────────────────────────────
sealed class AppsDialog {
    object Search : AppsDialog()
    data class SearchResults(val query: String) : AppsDialog()
    object Options : AppsDialog()
    object Restore : AppsDialog()
    data class AppActions(val app: AppInfoEx) : AppsDialog()
    data class AppDetails(val app: AppInfoEx) : AppsDialog()
}

private data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmText: String,
    val onConfirm: () -> Unit
)

// ── AppsScreen ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pkgServer = remember(adbService) { PkgServerService(adbService, context) }

    val apps = remember { mutableStateListOf<AppInfoEx>() }
    var filtered by remember { mutableStateOf<List<AppInfoEx>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var actionResult by remember { mutableStateOf<String?>(null) }
    var busyPackage by remember { mutableStateOf<String?>(null) }

    var showSystem by remember { mutableStateOf(true) }
    var showThirdParty by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(AppsSortMode.PackageName) }

    var dialog by remember { mutableStateOf<AppsDialog?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var showInstallSheet by remember { mutableStateOf(false) }
    var showLocalAppsPicker by remember { mutableStateOf(false) }
    var detailsData by remember { mutableStateOf<Map<String, String>?>(null) }

    // ── AppBar 动作：搜索 + 更多选项（对应 Flutter _pushActions）──────────────
    LaunchedEffect(actionsVersion) {
        onActionsChanged {
            IconButton(onClick = { dialog = AppsDialog.Search }) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "搜索",
                    tint = TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = { dialog = AppsDialog.Options }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多选项",
                    tint = TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    // ── 过滤 / 排序 ───────────────────────────────────────────────────────────
    fun applyFilter(list: List<AppInfoEx>, query: String = ""): List<AppInfoEx> {
        var r = list.filter { a ->
            when {
                a.isSystem && !showSystem -> false
                !a.isSystem && !showThirdParty -> false
                else -> true
            }
        }
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            r = r.filter {
                it.packageName.lowercase().contains(q) ||
                        (it.appLabel?.lowercase()?.contains(q) == true)
            }
        }
        r = when (sortMode) {
            AppsSortMode.PackageName -> r.sortedBy { it.packageName }
            AppsSortMode.AppName -> r.sortedBy { it.displayName.lowercase() }
        }
        return r
    }

    // ── 流式加载应用列表 ──────────────────────────────────────────────────────
    fun loadApps() {
        scope.launch {
            loading = true
            apps.clear()
            filtered = emptyList()
            actionResult = null
            val sortBy = if (sortMode == AppsSortMode.PackageName) "package" else "label"
            var batch = 0
            try {
                pkgServer.streamAllPackages(sortBy).collect { pkg ->
                    val ex = AppInfoEx(
                        pkg = pkg,
                        isDisabled = !pkg.enabled,
                        isFrozen = !pkg.enabled
                    )
                    apps.add(ex)
                    batch++
                    // 前 50 条每条刷新，之后每 5 条刷新一次
                    if (batch <= 50 || batch % 5 == 0) {
                        filtered = applyFilter(apps.toList())
                    }
                }
            } catch (e: Exception) {
                actionResult = "✗ 加载失败: ${e.message}"
            }
            loading = false
            filtered = applyFilter(apps.toList())
        }
    }

    LaunchedEffect(Unit) { loadApps() }

    // ── 本机保存目录 ──────────────────────────────────────────────────────────
    // 与 Flutter 一致：优先设置中的 local_save_path，默认 /sdcard/Download/CableBee
    fun localSaveDir(): File {
        val prefs = context.getSharedPreferences("cablebee", Context.MODE_PRIVATE)
        val p = prefs.getString("local_save_path", null)
        val dir = if (!p.isNullOrEmpty()) File(p) else File("/sdcard/Download/CableBee")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── ADB 操作 ──────────────────────────────────────────────────────────────
    fun launchApp(app: AppInfoEx) {
        scope.launch {
            busyPackage = app.packageName
            val res = adbService.shell("monkey -p ${app.packageName} -c android.intent.category.LAUNCHER 1")
            busyPackage = null
            actionResult = if (res.output.trim().isEmpty()) "已启动 ${app.displayName}" else res.output
        }
    }

    fun freezeApp(app: AppInfoEx) {
        scope.launch {
            busyPackage = app.packageName
            val res = adbService.shell("pm disable-user --user 0 ${app.packageName}")
            busyPackage = null
            actionResult = if (res.isSuccess) "已冻结 ${app.displayName}" else res.output
            if (res.isSuccess) loadApps()
        }
    }

    fun disableApp(app: AppInfoEx) {
        confirm = ConfirmRequest(
            title = "停用应用",
            message = "确认停用 ${app.packageName}？\n停用后该应用将无法使用。",
            confirmText = "停用"
        ) {
            confirm = null
            scope.launch {
                busyPackage = app.packageName
                val res = adbService.shell("pm disable ${app.packageName}")
                busyPackage = null
                actionResult = if (res.isSuccess) "已停用 ${app.displayName}" else res.output
                if (res.isSuccess) loadApps()
            }
        }
    }

    fun enableApp(app: AppInfoEx) {
        scope.launch {
            busyPackage = app.packageName
            val res = adbService.shell("pm enable ${app.packageName}")
            busyPackage = null
            actionResult = if (res.isSuccess) "已恢复 ${app.displayName}" else res.output
            if (res.isSuccess) loadApps()
        }
    }

    fun uninstall(app: AppInfoEx) {
        confirm = ConfirmRequest(
            title = "卸载应用",
            message = "确认卸载 ${app.packageName}？",
            confirmText = "卸载"
        ) {
            confirm = null
            scope.launch {
                busyPackage = app.packageName
                val res = adbService.uninstall(app.packageName)
                busyPackage = null
                actionResult = res.output
                if (res.isSuccess) loadApps()
            }
        }
    }

    fun clearData(app: AppInfoEx) {
        confirm = ConfirmRequest(
            title = "清除数据",
            message = "确认清除 ${app.packageName} 的全部数据？\n\n此操作不可撤销。",
            confirmText = "清除"
        ) {
            confirm = null
            scope.launch {
                busyPackage = app.packageName
                val res = adbService.clearData(app.packageName)
                busyPackage = null
                actionResult = res.output
            }
        }
    }

    fun downloadApk(app: AppInfoEx) {
        scope.launch {
            busyPackage = app.packageName
            actionResult = "正在拉取 ${app.displayName} 的安装包..."
            val saveDir = localSaveDir()
            val savePath = File(saveDir, "${app.packageName}.apk").absolutePath

            var apkPath = app.apkPath
            val pmRes = adbService.shell("pm path ${app.packageName}")
            if (pmRes.isSuccess) {
                val line = pmRes.stdout.split("\n").firstOrNull { it.startsWith("package:") } ?: ""
                if (line.isNotEmpty()) apkPath = line.removePrefix("package:").trim()
            }

            val res = adbService.pull(apkPath, savePath)
            if (res.isSuccess) {
                adbService.shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$savePath 2>/dev/null || true")
            }
            busyPackage = null
            actionResult = if (res.isSuccess) "✓ 已保存到 $savePath"
            else "✗ 下载失败: ${res.output}\nAPK: $apkPath"
        }
    }

    // ── 推送并安装 ────────────────────────────────────────────────────────────
    fun pushAndInstall(localApkPath: String) {
        scope.launch {
            val fileName = localApkPath.substringAfterLast('/')
            val remotePath = "/data/local/tmp/$fileName"
            loading = true
            actionResult = "正在推送 $fileName..."

            val pushRes = adbService.push(localApkPath, remotePath)
            if (!pushRes.isSuccess) {
                loading = false
                actionResult = "✗ 推送失败: ${pushRes.stderr}"
                return@launch
            }

            actionResult = "正在安装 $fileName..."
            val installRes = adbService.shell("pm install -r \"$remotePath\"", timeoutMs = 120000)
            adbService.shell("rm -f \"$remotePath\" 2>/dev/null || true")

            loading = false
            actionResult = if (installRes.isSuccess) "✓ 安装成功"
            else "✗ 安装失败: ${installRes.output}"
            if (installRes.isSuccess) loadApps()
        }
    }

    // 选择本地 APK 文件（本机文件选择器，对应 Flutter showLocalFilePicker）
    var showApkPicker by remember { mutableStateOf(false) }

    fun installFromLocalFile() {
        showApkPicker = true
    }

    // ── 应用详情 ──────────────────────────────────────────────────────────────
    fun showAppDetails(app: AppInfoEx) {
        dialog = AppsDialog.AppDetails(app)
        detailsData = null
        scope.launch {
            val res = adbService.shell("dumpsys package ${app.packageName} 2>&1")
            val raw = res.stdout
            fun ex(pattern: Regex): String = pattern.find(raw)?.groupValues?.getOrNull(1) ?: "未知"
            detailsData = linkedMapOf(
                "APK 路径" to app.apkPath,
                "版本名" to ex(Regex("versionName=(\\S+)")),
                "版本号" to ex(Regex("versionCode=(\\d+)")),
                "目标 SDK" to ex(Regex("targetSdk=(\\d+)")),
                "最低 SDK" to ex(Regex("minSdk=(\\d+)")),
                "数据目录" to ex(Regex("dataDir=(\\S+)")),
                "安装来源" to ex(Regex("installerPackageName=(\\S+)")),
                "首次安装" to (Regex("firstInstallTime=(.+?)(?:\n|$)").find(raw)?.groupValues?.getOrNull(1)?.trim() ?: "未知"),
                "最近更新" to (Regex("lastUpdateTime=(.+?)(?:\n|$)").find(raw)?.groupValues?.getOrNull(1)?.trim() ?: "未知"),
                "类型" to if (app.isSystem) "系统应用" else "第三方应用",
                "状态" to if (app.isDisabled) "已停用/冻结" else "正常"
            )
        }
    }

    // ── 搜索 ──────────────────────────────────────────────────────────────────
    fun showSearchResults(query: String) {
        if (query.isEmpty()) return
        dialog = AppsDialog.SearchResults(query)
    }

    // ── 构建 ──────────────────────────────────────────────────────────────────
    Scaffold(
        containerColor = Bg0,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showInstallSheet = true },
                containerColor = Primary,
                contentColor = Bg0,
                icon = {
                    Icon(
                        Icons.Rounded.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                text = {
                    Text(
                        "安装",
                        fontFamily = SpaceMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { padding ->
        // WidePageBody：平板限制 1200dp 居中（对应 Flutter WidePageBody）
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        WidePageBody(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
            // 计数徽章
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .padding(top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${filtered.size} packages",
                    fontFamily = SpaceMono,
                    fontSize = 11.sp,
                    color = TextMuted,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.width(8.dp))
                when {
                    showSystem && showThirdParty -> TagChip("全部", TextMuted)
                    showSystem -> TagChip("系统", Secondary)
                    showThirdParty -> TagChip("第三方", Primary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (sortMode == AppsSortMode.PackageName) "↑ 包名" else "↑ 名称",
                    fontFamily = SpaceMono,
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }

            // 加载进度条
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = Color.Transparent
                )
            }

            // 应用列表
            if (filtered.isEmpty()) {
                if (loading) CbeeLoader("加载应用列表...")
                else EmptyState(
                    icon = Icons.Rounded.AppsOutage,
                    title = "暂无应用",
                    message = "当前过滤条件下没有匹配的应用"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(filtered) { i, app ->
                        if (i > 0) {
                            Divider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = Bg3,
                                thickness = 1.dp
                            )
                        }
                        AppListTile(
                            app = app,
                            busy = busyPackage == app.packageName,
                            onTap = { dialog = AppsDialog.AppActions(app) }
                        )
                    }
                }
            }
            }
        }
        FloatingToast(
            message = actionResult,
            ok = actionResult?.let { toastIsOk(it) } ?: true,
            onDismiss = { actionResult = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
        } // Box
    }

    // ── 对话框 ────────────────────────────────────────────────────────────────
    val currentDialog = dialog
    when (currentDialog) {
        is AppsDialog.Search -> SearchDialog(
            onDismiss = { dialog = null },
            onSubmit = { q -> dialog = null; showSearchResults(q) }
        )

        is AppsDialog.SearchResults -> {
            val q = currentDialog.query
            val ql = q.lowercase()
            val results = apps.filter {
                it.packageName.lowercase().contains(ql) ||
                        (it.appLabel?.lowercase()?.contains(ql) == true)
            }
            SearchResultsDialog(
                query = q,
                results = results,
                onDismiss = { dialog = null },
                onTap = { app -> dialog = null; dialog = AppsDialog.AppActions(app) }
            )
        }

        is AppsDialog.Options -> OptionsDialog(
            showSystem = showSystem,
            showThirdParty = showThirdParty,
            sortMode = sortMode,
            onDismiss = { dialog = null },
            onApply = { s, t, m ->
                showSystem = s; showThirdParty = t; sortMode = m
                filtered = applyFilter(apps.toList())
                dialog = null
            },
            onRefresh = { s, t, m ->
                showSystem = s; showThirdParty = t; sortMode = m
                dialog = null
                loadApps()
            },
            onRestore = { s, t, m ->
                showSystem = s; showThirdParty = t; sortMode = m
                dialog = null
                dialog = AppsDialog.Restore
            }
        )

        is AppsDialog.Restore -> {
            val disabled = apps.filter { it.isDisabled || it.isFrozen }
            RestoreAppsDialog(
                disabled = disabled,
                onDismiss = { dialog = null },
                onRestore = { app -> dialog = null; enableApp(app) }
            )
        }

        is AppsDialog.AppActions -> AppActionsDialog(
            app = currentDialog.app,
            onDismiss = { dialog = null },
            onDetails = { app -> dialog = null; showAppDetails(app) },
            onLaunch = { app -> dialog = null; launchApp(app) },
            onFreeze = { app -> dialog = null; freezeApp(app) },
            onDisable = { app -> dialog = null; disableApp(app) },
            onClearData = { app -> dialog = null; clearData(app) },
            onDownload = { app -> dialog = null; downloadApk(app) },
            onUninstall = { app -> dialog = null; uninstall(app) }
        )

        is AppsDialog.AppDetails -> AppDetailsDialog(
            app = currentDialog.app,
            data = detailsData,
            onDismiss = { dialog = null }
        )

        null -> {}
    }

    // 确认弹窗
    confirm?.let { req ->
        ConfirmDialog(
            title = req.title,
            message = req.message,
            confirmText = req.confirmText,
            onDismiss = { confirm = null },
            onConfirm = req.onConfirm
        )
    }

    // 安装方式底部表
    if (showInstallSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showInstallSheet = false },
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
                    Icon(
                        Icons.Rounded.InstallMobile,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "安装 APK",
                        fontFamily = SpaceMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                Divider(color = Bg3, thickness = 1.dp)
                ActionTile(
                    icon = Icons.Rounded.FolderOpen,
                    iconColor = Primary,
                    title = "选择本地文件",
                    subtitle = "在本机目录中选择 APK 文件",
                    onTap = {
                        showInstallSheet = false
                        installFromLocalFile()
                    }
                )
                ActionTile(
                    icon = Icons.Rounded.Apps,
                    iconColor = Secondary,
                    title = "选择本机已安装应用",
                    subtitle = "提取本机已安装应用的 APK 安装到被连接设备",
                    onTap = {
                        showInstallSheet = false
                        showLocalAppsPicker = true
                    }
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    // 本机已安装应用选择
    if (showLocalAppsPicker) {
        LocalAppsPickerDialog(
            onDismiss = { showLocalAppsPicker = false },
            onPick = { apkPath ->
                showLocalAppsPicker = false
                pushAndInstall(apkPath)
            }
        )
    }

    // 本机 APK 文件选择（对应 Flutter showLocalFilePicker）
    if (showApkPicker) {
        LocalFilePickerSheet(
            onDismiss = { showApkPicker = false },
            onConfirm = { paths ->
                showApkPicker = false
                val path = paths.firstOrNull() ?: return@LocalFilePickerSheet
                if (!path.lowercase().endsWith(".apk")) {
                    actionResult = "✗ 请选择 .apk 文件"
                    return@LocalFilePickerSheet
                }
                pushAndInstall(path)
            },
            allowMultiple = false,
            allowFolders = false
        )
    }
}

// ── 应用图标 ──────────────────────────────────────────────────────────────────
@Composable
private fun AppIcon(app: AppInfoEx, size: Int = 42) {
    val bitmap = remember(app.pkg.icon) {
        app.pkg.icon?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }
    val corner = (size * 0.22f).dp
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner))
            .background(Bg2)
            .border(1.dp, Bg3, RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(((size * 0.22f) - 1).coerceAtLeast(0f).dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Rounded.Android,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size((size * 0.52f).dp)
            )
        }
    }
}

// ── 应用列表项 ────────────────────────────────────────────────────────────────
@Composable
private fun AppListTile(app: AppInfoEx, busy: Boolean, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.displayName,
                    modifier = Modifier.weight(1f),
                    fontFamily = SpaceMono,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.isDisabled) {
                    Spacer(Modifier.width(6.dp))
                    TagChip("已停用", Warning)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                app.packageName,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Primary
            )
        } else {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Bg3,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── 辅助组件 ──────────────────────────────────────────────────────────────────
@Composable
private fun TagChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            fontFamily = SpaceMono,
            fontSize = 9.sp,
            color = color
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun DialogCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Bg1,
        border = BorderStroke(1.dp, Bg3)
    ) {
        Column(content = content)
    }
}

@Composable
private fun DialogButtonRow(
    onCancel: () -> Unit,
    onApply: () -> Unit,
    cancelText: String = "取消",
    applyText: String = "应用"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp)
            .padding(top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) {
            Text(
                cancelText,
                fontFamily = SpaceMono,
                fontSize = 12.sp,
                color = TextMuted
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onApply,
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Bg0)
        ) {
            Text(applyText, fontFamily = SpaceMono, fontSize = 12.sp)
        }
    }
}

// ── 搜索弹窗 ──────────────────────────────────────────────────────────────────
@Composable
private fun SearchDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Text(
                "搜索应用",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp).padding(top = 18.dp, bottom = 6.dp),
                fontFamily = SpaceMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(bottom = 4.dp),
                placeholder = { Text("输入包名或应用名称...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(text.trim()) }),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Bg3,
                    focusedContainerColor = Bg0,
                    unfocusedContainerColor = Bg0
                )
            )
            DialogButtonRow(
                onCancel = onDismiss,
                onApply = { onSubmit(text.trim()) },
                applyText = "搜索"
            )
        }
    }
}

// ── 搜索结果弹窗 ──────────────────────────────────────────────────────────────
@Composable
private fun SearchResultsDialog(
    query: String,
    results: List<AppInfoEx>,
    onDismiss: () -> Unit,
    onTap: (AppInfoEx) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "搜索结果  \"$query\"",
                    modifier = Modifier.weight(1f),
                    fontFamily = SpaceMono,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Primary.copy(alpha = 0.1f))
                        .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "${results.size} 个",
                        fontFamily = SpaceMono,
                        fontSize = 11.sp,
                        color = Primary
                    )
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            if (results.isEmpty()) {
                Text(
                    "未找到匹配的应用",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    fontFamily = SpaceMono,
                    fontSize = 13.sp,
                    color = TextMuted
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    itemsIndexed(results) { i, app ->
                        if (i > 0) {
                            Divider(
                                modifier = Modifier.padding(start = 64.dp),
                                color = Bg3,
                                thickness = 1.dp
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTap(app) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.displayName,
                                    fontFamily = SpaceMono,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    app.packageName,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .padding(top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        "关闭",
                        fontFamily = SpaceMono,
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

// ── 选项菜单弹窗 ──────────────────────────────────────────────────────────────
@Composable
private fun OptionsDialog(
    showSystem: Boolean,
    showThirdParty: Boolean,
    sortMode: AppsSortMode,
    onDismiss: () -> Unit,
    onApply: (Boolean, Boolean, AppsSortMode) -> Unit,
    onRefresh: (Boolean, Boolean, AppsSortMode) -> Unit,
    onRestore: (Boolean, Boolean, AppsSortMode) -> Unit
) {
    var sSys by remember { mutableStateOf(showSystem) }
    var sThird by remember { mutableStateOf(showThirdParty) }
    var sort by remember { mutableStateOf(sortMode) }

    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Text(
                "选项",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp).padding(top = 18.dp, bottom = 6.dp),
                fontFamily = SpaceMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(bottom = 4.dp)
            ) {
                // 显示范围
                OptionSectionLabel("显示范围")
                CheckOption("显示系统应用", sSys) { sSys = it }
                CheckOption("显示第三方应用", sThird) { sThird = it }
                Spacer(Modifier.height(12.dp))
                OptionSectionLabel("排序方式")
                RadioOption("包名", sort == AppsSortMode.PackageName) { sort = AppsSortMode.PackageName }
                RadioOption("应用名称", sort == AppsSortMode.AppName) { sort = AppsSortMode.AppName }
                Spacer(Modifier.height(12.dp))
                Divider(color = Bg3, thickness = 1.dp)
                // 刷新列表
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRefresh(sSys, sThird, sort) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("刷新列表", fontFamily = SpaceMono, fontSize = 13.sp, color = TextPrimary)
                }
                // 恢复应用
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRestore(sSys, sThird, sort) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("恢复应用", fontFamily = SpaceMono, fontSize = 13.sp, color = TextPrimary)
                }
            }
            DialogButtonRow(
                onCancel = onDismiss,
                onApply = { onApply(sSys, sThird, sort) }
            )
        }
    }
}

// ── 恢复应用弹窗 ──────────────────────────────────────────────────────────────
@Composable
private fun RestoreAppsDialog(
    disabled: List<AppInfoEx>,
    onDismiss: () -> Unit,
    onRestore: (AppInfoEx) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "恢复应用",
                    modifier = Modifier.weight(1f),
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "${disabled.size} 个已停用",
                    fontFamily = SpaceMono,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
            Divider(color = Bg3, thickness = 1.dp)
            if (disabled.isEmpty()) {
                Text(
                    "没有被冻结或停用的应用",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    fontFamily = SpaceMono,
                    fontSize = 13.sp,
                    color = TextMuted
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(disabled) { i, app ->
                        if (i > 0) {
                            Divider(
                                modifier = Modifier.padding(start = 64.dp),
                                color = Bg3,
                                thickness = 1.dp
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRestore(app) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.displayName,
                                    fontFamily = SpaceMono,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    app.packageName,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Rounded.PlayCircleOutline,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .padding(top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
                }
            }
        }
    }
}

// ── App 操作弹窗 ──────────────────────────────────────────────────────────────
@Composable
private fun AppActionsDialog(
    app: AppInfoEx,
    onDismiss: () -> Unit,
    onDetails: (AppInfoEx) -> Unit,
    onLaunch: (AppInfoEx) -> Unit,
    onFreeze: (AppInfoEx) -> Unit,
    onDisable: (AppInfoEx) -> Unit,
    onClearData: (AppInfoEx) -> Unit,
    onDownload: (AppInfoEx) -> Unit,
    onUninstall: (AppInfoEx) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app, size = 48)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.displayName,
                        fontFamily = SpaceMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        app.packageName,
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        if (app.isSystem) TagChip("系统", Secondary)
                        else TagChip("第三方", Primary)
                        if (app.isDisabled) TagChip("已停用", Warning)
                    }
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.Info, "应用详情", Secondary) { onDetails(app) }
            Divider(modifier = Modifier.padding(start = 56.dp), color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.PlayArrow, "启动", Success) { onLaunch(app) }
            Divider(modifier = Modifier.padding(start = 56.dp), color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.AcUnit, "冻结", Secondary) { onFreeze(app) }
            Divider(modifier = Modifier.padding(start = 56.dp), color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.Block, "停用", Warning) { onDisable(app) }
            Divider(modifier = Modifier.padding(start = 56.dp), color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.CleaningServices, "清除数据", Warning) { onClearData(app) }
            Divider(modifier = Modifier.padding(start = 56.dp), color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.Download, "下载安装包", Primary) { onDownload(app) }
            Divider(modifier = Modifier.padding(start = 56.dp), color = Bg3, thickness = 1.dp)
            ActionItem(Icons.Rounded.DeleteOutline, "卸载", Danger) { onUninstall(app) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, color: Color, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(18.dp))
        Text(
            label,
            fontFamily = SpaceMono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

// ── 应用详情弹窗 ──────────────────────────────────────────────────────────────
@Composable
private fun AppDetailsDialog(
    app: AppInfoEx,
    data: Map<String, String>?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .padding(top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app, size = 42)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.displayName,
                        fontFamily = SpaceMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        app.packageName,
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            if (data == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = Primary)
                }
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    data.forEach { (label, value) ->
                        DetailRow(label, value)
                    }
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .padding(top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(90.dp),
            fontFamily = SpaceMono,
            fontSize = 11.sp,
            color = TextMuted
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            color = TextPrimary
        )
    }
}

// ── 选项小部件 ────────────────────────────────────────────────────────────────
@Composable
private fun OptionSectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(bottom = 4.dp),
        fontFamily = SpaceMono,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextMuted,
        letterSpacing = 1.sp
    )
}

@Composable
private fun CheckOption(label: String, value: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onChanged(!value) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = value,
            onCheckedChange = { onChanged(it) },
            colors = CheckboxDefaults.colors(checkedColor = Primary),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontFamily = SpaceMono, fontSize = 13.sp, color = TextPrimary)
    }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Primary),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontFamily = SpaceMono, fontSize = 13.sp, color = TextPrimary)
    }
}

// ── 确认弹窗 ──────────────────────────────────────────────────────────────────
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg1,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                title,
                fontFamily = SpaceMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Text(
                message,
                fontFamily = SpaceMono,
                fontSize = 13.sp,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
            ) { Text(confirmText, fontFamily = SpaceMono, fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
            }
        }
    )
}

// ── 本机已安装应用选择 ────────────────────────────────────────────────────────
private data class LocalAppEntry(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val icon: Bitmap?
)

private fun drawableToBitmap(drawable: Drawable, size: Int = 72): Bitmap {
    if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }
    val bmp = Bitmap.createBitmap(
        drawable.intrinsicWidth,
        drawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

@Composable
private fun LocalAppsPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LocalAppEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val list = pm.getInstalledApplications(0)
                .mapNotNull { ai ->
                    val src = ai.sourceDir ?: return@mapNotNull null
                    if (src.isEmpty()) return@mapNotNull null
                    val bmp = runCatching {
                        val d = ai.loadIcon(pm)
                        drawableToBitmap(d)
                    }.getOrNull()
                    LocalAppEntry(
                        label = ai.loadLabel(pm).toString(),
                        packageName = ai.packageName,
                        apkPath = src,
                        icon = bmp
                    )
                }
                .sortedBy { it.label.lowercase() }
            entries = list
            loaded = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Text(
                "选择本机应用",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp).padding(top = 18.dp, bottom = 8.dp),
                fontFamily = SpaceMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Divider(color = Bg3, thickness = 1.dp)
            if (!loaded) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = Primary)
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.apkPath) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LocalAppIcon(entry.icon)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.label,
                                    fontFamily = SpaceMono,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    entry.packageName,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Divider(color = Bg3, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .padding(top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun LocalAppIcon(icon: Bitmap?) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Bg2)
            .border(1.dp, Bg3, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Rounded.Android, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
        }
    }
}
