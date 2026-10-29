package com.cablebee.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.ui.components.CbeeLoader
import com.cablebee.assistant.ui.components.WidePageBody
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceInfoScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    var info by remember { mutableStateOf<Map<String, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var didLoad by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // AppBar 动作：信息页无动作（对应 Flutter refreshActions → []）
    LaunchedEffect(actionsVersion) {
        onActionsChanged {}
    }

    val pullOffset = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }

    fun load(isRefresh: Boolean = false) {
        scope.launch {
            if (isRefresh) refreshing = true else loading = true
            info = adbService.getDeviceInfo()
            loading = false
            refreshing = false
            pullOffset.floatValue = 0f
        }
    }

    LaunchedEffect(Unit) {
        if (!didLoad && adbService.hasDevice) {
            didLoad = true
            load()
        }
    }

    if (loading) {
        CbeeLoader("读取设备信息...")
        return
    }

    if (info == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("暂无信息", fontFamily = SpaceMono, fontSize = 13.sp, color = TextMuted)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { load() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新读取")
            }
        }
        return
    }

    val i = info!!

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && !refreshing) {
                    pullOffset.floatValue = (pullOffset.floatValue + available.y * 0.5f).coerceAtMost(threshold * 1.5f)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullOffset.floatValue > threshold && !refreshing) {
                    pullOffset.floatValue = 0f
                    load(isRefresh = true)
                } else {
                    pullOffset.floatValue = 0f
                }
                return super.onPostFling(consumed, available)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        // WidePageBody：平板限制 1200dp 居中（对应 Flutter WidePageBody）
        WidePageBody(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp)
            ) {
            // ── 设备摘要卡片 ──
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Bg1)
                        .border(1.dp, Bg3, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = Primary, modifier = Modifier.size(30.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            i["model"] ?: "未知设备",
                            fontFamily = SpaceMono,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            i["brand"] ?: i["manufacturer"] ?: "",
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            Badge("Android ${i["android"] ?: "?"}", Success)
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge("API ${i["sdk"] ?: "?"}", Primary)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }

            // ── 信息分组：窄屏纵向堆叠；宽屏（≥640dp）两列排布 ──
            // （对应 Flutter LayoutBuilder twoCol 分支）
            item {
                val groups = listOf<@Composable () -> Unit>(
                    {
                        InfoGroup("硬件", Icons.Rounded.Memory, Primary, listOf(
                            InfoItem("设备型号", i["model"] ?: "无"),
                            InfoItem("品牌", i["brand"] ?: "无"),
                            InfoItem("制造商", i["manufacturer"] ?: "无"),
                            InfoItem("处理器平台", i["platform"] ?: "无"),
                            InfoItem("CPU 架构", i["cpu_abi"] ?: "无"),
                            InfoItem("CPU 核心数", i["cpu_cores"] ?: "无"),
                            InfoItem("分辨率", i["resolution"] ?: "无"),
                            InfoItem("DPI", i["dpi"] ?: "无")
                        ))
                    },
                    {
                        InfoGroup("存储与内存", Icons.Rounded.Storage, Warning, listOf(
                            InfoItem("运行内存", i["memory"] ?: "无"),
                            InfoItem("存储总量", i["storage_total"] ?: "无"),
                            InfoItem("存储剩余", i["storage_free"] ?: "无")
                        ))
                    },
                    {
                        InfoGroup("电池", Icons.Rounded.BatteryChargingFull, Success, listOf(
                            InfoItem("电池电量", i["battery_level"] ?: "无"),
                            InfoItem("电池电压", i["battery_voltage"] ?: "无"),
                            InfoItem("电池温度", i["battery_temp"] ?: "无")
                        ))
                    },
                    {
                        InfoGroup("系统", Icons.Rounded.Android, Success, listOf(
                            InfoItem("Android 版本", i["android"] ?: "无"),
                            InfoItem("SDK 级别", if (i["sdk"] != null) "API ${i["sdk"]}" else "无"),
                            InfoItem("安全补丁日期", i["security_patch"] ?: "无"),
                            InfoItem("内核版本", i["kernel"] ?: "无")
                        ))
                    },
                    {
                        InfoGroup("网络与标识", Icons.Rounded.Wifi, Secondary, listOf(
                            InfoItem("序列号", i["serial"] ?: "无", canCopy = true),
                            InfoItem("IP 地址", i["ip"] ?: "无", canCopy = true),
                            InfoItem("MAC 地址", i["mac"] ?: "无", canCopy = true),
                            InfoItem("Android ID", i["android_id"] ?: "无", canCopy = true)
                        ))
                    },
                )

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val twoCol = maxWidth >= 640.dp
                    if (!twoCol) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            groups.forEachIndexed { idx, group ->
                                group()
                                if (idx < groups.lastIndex) Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    } else {
                        val left = groups.filterIndexed { idx, _ -> idx % 2 == 0 }
                        val right = groups.filterIndexed { idx, _ -> idx % 2 == 1 }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                left.forEachIndexed { idx, group ->
                                    group()
                                    if (idx < left.lastIndex) Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                right.forEachIndexed { idx, group ->
                                    group()
                                    if (idx < right.lastIndex) Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                Text(
                    "下拉刷新",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
            }
        }

        // ── 刷新指示器 ──
        if (refreshing || pullOffset.floatValue > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { pullOffset.floatValue.toDp() })
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Bg1)
                    .border(1.dp, Bg3, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Primary
                    )
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ── 徽标 ──────────────────────────────────────────────────────────────────────

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontFamily = SpaceMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// ── Info Group ────────────────────────────────────────────────────────────────

private data class InfoItem(
    val label: String,
    val value: String,
    val canCopy: Boolean = false
)

@Composable
private fun InfoGroup(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    items: List<InfoItem>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(12.dp))
            }
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                title,
                fontFamily = SpaceMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = iconColor,
                letterSpacing = 0.3.sp
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Bg1)
                .border(1.dp, Bg3, RoundedCornerShape(12.dp))
        ) {
            items.forEachIndexed { idx, item ->
                InfoRow(item.label, item.value, item.canCopy)
                if (idx < items.size - 1) {
                    Divider(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                        thickness = 1.dp,
                        color = Bg3
                    )
                }
            }
        }
    }
}

// ── Info Row ──────────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(label: String, value: String, canCopy: Boolean) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val isMissing = value == "无" || value.isEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canCopy && !isMissing) Modifier.clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                copied = true
            } else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.width(100.dp),
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            color = TextMuted
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            color = if (isMissing) TextMuted else TextPrimary,
            fontWeight = if (isMissing) FontWeight.Normal else FontWeight.Medium,
            fontStyle = if (isMissing) FontStyle.Italic else FontStyle.Normal
        )
        if (canCopy && !isMissing) {
            Icon(
                if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = "复制",
                tint = if (copied) Success else TextMuted,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}
