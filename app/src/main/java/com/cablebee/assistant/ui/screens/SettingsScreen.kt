package com.cablebee.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cablebee.assistant.services.AdbService
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

    // ── 保存路径（系统文件夹选择器）──────────────────────────────────────────
    val prefs = context.getSharedPreferences("cablebee", Context.MODE_PRIVATE)
    val defaultLocalSavePath = "/sdcard/Download/CableBee"
    var localSavePath by remember {
        mutableStateOf(prefs.getString("local_save_path", null) ?: defaultLocalSavePath)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 部分设备不支持持久授权，忽略
        }
        val path = treeUriToAbsolutePath(uri)
        if (path != null) {
            prefs.edit().putString("local_save_path", path).apply()
            localSavePath = path
        } else {
            Toast.makeText(context, "无法解析该目录路径，请选择内部存储下的文件夹", Toast.LENGTH_LONG).show()
        }
    }

    // ── 底部导航排序 ──
    var tabOrder by remember { mutableStateOf(loadTabOrder(context)) }
    fun moveTab(from: Int, to: Int) {
        if (to !in tabOrder.indices) return
        val list = tabOrder.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        tabOrder = list
        saveTabOrder(context, list)
    }

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
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // 用系统文件管理器选择文件夹
                                    folderPicker.launch(null)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
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
                                    "保存路径",
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = "选择文件夹",
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // ── 底部导航排序 ──
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SectionHeader("底部导航排序") }

                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg1)
                            .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            "调整左右顺序，进入设备页后生效",
                            style = TextStyle(
                                fontFamily = JetBrainsMono,
                                fontSize = 10.sp,
                                color = TextMuted
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                        Divider(color = Bg3)
                        val byId = DefaultTabItems.associateBy { it.id }
                        tabOrder.forEachIndexed { index, id ->
                            val item = byId[id] ?: return@forEachIndexed
                            if (index > 0) Divider(color = Bg3, modifier = Modifier.padding(start = 56.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(Primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        item.icon,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    item.label,
                                    modifier = Modifier.weight(1f),
                                    style = TextStyle(
                                        fontFamily = SpaceMono,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                )
                                IconButton(
                                    onClick = { moveTab(index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "上移",
                                        tint = if (index > 0) TextSecondary else Bg3,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { moveTab(index, index + 1) },
                                    enabled = index < tabOrder.lastIndex,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "下移",
                                        tint = if (index < tabOrder.lastIndex) TextSecondary else Bg3,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        Divider(color = Bg3)
                        TextButton(
                            onClick = {
                                val def = DefaultTabItems.map { it.id }
                                tabOrder = def
                                saveTabOrder(context, def)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "恢复默认顺序",
                                fontFamily = SpaceMono,
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    }

/**
 * 将系统文件夹选择器返回的 tree URI 转为绝对路径。
 * 支持 primary: 内部存储；其余卷（如 SD 卡）尝试 /storage/<id>/ 映射。
 */
private fun treeUriToAbsolutePath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        when {
            docId.equals("primary", ignoreCase = true) ->
                "/storage/emulated/0"
            docId.startsWith("primary:", ignoreCase = true) -> {
                val rel = docId.substringAfter(":")
                if (rel.isEmpty()) "/storage/emulated/0"
                else "/storage/emulated/0/$rel"
            }
            ":" in docId -> {
                // 例如 XXXX-XXXX:Download → /storage/XXXX-XXXX/Download
                val volume = docId.substringBefore(":")
                val rel = docId.substringAfter(":")
                if (rel.isEmpty()) "/storage/$volume" else "/storage/$volume/$rel"
            }
            else -> null
        }
    } catch (_: Exception) {
        null
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
