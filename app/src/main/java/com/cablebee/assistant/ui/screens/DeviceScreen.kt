package com.cablebee.assistant.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.models.AdbDevice
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.services.NetAddress
import com.cablebee.assistant.services.ScrcpyManager
import com.cablebee.assistant.services.PkgServerService
import com.cablebee.assistant.ui.components.RailWidth
import com.cablebee.assistant.ui.components.isExpanded
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.launch

private data class TabItem(val icon: ImageVector, val label: String, val title: String)

private val tabs = listOf(
    TabItem(Icons.Rounded.Info, "信息", "信息"),
    TabItem(Icons.Rounded.Build, "工具", "工具"),
    TabItem(Icons.Rounded.Apps, "应用", "应用"),
    TabItem(Icons.Rounded.Folder, "文件", "文件"),
    TabItem(Icons.Rounded.Memory, "进程", "进程管理"),
    TabItem(Icons.Rounded.Gamepad, "遥控", "遥控器"),
    TabItem(Icons.Rounded.Cast, "投屏", "远程控制"),
    TabItem(Icons.Rounded.Terminal, "Shell", "Shell"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    device: AdbDevice,
    adbService: AdbService,
    scrcpyManager: ScrcpyManager,
    pkgServerService: PkgServerService,
    onBack: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var disconnectHandled by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    // 各 Tab 推送到 AppBar 的动作（对应 Flutter onActionsChanged 机制）
    var tabActions by remember { mutableStateOf<@Composable (RowScope.() -> Unit)>({}) }
    var actionsVersion by remember { mutableIntStateOf(0) }

    val selectedDevice by adbService.selectedDevice.collectAsState()
    val scope = rememberCoroutineScope()

    // 初始化：选中设备
    LaunchedEffect(device) {
        adbService.selectDevice(device)
    }

    // 监听断连
    LaunchedEffect(selectedDevice) {
        if (selectedDevice == null && !disconnectHandled) {
            disconnectHandled = true
            showDisconnectDialog = true
        }
        if (selectedDevice != null) {
            disconnectHandled = false
        }
    }

    // 断连对话框
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Bg1,
            title = {
                Text(
                    "设备已断开",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                )
            },
            text = {
                Text(
                    "与 ${device.serial} 的连接已中断",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    disconnectHandled = false
                    scope.launch {
                        if (device.isWifi) {
                            val (host, port) = NetAddress.parse(device.serial)
                                ?: (device.serial to NetAddress.DEFAULT_ADB_PORT)
                            adbService.connect(host, port)
                            adbService.selectDevice(device)
                        } else {
                            adbService.refreshDevices()
                            val match = adbService.devices.value.find { it.serial == device.serial }
                            if (match != null) adbService.selectDevice(match)
                        }
                    }
                }) {
                    Text(
                        "重新连接",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = SpaceMono,
                            color = Primary
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    onBack()
                }) {
                    Text(
                        "回到主页",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = SpaceMono,
                            color = TextMuted
                        )
                    )
                }
            }
        )
    }

    // 平板横屏时用左侧导航栏（对应 Flutter _DeviceSideNav）
    val configuration = LocalConfiguration.current
    val useRail = isExpanded() && configuration.screenWidthDp > configuration.screenHeightDp

    val appBar: @Composable () -> Unit = {
        TopAppBar(
            title = {
                Text(
                    tabs[tab].title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.W700,
                        fontFamily = SpaceMono,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBackIosNew,
                        contentDescription = "返回",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            actions = {
                tabActions()
                Spacer(modifier = Modifier.width(4.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg0)
        )
    }

    val onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = { slot ->
        tabActions = slot
    }

    // Tab 内容：已创建过的 Tab 保持组合状态（对应 Flutter IndexedStack），
    // 不可见的 Tab 置为全透明并吞掉触摸事件。
    val tabCreated = remember { mutableStateMapOf<Int, Boolean>() }
    tabCreated[tab] = true

    @Composable
    fun TabBody(index: Int, visible: Boolean) {
        // 关键：可见 Tab 必须在 Z 轴最上层。否则索引更大的隐藏 Tab 的触摸拦截层
        // 会盖在索引更小的可见 Tab 上面，吞掉滑动事件（表现为某页突然无法上下滑动）
        val modifier = if (visible) Modifier.fillMaxSize().zIndex(1f)
        else Modifier.fillMaxSize().zIndex(0f).alpha(0f)
        // 只有当前可见的 Tab 才能向 AppBar 推送动作，避免隐藏 Tab 的动作覆盖当前页（调用时读取最新 tab）
        val onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = { slot ->
            if (index == tab) tabActions = slot
        }
        Box(modifier = modifier) {
            when (index) {
                0 -> DeviceInfoScreen(adbService, onActionsChanged, actionsVersion)
                1 -> ToolsScreen(adbService, onActionsChanged, actionsVersion)
                2 -> AppsScreen(adbService, onActionsChanged, actionsVersion)
                3 -> FilesScreen(adbService, onActionsChanged, actionsVersion)
                4 -> ProcessScreen(adbService, onActionsChanged, actionsVersion)
                5 -> ControllerScreen(adbService, onActionsChanged, actionsVersion)
                6 -> RemoteScreen(adbService, scrcpyManager, onActionsChanged, actionsVersion)
                7 -> ShellScreen(adbService, onActionsChanged, actionsVersion)
            }
            if (!visible) {
                // 拦截隐藏 Tab 的所有指针事件
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }
        }
    }

    if (useRail) {
        Scaffold(
            topBar = appBar,
            containerColor = Bg0
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                DeviceSideNav(
                    tabs = tabs,
                    selectedIndex = tab,
                    onTap = { newTab ->
                        if (tab == 6 && newTab != 6) scrcpyManager.stop()
                        tab = newTab
                        tabActions = {}
                        actionsVersion++
                    }
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Bg3)
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    tabs.forEachIndexed { index, _ ->
                        if (tabCreated[index] == true) {
                            TabBody(index, visible = index == tab)
                        }
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = appBar,
            bottomBar = {
                ScrollableBottomNav(
                    tabs = tabs,
                    selectedIndex = tab,
                    onTap = { newTab ->
                        // 离开投屏 Tab 时自动停止投屏
                        if (tab == 6 && newTab != 6) {
                            scrcpyManager.stop()
                        }
                        tab = newTab
                        tabActions = {}
                        actionsVersion++
                    }
                )
            },
            containerColor = Bg0
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                tabs.forEachIndexed { index, _ ->
                    if (tabCreated[index] == true) {
                        TabBody(index, visible = index == tab)
                    }
                }
            }
        }
    }
}

// ── 平板横屏左侧导航栏（对应 Flutter _DeviceSideNav）──────────────────────────

@Composable
private fun DeviceSideNav(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onTap: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(Bg1)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        tabs.forEachIndexed { i, item ->
            val selected = selectedIndex == i
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onTap(i) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (selected) Primary else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    item.label,
                    fontFamily = SpaceMono,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.W700 else FontWeight.W400,
                    color = if (selected) Primary else TextSecondary
                )
            }
        }
    }
}

