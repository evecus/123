package com.cablebee.assistant.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.models.AdbDevice
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.services.NetAddress
import com.cablebee.assistant.services.SelfPairManager
import com.cablebee.assistant.ui.components.PageBody
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    adbService: AdbService,
    selfPairManager: SelfPairManager,
    onDeviceClick: (AdbDevice) -> Unit,
    onFastbootClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val devices by adbService.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cablebee", Context.MODE_PRIVATE)
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarSuccess by remember { mutableStateOf(true) }

    var ipText by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var connectMsg by remember { mutableStateOf<String?>(null) }
    var connectingIp by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(loadHistory(prefs)) }

    // bee_logo.png（对应 Flutter Image.asset('assets/bee_logo.png')）
    val logoBitmap = remember {
        runCatching {
            android.graphics.BitmapFactory.decodeStream(context.assets.open("bee_logo.png"))
        }.getOrNull()
    }

    // Refresh devices on first composition (mirrors Flutter addPostFrameCallback)
    LaunchedEffect(Unit) {
        adbService.refreshDevices()
    }

    fun showSnack(msg: String, success: Boolean) {
        snackbarSuccess = success
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun saveHistory(ip: String) {
        val list = history.toMutableList()
        list.remove(ip)
        list.add(0, ip)
        if (list.size > 10) list.subList(10, list.size).clear()
        history = list.toList()
        prefs.edit().putString("ip_history", list.joinToString("\n")).apply()
    }

    fun removeHistory(ip: String) {
        val list = history.filter { it != ip }
        history = list
        prefs.edit().putString("ip_history", list.joinToString("\n")).apply()
    }

    fun connect(overrideIp: String? = null) {
        val raw = (overrideIp ?: ipText).trim()
        if (raw.isEmpty()) return
        // 支持 IPv4 / 域名 / IPv6（[2001:db8::1]:5555 或不带端口的 2001:db8::1）
        val (host, port) = NetAddress.parse(raw) ?: run {
            connectMsg = "地址格式不正确（IPv6 带端口请写成 [地址]:端口）"
            return
        }
        val target = NetAddress.format(host, port)
        scope.launch {
            connecting = true
            connectMsg = null
            connectingIp = target
            val result = adbService.connect(host, port)
            connecting = false
            connectingIp = null
            if (result.isSuccess) {
                saveHistory(target)
                adbService.refreshDevices()
            } else {
                connectMsg = result.stderr
            }
        }
    }

    // ── 配对本机事件（通知栏提交配对码后由 SelfPairManager 推送）──────────────
    LaunchedEffect(Unit) {
        selfPairManager.events.collect { event ->
            when (event.type) {
                "success" -> {
                    val port = event.connectPort
                    if (port != null) {
                        connect(overrideIp = "127.0.0.1:$port")
                    } else {
                        adbService.refreshDevices()
                    }
                    showSnack("配对成功！正在连接本机...", true)
                }
                "error" -> showSnack("配对失败：${event.message}", false)
            }
        }
    }

    var showScanDialog by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    var showSelfPairDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (snackbarSuccess) Success else Danger,
                    contentColor = Color.White
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CableBee",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceMono,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                    )
                },
                actions = {
                    IconButton(onClick = { showSelfPairDialog = true }) {
                        Icon(
                            Icons.Rounded.Phonelink,
                            contentDescription = "配对本机",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { showPairDialog = true }) {
                        Icon(
                            Icons.Rounded.Link,
                            contentDescription = "无线配对",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onFastbootClick) {
                        Icon(
                            Icons.Rounded.FlashOn,
                            contentDescription = "Fastboot",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg0)
            )
        },
        containerColor = Bg0
    ) { padding ->
        // PageBody：手机上铺满，平板限制 720dp 居中（对应 Flutter PageBody）
        PageBody(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
            item { Spacer(modifier = Modifier.height(32.dp)) }

            // Logo（对应 Flutter Image.asset('assets/bee_logo.png')，缺失时回退文本）
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (logoBitmap != null) {
                        Image(
                            bitmap = logoBitmap.asImageBitmap(),
                            contentDescription = "CableBee",
                            modifier = Modifier.height(150.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            "CableBee",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = SpaceMono,
                                fontSize = 34.sp,
                                color = TextPrimary,
                                letterSpacing = (-1).sp
                            )
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }

            // IP input
            item {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "",
                            fontSize = 13.sp,
                            color = TextMuted,
                            fontFamily = JetBrainsMono
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Router,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (ipText.isNotEmpty()) {
                            IconButton(onClick = { ipText = "" }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "清除",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    // 数字键盘没有 ":"、"a-f"、"[ ]"，无法输入 IPv6，改用 URI 键盘
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    keyboardActions = KeyboardActions(onDone = { connect() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Bg3,
                        focusedContainerColor = Bg1,
                        unfocusedContainerColor = Bg1,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }

            // Error message box
            if (connectMsg != null) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Danger.copy(alpha = 0.08f))
                            .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Danger,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            connectMsg!!,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Danger,
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMono
                            )
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(14.dp)) }

            // Scan + Connect buttons
            item {
                Row {
                    OutlinedButton(
                        onClick = { showScanDialog = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, Bg3)
                    ) {
                        Icon(Icons.Rounded.WifiFind, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "扫描",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = SpaceMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { connect() },
                        modifier = Modifier.weight(2f).height(48.dp),
                        enabled = !connecting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Bg0
                        )
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Bg0
                            )
                        } else {
                            Icon(Icons.Rounded.Cable, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (connecting) "连接中..." else "连接",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = SpaceMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }

            // Connected devices
            if (devices.isNotEmpty()) {
                item { SectionLabel("已连接设备", devices.size, Success) }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(devices.distinctBy { it.serial }, key = { "device:${it.serial}" }) { device ->
                    ConnectedDeviceCard(
                        device = device,
                        onTap = { onDeviceClick(device) },
                        onDisconnect = {
                            scope.launch { adbService.disconnect(device.serial) }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }

            // History devices
            if (history.isNotEmpty()) {
                item { SectionLabel("历史设备", history.size) }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(history.distinct(), key = { "history:$it" }) { ip ->
                    GenericDeviceCard(
                        icon = Icons.Rounded.History,
                        iconColor = TextMuted,
                        title = ip,
                        subtitle = if (connectingIp == ip) "等待授权..." else "",
                        connecting = connectingIp == ip,
                        onTap = if (connecting) null else { { connect(ip) } },
                        onDelete = if (connecting) null else { { removeHistory(ip) } }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    if (showScanDialog) {
        ScanDialog(
            onDismiss = { showScanDialog = false },
            onSelected = { ip ->
                showScanDialog = false
                ipText = ip
                connectMsg = null
            }
        )
    }

    if (showPairDialog) {
        PairDialog(
            onDismiss = { showPairDialog = false },
            onPaired = {
                showPairDialog = false
                scope.launch { adbService.refreshDevices() }
            },
            adbService = adbService
        )
    }

    if (showSelfPairDialog) {
        SelfPairDialog(
            onDismiss = { showSelfPairDialog = false },
            onStart = {
                showSelfPairDialog = false
                // 启动 mDNS 监听 + 通知栏配对输入框，并跳转系统无线调试设置页
                // （对应 Flutter MethodChannel('com.cablebee/adb').startSelfPair）
                selfPairManager.start()
            }
        )
    }
}

// ── History persistence helpers ──────────────────────────────────────────────

private fun loadHistory(prefs: SharedPreferences): List<String> {
    val s = prefs.getString("ip_history", "") ?: ""
    return if (s.isEmpty()) emptyList() else s.split("\n")
}

// ── Section Label ────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(label: String, count: Int? = null, color: Color = TextMuted) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = color,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                fontFamily = SpaceMono
            )
        )
        if (count != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = SpaceMono
                    )
                )
            }
        }
    }
}

// ── Connected Device Card ────────────────────────────────────────────────────

@Composable
private fun ConnectedDeviceCard(
    device: AdbDevice,
    onTap: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Bg1)
            .border(1.dp, Success.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Success.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (device.isWifi) Icons.Rounded.Wifi else Icons.Rounded.Usb,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = SpaceMono
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                device.serial,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono
                )
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Success.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                "在线",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Success,
                    fontSize = 10.sp,
                    fontFamily = SpaceMono
                )
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Rounded.LinkOff,
                contentDescription = "断开连接",
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Generic Device Card (for history etc.) ───────────────────────────────────

@Composable
private fun GenericDeviceCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    badge: String? = null,
    badgeColor: Color? = null,
    connecting: Boolean = false,
    onTap: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Bg1)
            .border(
                1.dp,
                if (connecting) Primary.copy(alpha = 0.4f) else Bg3,
                RoundedCornerShape(12.dp)
            )
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background((if (connecting) Primary else iconColor).copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Primary
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = SpaceMono
                )
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (connecting) Primary else TextMuted,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono
                    )
                )
            }
        }
        if (badge != null) {
            val bc = badgeColor ?: TextMuted
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bc.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = bc,
                        fontSize = 10.sp,
                        fontFamily = SpaceMono
                    )
                )
            }
        }
        if (onDelete != null && !connecting) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "删除",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (badge == null && onDelete == null && !connecting) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Self Pair Dialog (配对本机) ──────────────────────────────────────────────

