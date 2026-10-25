package com.cablebee.assistant.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.ui.components.ActionTile
import com.cablebee.assistant.ui.components.PageBody
import com.cablebee.assistant.ui.components.SectionHeader
import com.cablebee.assistant.ui.theme.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(adbService: AdbService, context: Context, onBack: () -> Unit) {
    val devices by adbService.devices.collectAsState()
    val serverRunning by adbService.serverRunning.collectAsState()
    val scope = rememberCoroutineScope()

    // ── 本机默认保存路径（对应 Flutter local_save_path）────────────────────────
    val prefs = context.getSharedPreferences("cablebee", Context.MODE_PRIVATE)
    val defaultLocalSavePath = "/sdcard/download/cablebee"
    var localSavePath by remember {
        mutableStateOf(prefs.getString("local_save_path", null) ?: defaultLocalSavePath)
    }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInvalid by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceMono,
                            fontSize = 18.sp,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg0)
            )
        },
        containerColor = Bg0
    ) { padding ->
        PageBody(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {

                // ── 连接 ──
                item { SectionHeader("连接") }

                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                    ) {
                        // Status row
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (serverRunning) Success.copy(alpha = 0.1f)
                                        else TextMuted.copy(alpha = 0.1f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Cable,
                                    contentDescription = null,
                                    tint = if (serverRunning) Success else TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "设备连接",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    if (serverRunning) "${devices.size} 台设备已连接" else "未连接设备",
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = if (serverRunning) Success else TextMuted
                                    )
                                )
                            }
                            // Start/Stop button
                            Button(
                                onClick = {
                                    if (serverRunning) adbService.killServer()
                                    else scope.launch { adbService.startServer() }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (serverRunning) Danger else Primary,
                                    contentColor = Bg0
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    if (serverRunning) "断开全部" else "启动轮询",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                        Divider(color = Bg3)
                        ActionTile(
                            icon = Icons.Rounded.Refresh,
                            title = "刷新设备列表",
                            subtitle = "立即重新扫描已连接设备",
                            iconColor = Warning,
                            onTap = { scope.launch { adbService.refreshDevices() } }
                        )
                    }
                }

                // ── 平台工具 ──
                item { SectionHeader("平台工具") }

                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Success.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Success,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "ADB 协议",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    "原生 Kotlin 实现，无需 adb 进程",
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Divider(color = Bg3)
                        Spacer(modifier = Modifier.height(12.dp))
                        InfoRow("ADB 实现", "原生协议 (Kotlin)")
                        InfoRow("SYNC 支持", "push / pull ✓")
                        InfoRow("fastboot", "v34.0.4 (内置)")
                        InfoRow("最低 Android", "8.0 (API 26)")
                    }
                }

                // ── 文件管理 ──
                item { SectionHeader("文件管理") }

                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Success.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = Success,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "本机默认保存路径",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    localSavePath,
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { showPathDialog = true }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "修改本机路径",
                                    tint = Success,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Divider(color = Bg3)
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "截图、文件下载、APK 提取均保存到此路径。路径无效时自动恢复默认值。",
                                style = TextStyle(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            )
                        }
                    }
                }

                // ── 行为 ──
                item { SectionHeader("行为") }

                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Sensors,
                                contentDescription = null,
                                tint = Secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "自动检测设备",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    "每 3 秒轮询一次已连接设备",
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                )
                            }
                            // 始终开启（对应 Flutter Switch(value: true, onChanged: null)）
                            Switch(
                                checked = true,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Bg0,
                                    checkedTrackColor = Primary
                                )
                            )
                        }
                    }
                }

                // ── 关于 ──
                item { SectionHeader("关于") }

                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Primary.copy(alpha = 0.12f))
                                    .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Cable,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    "CableBee",
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    "ADB Assistant",
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = Bg3)
                        Spacer(modifier = Modifier.height(12.dp))
                        InfoRow("版本", "1.0.0")
                        InfoRow("ADB 协议", "原生实现 (无进程)")
                        InfoRow("fastboot", "v34.0.4 内置")
                        InfoRow("最低系统", "Android 8.0+")
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    // ── 修改本机默认保存路径对话框（对应 Flutter _editLocalSavePath）──────────
    if (showPathDialog) {
        var text by remember { mutableStateOf(localSavePath) }
        Dialog(onDismissRequest = { showPathDialog = false }) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(14.dp),
                color = Bg1,
                border = androidx.compose.foundation.BorderStroke(1.dp, Bg3)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "本机默认保存路径",
                        style = TextStyle(
                            fontFamily = SpaceMono,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = {
                            Text(
                                "本机绝对路径",
                                fontFamily = JetBrainsMono,
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        },
                        placeholder = {
                            Text(
                                defaultLocalSavePath,
                                fontFamily = JetBrainsMono,
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        },
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            color = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Bg3,
                            focusedContainerColor = Bg0,
                            unfocusedContainerColor = Bg0
                        )
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showPathDialog = false }) {
                            Text("取消", fontFamily = SpaceMono, fontSize = 12.sp, color = TextMuted)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val v = text.trim()
                                // 校验：必须以 / 开头且不含连续 //（对应 Flutter _isValidPath）
                                val valid = v.isNotEmpty() && v.startsWith("/") && !v.contains("//")
                                val effective = if (valid) v else defaultLocalSavePath
                                prefs.edit().putString("local_save_path", effective).apply()
                                localSavePath = effective
                                pathInvalid = !valid
                                showPathDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Bg0)
                        ) {
                            Text("保存", fontFamily = SpaceMono, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // 路径无效提示（对应 Flutter SnackBar）
    if (pathInvalid) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "路径无效，已恢复为默认路径", Toast.LENGTH_LONG).show()
            pathInvalid = false
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = SpaceMono,
                fontSize = 11.sp,
                color = TextMuted,
                letterSpacing = 0.3.sp
            )
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = TextSecondary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.6f)
        )
    }
}