// ── 可横向滑动的底部导航栏（对应 Flutter _ScrollableBottomNav）─────────────────

@Composable
private fun ScrollableBottomNav(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onTap: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // 选中项变化时滚动到可视区域中央（对应 Flutter _scrollToSelected）
    LaunchedEffect(selectedIndex) {
        val itemWidthPx = with(density) { 72.dp.toPx() }
        val viewportPx = scrollState.viewportSize.toFloat()
        val target = (selectedIndex * itemWidthPx - viewportPx / 2 + itemWidthPx / 2)
            .coerceIn(0f, scrollState.maxValue.toFloat())
        scrollState.animateScrollTo(target.toInt())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg1)
            // 避让系统手势条/导航栏（边到边模式下底栏否则会与小白条重叠）
            .navigationBarsPadding()
    ) {
        Divider(color = Bg3, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .horizontalScroll(scrollState)
        ) {
            tabs.forEachIndexed { i, item ->
                val selected = selectedIndex == i
                val indicatorWidth by animateDpAsState(
                    targetValue = if (selected) 32.dp else 0.dp,
                    animationSpec = tween(durationMillis = 180),
                    label = "indicator"
                )
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .fillMaxHeight()
                        .clickable { onTap(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(indicatorWidth)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Primary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (selected) Primary else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Primary else TextMuted,
                            fontSize = 9.sp,
                            fontFamily = SpaceMono
                        )
                    )
                }
            }
        }
    }
}
