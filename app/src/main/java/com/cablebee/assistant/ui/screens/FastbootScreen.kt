package com.cablebee.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.services.FastbootService
import com.cablebee.assistant.ui.components.LocalFilePickerSheet
import com.cablebee.assistant.ui.components.SectionHeader
import com.cablebee.assistant.ui.components.WidePageBody
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Quick Command Data（与 Flutter _quickCmds 完全一致）───────────────────────

private data class QuickCmd(
    val title: String,
    val command: String,
    val icon: ImageVector,
    val color: Color
)

private val QuickCmds = listOf(
    QuickCmd("重启系统", "fastboot reboot", Icons.Rounded.RestartAlt, Success),
    QuickCmd("重启到恢复模式", "fastboot reboot recovery", Icons.Rounded.Build, Warning),
    QuickCmd("重启到 Bootloader 模式", "fastboot reboot bootloader", Icons.Rounded.DeveloperMode, TextMuted),
    QuickCmd("重启到 Fastboot 模式", "fastboot reboot fastboot", Icons.Rounded.FlashOn, Primary),
    QuickCmd("解锁 Bootloader", "fastboot flashing unlock", Icons.Rounded.LockOpen, Danger),
    QuickCmd("回锁 Bootloader", "fastboot flashing lock", Icons.Rounded.Lock, Warning),
    QuickCmd("刷写分区", "fastboot flash ", Icons.Rounded.SystemUpdate, Primary),
    QuickCmd("擦除分区", "fastboot erase ", Icons.Rounded.DeleteForever, Danger),
    QuickCmd("查看设备所有变量", "fastboot getvar all", Icons.Rounded.Info, TextSecondary),
    QuickCmd("列出已连接设备", "fastboot devices", Icons.Rounded.Usb, Success),
    QuickCmd("刷入 boot.img", "fastboot flash boot ", Icons.Rounded.Memory, Primary),
    QuickCmd("刷入 recovery.img", "fastboot flash recovery ", Icons.Rounded.HealthAndSafety, Primary),
    QuickCmd(
        "刷入 vbmeta（禁用校验）",
        "fastboot flash vbmeta --disable-verity --disable-verification ",
        Icons.Rounded.VerifiedUser,
        Warning
    ),
    QuickCmd("擦除 userdata", "fastboot erase userdata", Icons.Rounded.DeleteOutline, Danger),
    QuickCmd("擦除 cache", "fastboot erase cache", Icons.Rounded.CleaningServices, Warning),
    QuickCmd("格式化 userdata", "fastboot format userdata", Icons.Rounded.FormatColorReset, Danger),
    QuickCmd("设置活跃槽位为 a", "fastboot set_active a", Icons.Rounded.SwapHoriz, TextSecondary),
    QuickCmd("设置活跃槽位为 b", "fastboot set_active b", Icons.Rounded.SwapHoriz, TextSecondary),
    QuickCmd("查看电池电量", "fastboot getvar battery-voltage", Icons.Rounded.BatteryFull, Success),
    QuickCmd("查看 bootloader 锁定状态", "fastboot getvar unlocked", Icons.Rounded.Security, TextSecondary),
)

