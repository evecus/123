package com.cablebee.assistant.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cablebee.assistant.models.PkgInfo
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.services.PkgServerService
import com.cablebee.assistant.ui.components.CbeeLoader
import com.cablebee.assistant.ui.components.WidePageBody
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.launch

// ── 数据模型 ─────────────────────────────────────────────────────────────────

private class ProcInfo {
    var pkg: String = ""
    var kbRss: Long = 0
    var label: String? = null
    var iconBytes: ByteArray? = null

    val displayName: String
        get() = if (!label.isNullOrEmpty()) label!! else if (pkg.contains('.')) pkg.split('.').last() else pkg
}

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun ProcessScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var totalKb by remember { mutableStateOf(0L) }
    var usedKb by remember { mutableStateOf(0L) }
    var procs by remember { mutableStateOf<List<ProcInfo>>(emptyList()) }
    var confirmStopPkg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val pkgCache = remember { mutableMapOf<String, PkgInfo>() }
    var pkgServer by remember { mutableStateOf<PkgServerService?>(null) }

    fun load() {
        scope.launch {
            loading = true
            procs = emptyList()
            val server = pkgServer ?: PkgServerService(adbService, context).also { pkgServer = it }

            // 先获取内存信息并立即显示
            val memRes = adbService.shell("cat /proc/meminfo | grep -E \"^MemTotal:|^MemAvailable:\"")
            var total = 0L
            var avail = 0L
            for (line in memRes.stdout.split("\n")) {
                val m = Regex("(\\w+):\\s+(\\d+)").find(line) ?: continue
                val kb = m.groupValues[2].toLongOrNull() ?: 0L
                when (m.groupValues[1]) {
                    "MemTotal" -> total = kb
                    "MemAvailable" -> avail = kb
                }
            }
            totalKb = total
            usedKb = total - avail

            // 获取进程列表（dumpsys meminfo 最可靠）
            val procRes = adbService.shell(
                "dumpsys meminfo 2>/dev/null | grep -E '^\\s+[0-9,]+K:' | " +
                    "sed 's/,//g; s/K://' | awk '{kb=\$1; pkg=\$2; if(pkg~/\\(/)pkg=\$2; print pkg, kb}' | " +
                    "sort -rn -k2 | head -40"
            )

            val list = mutableListOf<ProcInfo>()
            for (line in procRes.stdout.split("\n")) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val pkg = parts[0].replace(Regex("[()]"), "").trim()
                val kb = parts[1].toLongOrNull() ?: 0L
                if (kb <= 0 || pkg.isEmpty() || pkg == "(pid") continue
                list.add(ProcInfo().apply { this.pkg = pkg; this.kbRss = kb })
            }

            if (list.isEmpty()) {
                val psRes = adbService.shell("ps -A -o NAME,RSS 2>/dev/null | tail -n +2")
                for (line in psRes.stdout.split("\n")) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 2) continue
                    val kb = parts.last().toLongOrNull() ?: 0L
                    val name = parts.first()
                    if (kb <= 0 || name.isEmpty()) continue
                    list.add(ProcInfo().apply { this.pkg = name; this.kbRss = kb })
                }
                list.sortByDescending { it.kbRss }
            }

            procs = list.take(40)
            loading = false

            // 用 PkgServer 补充应用名与图标
            for (proc in procs) {
                val cached = pkgCache[proc.pkg]
                if (cached != null) {
                    proc.label = cached.label
                    proc.iconBytes = cached.icon
                }
            }
            val toFetch = procs
                .filter { !pkgCache.containsKey(it.pkg) && it.pkg.contains('.') }
                .map { it.pkg }
                .toSet()
            for (pkgName in toFetch) {
                val info = try {
                    server.getPackage(pkgName)
                } catch (_: Exception) { null } ?: continue
                pkgCache[pkgName] = info
                for (proc in procs) {
                    if (proc.pkg == pkgName) {
                        proc.label = info.label
                        proc.iconBytes = info.icon
                    }
                }
            }
        }
    }

    // ── AppBar 动作：刷新（对应 Flutter _pushActions）────────────────────────
    LaunchedEffect(actionsVersion) {
        onActionsChanged {
            IconButton(onClick = { load() }) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    fun forceStop(pkg: String) {
        scope.launch {
            adbService.forceStop(pkg)
            load()
        }
    }

    fun fmt(kb: Long): String = when {
        kb >= 1024 * 1024 -> "%.2f GB".format(kb / 1024.0 / 1024.0)
        kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
        else -> "$kb KB"
    }

    // ── 下拉刷新（对应 Flutter RefreshIndicator）─────────────────────────────
    val density = LocalDensity.current
    val pullOffset = remember { mutableFloatStateOf(0f) }
    val threshold = with(density) { 64.dp.toPx() }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0f && !loading) {
                    pullOffset.floatValue =
                        (pullOffset.floatValue + available.y * 0.5f).coerceAtMost(threshold * 1.5f)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullOffset.floatValue > threshold && !loading) {
                    pullOffset.floatValue = 0f
                    load()
                } else {
                    pullOffset.floatValue = 0f
                }
                return super.onPostFling(consumed, available)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg0)) {
        // 顶部进度条
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Primary,
                trackColor = Color.Transparent
            )
        }

        if (procs.isEmpty() && loading) {
            CbeeLoader("读取进程...")
        } else {
            WidePageBody(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // ── 设备内存概览 ──
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "设备内存",
                                        fontFamily = SpaceMono,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.W700,
                                        color = TextPrimary
                                    )
                                    Text(
                                        fmt(totalKb),
                                        fontFamily = JetBrainsMono,
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                val ratio = if (totalKb > 0) usedKb.toFloat() / totalKb else 0f
                                val barColor = when {
                                    ratio > 0.85f -> Danger
                                    ratio > 0.65f -> Warning
                                    else -> Primary
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Bg3)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(barColor)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MemTag(barColor, "已用内存", fmt(usedKb))
                                    Spacer(modifier = Modifier.width(24.dp))
                                    MemTag(Bg3, "可用内存", fmt(totalKb - usedKb))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }

                        item { Divider(color = Bg3, thickness = 1.dp) }

                        if (procs.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "暂无进程数据",
                                        fontFamily = SpaceMono,
                                        fontSize = 13.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        } else {
                            items(procs) { p ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProcIcon(p)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            p.displayName,
                                            fontFamily = SpaceMono,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            p.pkg,
                                            fontFamily = JetBrainsMono,
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        fmt(p.kbRss),
                                        fontFamily = JetBrainsMono,
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(onClick = { confirmStopPkg = p.pkg }) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "强制停止",
                                            tint = Primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Divider(
                                    modifier = Modifier.padding(start = 72.dp),
                                    color = Bg3,
                                    thickness = 1.dp
                                )
                            }
                        }
                    }

                    // 刷新指示器
                    if (loading && pullOffset.floatValue > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = with(density) { pullOffset.floatValue.toDp() })
                                .size(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Bg1)
                                .border(1.dp, Bg3, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Primary
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 强制停止确认 ──
    confirmStopPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmStopPkg = null },
            containerColor = Bg1,
            shape = RoundedCornerShape(14.dp),
            title = {
                Text(
                    "强制停止",
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "强制停止 $pkg？",
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmStopPkg = null
                        forceStop(pkg)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    Text("停止", fontFamily = SpaceMono, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopPkg = null }) {
                    Text("取消", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
                }
            }
        )
    }
}

// ── 进程图标 ─────────────────────────────────────────────────────────────────

@Composable
private fun ProcIcon(proc: ProcInfo) {
    val isSystem = proc.pkg.startsWith("com.android") ||
        proc.pkg.startsWith("android") ||
        proc.pkg.startsWith("system")
    val iconColor = if (isSystem) TextMuted else Primary
    val bitmap = remember(proc.iconBytes) {
        proc.iconBytes?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(iconColor.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                if (isSystem) Icons.Rounded.SettingsSuggest else Icons.Rounded.Apps,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── 内存标签 ─────────────────────────────────────────────────────────────────

@Composable
private fun MemTag(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            fontFamily = SpaceMono,
            fontSize = 11.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            value,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}