@Composable
private fun SelfPairDialog(
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg1,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Phonelink,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "配对本机",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }
        },
        text = {
            Column {
                StepRow("1", "点击「开始配对」，将自动跳转到系统无线调试设置页，同时通知栏会立即弹出配对输入框")
                Spacer(modifier = Modifier.height(10.dp))
                StepRow("2", "在系统页面点击「使用配对码配对」，等待配对码弹窗出现（不要关闭）")
                Spacer(modifier = Modifier.height(10.dp))
                StepRow("3", "下拉通知栏：若已自动检测到端口，只需输入配对码；否则同时输入端口号和配对码")
                Spacer(modifier = Modifier.height(10.dp))
                StepRow("4", "配对成功后自动连接本机，无需任何额外操作")
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Primary.copy(alpha = 0.06f))
                        .border(1.dp, Primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "需要 Android 11 及以上，且已开启无线调试",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMono,
                            fontSize = 11.sp,
                            color = Primary
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Bg0
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "开始配对",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SpaceMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SpaceMono,
                        color = TextSecondary
                    )
                )
            }
        }
    )
}

@Composable
private fun StepRow(step: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                step,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = SpaceMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 16.5.sp
            )
        )
    }
}

// ── Pair Dialog (无线配对) ───────────────────────────────────────────────────