// ── FastbootScreen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastbootScreen(
    fastbootService: FastbootService,
    onBack: () -> Unit
) {
    var cmdText by remember { mutableStateOf("") }
    var outputLines by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val deviceConnected by fastbootService.deviceConnected.collectAsState()
    val deviceSerial by fastbootService.deviceSerial.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showFilePicker by remember { mutableStateOf(false) }

    // 自动轮询 Fastboot 设备（进入页面立即检测一次，之后每 2 秒一次；离开页面自动停止）。
    // 命令执行期间 service 内部会跳过本次检测，不会打断刷机。
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { fastbootService.pollDevices() }
            delay(2000)
        }
    }

    // Collect progress flow
    LaunchedEffect(Unit) {
        fastbootService.progress.collect { line ->
            outputLines = outputLines + line
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    fun scrollToBottom() {
        if (outputLines.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(outputLines.size - 1)
            }
        }
    }

    fun execute() {
        val cmd = cmdText.trim()
        if (cmd.isEmpty()) return
        // 注意：执行后不清空输入框（与 Flutter 一致）

        // Build full command
        var fullCmd = cmd
        if (!cmd.startsWith("fastboot ") && cmd != "fastboot") {
            fullCmd = "fastboot $cmd"
        }
        val parts = fullCmd.split(Regex("\\s+"))
        val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()

        scope.launch {
            busy = true
            val result = if (args.isEmpty()) {
                // getDevices 这条路径没有 progress 事件（即时应答），手动回显命令
                outputLines = outputLines + "$ $cmd"
                fastbootService.devices()
            } else {
                val sub = args[0]
                when {
                    sub == "flash" && args.size >= 3 -> fastbootService.flash(args[1], args.subList(2, args.size).joinToString(" "))
                    sub == "erase" && args.size >= 2 -> fastbootService.erase(args[1])
                    sub == "reboot" -> fastbootService.reboot(if (args.size >= 2) args[1] else null)
                    sub == "flashing" && args.size >= 2 && args[1] == "unlock" -> fastbootService.oemUnlock()
                    sub == "flashing" && args.size >= 2 && args[1] == "lock" -> fastbootService.oemLock()
                    sub == "getvar" && args.size >= 2 -> fastbootService.getVar(args[1])
                    sub == "-w" -> fastbootService.wipeData()
                    else -> fastbootService.run(args)
                }
            }

            val out = result.output.trim()
            busy = false
            if (out.isNotEmpty()) {
                outputLines = outputLines + out.lines()
            }
            outputLines = outputLines + ""
            scrollToBottom()
        }
    }

    fun fillCmd(cmd: String) {
        cmdText = cmd
    }

    fun diagnose() {
        outputLines = outputLines + "[USB 诊断] 读取中..."
        scrollToBottom()
        scope.launch {
            val info = fastbootService.diagnose()
            outputLines = outputLines + listOf("[USB 诊断]") + info.lines()
            outputLines = outputLines + ""
            scrollToBottom()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Fastboot",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = SpaceMono,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.W700,
                            color = TextPrimary
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { outputLines = emptyList() }) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = "清空输出",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg0)
            )
        },
        containerColor = Bg0
    ) { padding ->
        WidePageBody(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 40.dp)
            ) {

                // ── Device Status Card ──
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Bg1)
                            .border(
                                1.dp,
                                if (deviceConnected) Success.copy(alpha = 0.4f) else Warning.copy(alpha = 0.4f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Status icon
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (deviceConnected) Success.copy(alpha = 0.12f)
                                        else Warning.copy(alpha = 0.12f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (deviceConnected) Icons.Rounded.Usb else Icons.Rounded.UsbOff,
                                    contentDescription = null,
                                    tint = if (deviceConnected) Success else Warning,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // Status text
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (deviceConnected) "Fastboot 设备已连接" else "未检测到 Fastboot 设备",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (deviceConnected) Success else Warning
                                    )
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                if (deviceSerial != null) {
                                    Text(
                                        deviceSerial!!,
                                        style = TextStyle(
                                            fontFamily = JetBrainsMono,
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    )
                                } else {
                                    Text(
                                        "通过 USB OTG 连接设备并进入 Fastboot 模式（自动检测）",
                                        style = TextStyle(
                                            fontFamily = JetBrainsMono,
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    )
                                }
                            }
                            // Diagnose button
                            IconButton(
                                onClick = { diagnose() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    contentDescription = "USB 诊断",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // ── Command Input + Terminal Output Card ──
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(14.dp))
                    ) {
                        // Command input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 12.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$",
                                style = TextStyle(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 14.sp,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            OutlinedTextField(
                                value = cmdText,
                                onValueChange = { cmdText = it },
                                placeholder = {
                                    Text(
                                        "fastboot flash recovery ...",
                                        style = TextStyle(
                                            fontFamily = JetBrainsMono,
                                            fontSize = 12.sp,
                                            color = TextMuted
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = false,
                                maxLines = 6,
                                textStyle = TextStyle(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Bg3,
                                    focusedContainerColor = Bg0,
                                    unfocusedContainerColor = Bg0
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { execute() }),
                                trailingIcon = {
                                    if (cmdText.isNotEmpty()) {
                                        IconButton(
                                            onClick = { cmdText = "" },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = "清除",
                                                tint = TextMuted,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        // Buttons row: file picker + execute
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // File picker button（本机文件选择器，对应 Flutter showLocalFilePicker）
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Warning.copy(alpha = 0.1f))
                                    .border(1.dp, Warning.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    .clickable { showFilePicker = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.FolderOpen,
                                    contentDescription = "选择本机文件",
                                    tint = Warning,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Execute button
                            Box(
                                modifier = Modifier
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (busy) Primary.copy(alpha = 0.5f) else Primary)
                                    .clickable(enabled = !busy) { execute() }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (busy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(13.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        if (busy) "执行中" else "执行",
                                        style = TextStyle(
                                            fontFamily = SpaceMono,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Terminal output box
                        TerminalOutputBox(outputLines = outputLines)
                    }
                }

                // ── Quick Commands ──
                item { SectionHeader("快捷命令") }

                items(QuickCmds) { cmd ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { fillCmd(cmd.command) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Icon box
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(cmd.color.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    cmd.icon,
                                    contentDescription = cmd.title,
                                    tint = cmd.color,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // Title + command
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    cmd.title,
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    cmd.command.trimEnd(),
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Copy button
                            CopyButton(text = cmd.command.trimEnd())
                        }
                        if (cmd != QuickCmds.last()) {
                            Divider(
                                color = Bg3,
                                modifier = Modifier.padding(start = 56.dp)
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    // ── 本机文件选择器（对应 Flutter showLocalFilePicker）─────────────────────
    if (showFilePicker) {
        LocalFilePickerSheet(
            onDismiss = { showFilePicker = false },
            onConfirm = { paths ->
                showFilePicker = false
                val path = paths.firstOrNull() ?: return@LocalFilePickerSheet
                // 将选中文件路径追加到命令输入框（对应 Flutter _pickFile）
                val current = cmdText.trimEnd()
                cmdText = if (current.isEmpty()) path else "$current $path"
            },
            allowMultiple = false,
            allowFolders = false
        )
    }
}

// ── Copy Button ──────────────────────────────────────────────────────────────

@Composable
private fun CopyButton(text: String) {
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (copied) Success.copy(alpha = 0.12f) else Bg3.copy(alpha = 0.5f)
            )
            .clickable {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cmd", text))
                copied = true
            }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(
            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = "复制",
            tint = if (copied) Success else TextMuted,
            modifier = Modifier.size(14.dp)
        )
    }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }
}

// ── Terminal Output Box ──────────────────────────────────────────────────────

@Composable
private fun TerminalOutputBox(outputLines: List<String>) {
    val termListState = rememberLazyListState()

    // Auto-scroll terminal
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            termListState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 0.dp)
            .padding(bottom = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(1.5.dp, Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
        ) {
            if (outputLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "等待执行...",
                        style = TextStyle(
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA)
                        )
                    )
                }
            } else {
                LazyColumn(
                    state = termListState,
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                ) {
                    items(outputLines) { line ->
                        val isCmd = line.startsWith("$")
                        val lineColor: Color = when {
                            isCmd -> Primary
                            line.lowercase().contains("error") ||
                                line.lowercase().contains("fail") ||
                                line.lowercase().contains("failed") -> Danger
                            line.lowercase().contains("okay") ||
                                line.lowercase().contains("success") ||
                                line.lowercase().contains("finished") -> Success
                            else -> Color(0xFF1A1A1A)
                        }
                        SelectionContainer {
                            Text(
                                line,
                                style = TextStyle(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 12.sp,
                                    color = lineColor,
                                    lineHeight = 18.sp
                                ),
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
