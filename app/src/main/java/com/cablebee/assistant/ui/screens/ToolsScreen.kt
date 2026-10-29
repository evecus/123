package com.cablebee.assistant.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.ui.components.FloatingToast
import com.cablebee.assistant.ui.components.WidePageBody
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

// ── Dialog data ──────────────────────────────────────────────────────────────

private data class ConfirmData(
    val title: String,
    val message: String,
    val confirmText: String = "确认",
    val destructive: Boolean = false,
    val onConfirm: () -> Unit
)

private data class InputData(
    val title: String,
    val hint: String,
    val initialValue: String,
    val onConfirm: (String) -> Unit
)

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun ToolsScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(true) }
    var takingScreenshot by remember { mutableStateOf(false) }

    // AppBar 动作：工具页无动作（对应 Flutter refreshActions → []）
    LaunchedEffect(actionsVersion) {
        onActionsChanged {}
    }

    var confirmData by remember { mutableStateOf<ConfirmData?>(null) }
    var inputData by remember { mutableStateOf<InputData?>(null) }
    var showAnimDialog by remember { mutableStateOf(false) }
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showNtpDialog by remember { mutableStateOf(false) }

    fun showResult(msg: String, ok: Boolean = true) {
        resultMsg = msg
        resultOk = ok
    }

    // ── 截图 ──
    fun takeScreenshot() {
        scope.launch {
            takingScreenshot = true
            resultMsg = null
            // 保存目录：优先设置中的 local_save_path，默认 /sdcard/Download/CableBee（与 Flutter 一致）
            val prefs = context.getSharedPreferences("cablebee", Context.MODE_PRIVATE)
            val saveDir = prefs.getString("local_save_path", null) ?: "/sdcard/Download/CableBee"
            runCatching { File(saveDir).mkdirs() }
            val path = "$saveDir/screenshot_${System.currentTimeMillis()}.png"
            val res = adbService.screenshot(path)
            takingScreenshot = false
            showResult(
                if (res.isSuccess && File(path).exists()) "✓ 截图已保存至 $path"
                else "✗ 截图失败：${res.stderr}",
                res.isSuccess && File(path).exists()
            )
        }
    }

    // ── 重启 ──
    fun reboot(mode: String? = null) {
        val (title, message) = when (mode) {
            null -> "重启" to "正常重启系统？"
            "update" -> "重启到更新模式" to "设备将进入 OTA 更新模式"
            "recovery" -> "重启到 Recovery" to "设备将进入 Recovery 模式"
            "fastboot" -> "重启到 Fastboot" to "设备将进入 Fastboot 模式"
            "bootloader" -> "重启到 Bootloader" to "设备将进入 Bootloader 模式，可能触发数据清除"
            else -> "重启" to "确认重启？"
        }
        confirmData = ConfirmData(
            title = title,
            message = message,
            confirmText = "确认",
            destructive = mode == "bootloader",
            onConfirm = {
                scope.launch {
                    adbService.reboot(mode)
                    showResult("$title 命令已发送...")
                }
            }
        )
    }

    // ── 息屏 / 亮屏 ──
    fun screenOff() {
        scope.launch {
            val res = adbService.shell("input keyevent KEYCODE_SLEEP")
            showResult(if (res.isSuccess) "✓ 屏幕已息屏" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    fun screenOn() {
        scope.launch {
            val res = adbService.shell("input keyevent KEYCODE_WAKEUP")
            showResult(if (res.isSuccess) "✓ 屏幕已点亮" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    // ── 墓碑模式 ──
    fun enableTombstone() {
        confirmData = ConfirmData(
            title = "启用墓碑模式",
            message = "启用后系统将记录详细崩溃日志，可能影响性能。确认启用？",
            confirmText = "启用",
            onConfirm = {
                scope.launch {
                    val res = adbService.shell("setprop tombstoned.max_tombstone_count 50")
                    showResult(if (res.isSuccess) "✓ 墓碑模式已启用" else "✗ 失败：${res.stderr}", res.isSuccess)
                }
            }
        )
    }

    // ── 激活第三方工具 ──
    fun activateTool(label: String, cmd: String) {
        confirmData = ConfirmData(
            title = "激活 $label",
            message = "将执行：\n$cmd\n\n请确保该应用已安装。",
            confirmText = "激活",
            onConfirm = {
                scope.launch {
                    val res = adbService.shell(cmd)
                    showResult(if (res.isSuccess) "✓ $label 激活命令已发送" else "✗ 失败：${res.stderr}", res.isSuccess)
                }
            }
        )
    }

    // ── 启用 TCP/IP ──
    fun enableTcpip() {
        inputData = InputData(
            title = "启用 TCP/IP",
            hint = "5555",
            initialValue = "5555",
            onConfirm = { port ->
                scope.launch {
                    val p = port.toIntOrNull() ?: 5555
                    val res = adbService.enableTcpip(p)
                    showResult(if (res.isSuccess) "✓ TCP/IP 已启用，端口 $p" else "✗ 失败：${res.stderr}", res.isSuccess)
                }
            }
        )
    }

    // ── NTP ──
    fun setNtp(server: String) {
        scope.launch {
            val res = adbService.shell("settings put global ntp_server $server")
            showResult(if (res.isSuccess) "✓ NTP 服务器已设为 $server" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    // ── 动画速率 ──
    fun setAnimation(scale: Double) {
        scope.launch {
            val res = adbService.setAnimationScale(scale)
            showResult(
                if (scale == 0.0) "✓ 动画已关闭（最快速度）"
                else "✓ 动画速率已设为 ${"%.1f".format(scale)}x",
                res.isSuccess
            )
        }
    }

    // ── 分辨率 ──
    fun setResolution(w: Int, h: Int) {
        scope.launch {
            val res = adbService.setWmSize(w, h)
            showResult(if (res.isSuccess) "✓ 分辨率已设为 ${w}x$h" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    fun resetResolution() {
        scope.launch {
            val res = adbService.resetWmSize()
            showResult(if (res.isSuccess) "✓ 分辨率已恢复默认" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    // ── DPI ──
    fun setDpi(dpi: Int) {
        scope.launch {
            val res = adbService.setWmDensity(dpi)
            showResult(if (res.isSuccess) "✓ DPI 已设为 $dpi" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    fun resetDpi() {
        scope.launch {
            val res = adbService.resetWmDensity()
            showResult(if (res.isSuccess) "✓ DPI 已恢复默认" else "✗ 失败：${res.stderr}", res.isSuccess)
        }
    }

    // ── UI ──

    // WidePageBody：平板限制 1200dp 居中（对应 Flutter WidePageBody）
    Box(modifier = Modifier.fillMaxSize()) {
    WidePageBody(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)
        ) {
        // ════ 截图 ════
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            ToolGroup(title = "截图", icon = Icons.Rounded.ScreenshotMonitor, iconColor = Primary) {
                ToolTile(
                    icon = Icons.Rounded.Screenshot,
                    iconColor = Primary,
                    title = "截取屏幕",
                    subtitle = "",
                    loading = takingScreenshot,
                    enabled = !takingScreenshot,
                    onTap = { takeScreenshot() }
                )
            }
        }

        // ════ 电源 ════
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            ToolGroup(title = "电源", icon = Icons.Rounded.PowerSettingsNew, iconColor = Danger) {
                ToolTile(Icons.Rounded.RestartAlt, Success, "重启", "") { reboot() }
                ToolDivider()
                ToolTile(Icons.Rounded.SystemUpdateAlt, Primary, "重启到更新模式", "") { reboot("update") }
                ToolDivider()
                ToolTile(Icons.Rounded.BuildCircle, Warning, "重启到 Recovery", "") { reboot("recovery") }
                ToolDivider()
                ToolTile(Icons.Rounded.FlashOn, Warning, "重启到 Fastboot", "") { reboot("fastboot") }
                ToolDivider()
                ToolTile(Icons.Rounded.DeveloperMode, Danger, "重启到 Bootloader", "") { reboot("bootloader") }
            }
        }

        // ════ 屏幕 ════
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            ToolGroup(title = "屏幕", icon = Icons.Rounded.Monitor, iconColor = Secondary) {
                ToolTile(Icons.Rounded.Brightness2, Color(0xFF6C63FF), "息屏待机", "") { screenOff() }
                ToolDivider()
                ToolTile(Icons.Rounded.WbSunny, Warning, "点亮屏幕", "") { screenOn() }
                ToolDivider()
                ToolTile(Icons.Rounded.Animation, Secondary, "修改动画速率", "") { showAnimDialog = true }
                ToolDivider()
                ToolTile(Icons.Rounded.FitScreen, Secondary, "修改屏幕分辨率", "") { showResolutionDialog = true }
                ToolDivider()
                ToolTile(Icons.Rounded.DensityMedium, Secondary, "修改 DPI", "") { showDpiDialog = true }
            }
        }

        // ════ 网络 ════
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            ToolGroup(title = "网络", icon = Icons.Rounded.Wifi, iconColor = Primary) {
                ToolTile(Icons.Rounded.WifiTethering, Primary, "启用 TCP/IP", "") { enableTcpip() }
                ToolDivider()
                ToolTile(Icons.Rounded.AccessTime, Primary, "修改 NTP 服务器", "") { showNtpDialog = true }
            }
        }

        // ════ 系统 ════
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            ToolGroup(title = "系统", icon = Icons.Rounded.Settings, iconColor = TextSecondary) {
                ToolTile(Icons.Rounded.BugReport, Color(0xFF8B5CF6), "启用墓碑模式", "") { enableTombstone() }
            }
        }

        // ════ 激活工具 ════
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            ToolGroup(title = "激活工具", icon = Icons.Rounded.RocketLaunch, iconColor = Color(0xFFE67E22)) {
                ToolTile(Icons.Rounded.Tune, Color(0xFFE67E22), "激活 Scene", "") {
                    activateTool("Scene", "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh")
                }
                ToolDivider()
                ToolTile(Icons.Rounded.Block, Color(0xFF2ECC71), "激活黑域", "") {
                    activateTool("黑域", "sh /data/data/me.piebridge.brevent/brevent.sh")
                }
                ToolDivider()
                ToolTile(Icons.Rounded.VerifiedUser, Color(0xFF3498DB), "激活 Shizuku", "") {
                    activateTool("Shizuku", "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh")
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    FloatingToast(
        message = resultMsg,
        ok = resultOk,
        onDismiss = { resultMsg = null },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 12.dp)
    )
    } // Box

    // ── Dialogs ──

    confirmData?.let { data ->
        ConfirmDialogView(data = data, onDismiss = { confirmData = null })
    }

    inputData?.let { data ->
        InputDialogView(data = data, onDismiss = { inputData = null })
    }

    if (showAnimDialog) {
        AnimationDialog(
            onSet = { scale -> setAnimation(scale); showAnimDialog = false },
            onDismiss = { showAnimDialog = false }
        )
    }

    if (showResolutionDialog) {
        ResolutionDialog(
            onSet = { w, h -> setResolution(w, h); showResolutionDialog = false },
            onReset = { resetResolution(); showResolutionDialog = false },
            onDismiss = { showResolutionDialog = false }
        )
    }

    if (showDpiDialog) {
        DpiDialog(
            onSet = { dpi -> setDpi(dpi); showDpiDialog = false },
            onReset = { resetDpi(); showDpiDialog = false },
            onDismiss = { showDpiDialog = false }
        )
    }

    if (showNtpDialog) {
        NtpDialog(
            onSet = { server -> setNtp(server); showNtpDialog = false },
            onDismiss = { showNtpDialog = false }
        )
    }
}

// ── 工具分组 ─────────────────────────────────────────────────────────────────

@Composable
private fun ToolGroup(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    content: @Composable ColumnScope.() -> Unit
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
                .border(1.dp, Bg3, RoundedCornerShape(12.dp)),
            content = content
        )
    }
}

@Composable
private fun ColumnScope.ToolDivider() {
    Divider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 1.dp,
        color = Bg3
    )
}

// ── 工具条目 ─────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.ToolTile(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    loading: Boolean = false,
    enabled: Boolean = true,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = iconColor
                )
            } else {
                Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = SpaceMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) TextPrimary else TextMuted
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (enabled) TextMuted else Bg3,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ── 确认对话框 ───────────────────────────────────────────────────────────────

@Composable
private fun ConfirmDialogView(data: ConfirmData, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    data.title,
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    data.message,
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { data.onConfirm(); onDismiss() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (data.destructive) Danger else Primary
                        )
                    ) {
                        Text(data.confirmText, fontFamily = SpaceMono, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── 输入对话框 ───────────────────────────────────────────────────────────────

@Composable
private fun InputDialogView(data: InputData, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(data.initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    data.title,
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(data.hint, fontFamily = JetBrainsMono, fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { data.onConfirm(text); onDismiss() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Primary)
                    ) {
                        Text("确认", fontFamily = SpaceMono, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── 动画速率弹窗 ─────────────────────────────────────────────────────────────

@Composable
private fun AnimationDialog(onSet: (Double) -> Unit, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    val presets = listOf(0.0f, 0.5f, 1.0f, 1.5f, 2.0f)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "修改动画速率",
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (scale == 0f) "关闭（最快）" else "${"%.1f".format(scale)}x",
                    fontFamily = JetBrainsMono,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (scale == 0f) Success else Primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
                )
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    valueRange = 0f..3f,
                    steps = 5,
                    colors = SliderDefaults.colors(
                        thumbColor = Primary,
                        activeTrackColor = Primary,
                        inactiveTrackColor = Bg3
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0 = 关闭", fontFamily = JetBrainsMono, fontSize = 10.sp, color = TextMuted)
                    Text("1x = 默认", fontFamily = JetBrainsMono, fontSize = 10.sp, color = TextMuted)
                    Text("3x = 最慢", fontFamily = JetBrainsMono, fontSize = 10.sp, color = TextMuted)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { v ->
                        val selected = scale == v
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Primary else Bg3,
                            modifier = Modifier.clickable { scale = v }
                        ) {
                            Text(
                                if (v == 0f) "关闭" else "${"%.1f".format(v)}x",
                                fontFamily = SpaceMono,
                                fontSize = 11.sp,
                                color = if (selected) Bg0 else TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSet(scale.toDouble()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("应用", fontFamily = SpaceMono)
                    }
                }
            }
        }
    }
}

// ── 分辨率弹窗 ───────────────────────────────────────────────────────────────

@Composable
private fun ResolutionDialog(
    onSet: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var wText by remember { mutableStateOf("") }
    var hText by remember { mutableStateOf("") }
    val presets = listOf(
        "720×1280" to (720 to 1280),
        "1080×1920" to (1080 to 1920),
        "1080×2400" to (1080 to 2400),
        "1440×2560" to (1440 to 2560),
        "1440×3200" to (1440 to 3200)
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "修改屏幕分辨率",
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("快捷预设", fontFamily = JetBrainsMono, fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, dims) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Bg3,
                            modifier = Modifier.clickable {
                                wText = dims.first.toString()
                                hText = dims.second.toString()
                            }
                        ) {
                            Text(
                                label,
                                fontFamily = SpaceMono,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = wText,
                        onValueChange = { wText = it },
                        label = { Text("宽度 (px)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "×",
                        fontSize = 22.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    OutlinedTextField(
                        value = hText,
                        onValueChange = { hText = it },
                        label = { Text("高度 (px)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(onClick = onReset) {
                        Text("恢复默认", fontFamily = SpaceMono)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val w = wText.toIntOrNull()
                            val h = hText.toIntOrNull()
                            if (w != null && h != null) onSet(w, h)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("应用", fontFamily = SpaceMono)
                    }
                }
            }
        }
    }
}

// ── DPI 弹窗 ─────────────────────────────────────────────────────────────────

@Composable
private fun DpiDialog(
    onSet: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val presets = listOf(
        "mdpi\n160" to 160,
        "hdpi\n240" to 240,
        "xhdpi\n320" to 320,
        "xxhdpi\n480" to 480,
        "xxxhdpi\n640" to 640
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "修改 DPI",
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("快捷预设", fontFamily = JetBrainsMono, fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, dpi) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Bg3,
                            modifier = Modifier.clickable { text = dpi.toString() }
                        ) {
                            Text(
                                label,
                                fontFamily = SpaceMono,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("自定义 DPI") },
                    suffix = { Text("dpi") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(onClick = onReset) {
                        Text("恢复默认", fontFamily = SpaceMono)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val dpi = text.toIntOrNull()
                            if (dpi != null && dpi > 0) onSet(dpi)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("应用", fontFamily = SpaceMono)
                    }
                }
            }
        }
    }
}

// ── NTP 服务器弹窗 ───────────────────────────────────────────────────────────

@Composable
private fun NtpDialog(
    onSet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("ntp.aliyun.com") }
    val presets = listOf(
        "阿里云" to "ntp.aliyun.com",
        "腾讯云" to "ntp.tencent.com",
        "Google" to "time.google.com",
        "Cloudflare" to "time.cloudflare.com",
        "国家授时中心" to "ntp.ntsc.ac.cn"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "修改 NTP 服务器",
                    fontFamily = SpaceMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, server) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Bg3,
                            modifier = Modifier.clickable { text = server }
                        ) {
                            Text(
                                label,
                                fontFamily = SpaceMono,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("NTP 服务器地址") },
                    placeholder = { Text("ntp.aliyun.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontFamily = SpaceMono, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val s = text.trim()
                            if (s.isNotEmpty()) onSet(s)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("应用", fontFamily = SpaceMono)
                    }
                }
            }
        }
    }
}