@Composable
private fun PairDialog(
    onDismiss: () -> Unit,
    onPaired: () -> Unit,
    adbService: AdbService
) {
    var code by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg1,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                "无线配对",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("配对码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = addr,
                    onValueChange = { addr = it },
                    label = { Text("IP 地址和端口") },
                    placeholder = { Text("10.0.0.6:43251 或 [fe80::1]:43251", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                if (result != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val color = if (success) Success else Danger
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.08f))
                            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            result!!,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = color,
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMono
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (code.isBlank() || addr.isBlank()) return@Button
                    // 配对必须带端口；IPv6 请写成 [地址]:端口
                    val (host, port) = NetAddress.parse(addr, defaultPort = null) ?: run {
                        result = "请输入正确格式：IP地址:端口（IPv6：[地址]:端口）"
                        success = false
                        return@Button
                    }
                    pairing = true
                    result = null
                    scope.launch {
                        val res = adbService.pair(host, port, code)
                        pairing = false
                        if (res.isSuccess) {
                            result = "配对成功！"
                            success = true
                            delay(800)
                            onPaired()
                        } else {
                            result = res.stderr
                            success = false
                        }
                    }
                },
                enabled = !pairing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Bg0
                )
            ) {
                if (pairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Bg0
                    )
                } else {
                    Text("配对")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SpaceMono,
                        color = TextSecondary
                    )
                )
            }
        }
    )
}

// ── Scan Dialog (扫描局域网) ─────────────────────────────────────────────────

@Composable
private fun ScanDialog(
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    var scanning by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf("准备扫描...") }
    var scanKey by remember { mutableStateOf(0) }

    LaunchedEffect(scanKey) {
        scanning = true
        found = emptyList()
        status = "正在扫描局域网..."
        try {
            val prefix = withContext(Dispatchers.IO) {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
                var p: String? = null
                for (iface in interfaces) {
                    for (addr in iface.inetAddresses) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.") && !ip.startsWith("169.") &&
                            ip.contains(".") && !ip.contains(":")) {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                p = "${parts[0]}.${parts[1]}.${parts[2]}"
                                break
                            }
                        }
                    }
                    if (p != null) break
                }
                p
            }

            if (prefix == null) {
                scanning = false
                status = "无法获取本机 IP，请手动输入"
                return@LaunchedEffect
            }

            status = "扫描 $prefix.1-254:5555..."

            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    (1..254).map { i ->
                        async {
                            try {
                                val socket = Socket()
                                socket.connect(InetSocketAddress("$prefix.$i", 5555), 300)
                                socket.close()
                                "$prefix.$i:5555"
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }

            val sorted = results.sorted()
            found = sorted
            scanning = false
            status = if (sorted.isEmpty()) "未发现设备，请手动输入 IP" else "发现 ${sorted.size} 个设备"
        } catch (e: Exception) {
            scanning = false
            status = "扫描失败：${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg1,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "扫描局域网",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!scanning) {
                    IconButton(
                        onClick = { scanKey++ },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "重新扫描",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (scanning) {
                    LinearProgressIndicator(
                        color = Primary,
                        trackColor = Bg3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                )
                if (found.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        found.forEachIndexed { index, ip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelected(ip) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Smartphone,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    ip,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                )
                            }
                            if (index < found.size - 1) {
                                Divider(color = Bg3, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SpaceMono,
                        color = TextSecondary
                    )
                )
            }
        }
    )
}
