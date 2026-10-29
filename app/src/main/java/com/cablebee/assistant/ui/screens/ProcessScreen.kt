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
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 数据模型 ─────────────────────────────────────────────────────────────────

private class ProcInfo(val pkg: String, val kbRss: Long) {
    val displayName: String
        get() = if (pkg.contains('.')) pkg.split('.').last() else pkg
}

/** 把同一应用的多个进程（com.foo、com.foo:remote …）合并为一项，按内存降序取前 40。 */
private fun groupAndSort(raw: List<Pair<String, Long>>): List<ProcInfo> {
    val sum = HashMap<String, Long>()
    for ((name, kb) in raw) {
        if (kb <= 0 || name.isEmpty()) continue
        val base = name.substringAfterLast('/').substringBefore(':')
        if (base.isEmpty() || base.startsWith("[")) continue
        sum[base] = (sum[base] ?: 0L) + kb
    }
    return sum.entries
        .sortedByDescending { it.value }
        .take(40)
        .map { ProcInfo(it.key, it.value) }
}

/** `ps -A -o RSS,NAME` 的输出：每行 "<rss_kb> <name>"（Android 6.0+ toybox） */
private fun parsePs(out: String): List<ProcInfo> {
    val raw = ArrayList<Pair<String, Long>>()
    for (line in out.lineSequence()) {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) continue
        val kb = parts[0].toLongOrNull() ?: continue
        raw.add(parts[1] to kb)
    }
    return groupAndSort(raw)
}

/**
 * Android 5.0/5.1 裸 `ps` 输出（toolbox 不支持 -A/-o）：
 *   表头：USER PID PPID VSIZE RSS WCHAN PC NAME
 *   数据：<user> <pid> <ppid> <vsize_kb> <rss_kb> <wchan> <eip> <state> <name>
 * RSS 列 = rss页数×4（4KB 页），单位已是 KB；NAME 从第 9 列起（可能含空格）。
 * 表头行 RSS 列非数字，会被 toLongOrNull 自动跳过。
 */
private fun parsePsLegacy(out: String): List<ProcInfo> {
    val raw = ArrayList<Pair<String, Long>>()
    for (line in out.lineSequence()) {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 9) continue
        val kb = parts[4].toLongOrNull() ?: continue
        val name = parts.subList(8, parts.size).joinToString(" ")
        raw.add(name to kb)
    }
    return groupAndSort(raw)
}

/** dumpsys meminfo 原始输出中的进程行：`  12345K: com.foo (pid 1234 / activities)` */
private fun parseDumpsys(out: String): List<ProcInfo> {
    val regex = Regex("""^\s*([\d,]+)K:\s+(\S+)""")
    val raw = ArrayList<Pair<String, Long>>()
    for (line in out.lineSequence()) {
        val m = regex.find(line) ?: continue
        val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
        val name = m.groupValues[2].replace(Regex("[()]"), "").trim()
        if (name.isEmpty() || name == "(pid") continue
        raw.add(name to kb)
    }
    return groupAndSort(raw)
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

    // 应用名/图标缓存。用 SnapshotStateMap，补全完成后列表会自动重组
    val pkgInfos = remember { mutableStateMapOf<String, PkgInfo>() }
    val pkgMissed = remember { mutableSetOf<String>() }          // 查询失败的包，避免每次刷新都重试
    var pkgServer by remember { mutableStateOf<PkgServerService?>(null) }

    // 防止重复/并发 load：新的 load 会取消旧的，旧的结束时不得改写 loading
    var loadJob by remember { mutableStateOf<Job?>(null) }
    val loadGen = remember { intArrayOf(0) }

    fun load() {
        loadJob?.cancel()
        val myGen = ++loadGen[0]
        loadJob = scope.launch {
            loading = true
            try {
                val server = pkgServer ?: PkgServerService(adbService, context).also { pkgServer = it }

                // 1) 内存概览（shell 已在 IO 线程执行）
                //    Android 5.0 无 grep -E，直接拉全量 /proc/meminfo 本地解析；
                //    MemAvailable 自内核 3.14 起才有，缺失时用 MemFree+Buffers+Cached 近似。
                val memRes = adbService.shell("cat /proc/meminfo 2>/dev/null")
                var total = 0L
                var avail = 0L
                var memFree = 0L
                var memBuffers = 0L
                var memCached = 0L
                for (line in memRes.stdout.split("\n")) {
                    val m = Regex("(\\w+):\\s+(\\d+)").find(line) ?: continue
                    val kb = m.groupValues[2].toLongOrNull() ?: 0L
                    when (m.groupValues[1]) {
                        "MemTotal" -> total = kb
                        "MemAvailable" -> avail = kb
                        "MemFree" -> memFree = kb
                        "Buffers" -> memBuffers = kb
                        "Cached" -> memCached = kb
                    }
                }
                if (avail <= 0L) avail = memFree + memBuffers + memCached
                totalKb = total
                usedKb = total - avail

                // 2) 进程列表：优先用 ps（几十毫秒）。
                //    dumpsys meminfo 在部分 ROM（如 ColorOS）上要数秒，还会解冻所有被冻结的进程，只作兜底。
                //    Android 6.0+ 用 `ps -A -o RSS,NAME`；5.0/5.1 的 toolbox ps 不支持 -A/-o，
                //    只能用裸 ps（列结构不同，由 parsePsLegacy 解析）。
                val sdk = adbService.deviceSdkInt()
                val psOut = if (sdk >= 23) {
                    adbService.shell("ps -A -o RSS,NAME 2>/dev/null | tail -n +2").stdout
                } else {
                    adbService.shell("ps 2>/dev/null").stdout
                }
                var list = withContext(Dispatchers.Default) {
                    if (sdk >= 23) parsePs(psOut) else parsePsLegacy(psOut)
                }
                if (list.isEmpty()) {
                    // 兜底：dumpsys meminfo 全量拉取后本地解析
                    // （5.0 无 grep/sed/awk/sort/head，无法在设备侧做管道过滤）
                    val dumpOut = adbService.shell("dumpsys meminfo 2>/dev/null", timeoutMs = 30_000).stdout
                    list = withContext(Dispatchers.Default) { parseDumpsys(dumpOut) }
                }
                procs = list
                loading = false

                // 3) 后台逐个补全应用名与图标。
                //    每次 getPackage 都会拉起一个 app_process(JVM)，很慢，所以：
                //    只在 IO 线程执行、可随时被新的 load 取消、失败的包不重复查询。
                val toFetch = list.map { it.pkg }
                    .filter { it.contains('.') && !pkgInfos.containsKey(it) && it !in pkgMissed }
                for (name in toFetch) {
                    ensureActive()
                    val info = try {
                        withContext(Dispatchers.IO) { server.getPackage(name) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (info != null) pkgInfos[name] = info else pkgMissed.add(name)
                }
            } finally {
                if (loadGen[0] == myGen) loading = false
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
                            items(procs, key = { it.pkg }) { p ->
                                val info = pkgInfos[p.pkg]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProcIcon(p.pkg, info?.icon)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            info?.label?.takeIf { it.isNotEmpty() } ?: p.displayName,
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
private fun ProcIcon(pkg: String, iconBytes: ByteArray?) {
    val isSystem = pkg.startsWith("com.android") ||
        pkg.startsWith("android") ||
        pkg.startsWith("system")
    val iconColor = if (isSystem) TextMuted else Primary
    val bitmap by produceState<ImageBitmap?>(null, iconBytes) {
        value = iconBytes?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(iconColor.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
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
