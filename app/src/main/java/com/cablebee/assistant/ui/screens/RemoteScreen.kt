package com.cablebee.assistant.ui.screens

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.services.ScrcpyManager
import com.cablebee.assistant.ui.components.CbeeLoader
import com.cablebee.assistant.ui.components.PageBody
import com.cablebee.assistant.ui.components.PickerDialog
import com.cablebee.assistant.ui.components.isTablet
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Android keycode 常量 ──────────────────────────────────────────────────────
private const val KEY_BACK = 4
private const val KEY_HOME = 3
private const val KEY_SWITCH = 187
private const val KEY_MENU = 82

// ── 触摸 action 常量 ──────────────────────────────────────────────────────────
private const val ACTION_DOWN = 0
private const val ACTION_UP = 1
private const val ACTION_MOVE = 2

@Composable
fun RemoteScreen(
    adbService: AdbService,
    scrcpyManager: ScrcpyManager,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val context = LocalContext.current
    var maxSize by remember { mutableIntStateOf(1080) }
    var bitRate by remember { mutableIntStateOf(8) }
    var maxFps by remember { mutableIntStateOf(30) }

    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 设备显示尺寸（crop 后真实尺寸，握手 / 输出格式变化时更新，用于镜像视图排版）
    var devW by remember { mutableIntStateOf(scrcpyManager.deviceWidth) }
    var devH by remember { mutableIntStateOf(scrcpyManager.deviceHeight) }

    // 服务端视频编码尺寸（握手 codecMeta，round8 后）：触摸坐标映射的唯一基准。
    // scrcpy 服务端 PositionMapper 会校验触摸事件中的 screenSize 与该尺寸一致，
    // 不一致时静默丢弃事件，因此不能用布局尺寸（devW/devH）替代。
    var videoW by remember { mutableIntStateOf(scrcpyManager.videoWidth) }
    var videoH by remember { mutableIntStateOf(scrcpyManager.videoHeight) }

    // 镜像视图状态：显示镜像页时才创建用于解码的 TextureView
    var showMirror by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var retriedSafeProfile by remember { mutableStateOf(false) }

    var showSizePicker by remember { mutableStateOf(false) }
    var showBitratePicker by remember { mutableStateOf(false) }
    var showFpsPicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarSuccess by remember { mutableStateOf(true) }

    fun showSnack(msg: String, success: Boolean) {
        snackbarSuccess = success
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun takeScreenshot() {
        scope.launch {
            val prefs = context.getSharedPreferences("cablebee", android.content.Context.MODE_PRIVATE)
            val saveDir = prefs.getString("local_save_path", null) ?: "/sdcard/Download/CableBee"
            val path = "$saveDir/screenshot_${System.currentTimeMillis()}.png"
            // 先在设备上截屏，再 pull 到本机（对应 Flutter _takeScreenshot）
            adbService.shell("mkdir -p $saveDir 2>/dev/null")
            val res = adbService.screenshot(path)
            showSnack(
                if (res.isSuccess) "✓ 截图已保存至 $path" else "✗ 截图失败",
                res.isSuccess
            )
        }
    }

    // ── AppBar 动作：截屏 + 断开（对应 Flutter _pushActions，仅连接时显示）────
    LaunchedEffect(actionsVersion, connected) {
        onActionsChanged {
            if (connected) {
                IconButton(onClick = { takeScreenshot() }) {
                    Icon(
                        Icons.Rounded.Screenshot,
                        contentDescription = "截屏",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                TextButton(onClick = {
                    scrcpyManager.stop()
                    connected = false
                    connecting = false
                    statusMsg = null
                    showMirror = false
                }) {
                    Text(
                        "断开",
                        color = Danger,
                        fontFamily = SpaceMono,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // ── 启动投屏会话（镜像页 TextureView 的 surface 就绪后调用）───────────────
    fun startSession() {
        connecting = true
        errorMsg = null
        statusMsg = "正在初始化..."

        scope.launch {
            val st = textureView?.surfaceTexture
            if (st == null) {
                connecting = false
                errorMsg = "TextureView 未就绪"
                return@launch
            }
            val success = scrcpyManager.start(
                surfaceTexture = st,
                maxSize = maxSize,
                bitRateMbps = bitRate,
                maxFps = maxFps,
                onEvent = { type, data ->
                    when (type) {
                        "status" -> {
                            statusMsg = data["msg"] as? String
                        }
                        "connected" -> {
                            (data["deviceWidth"] as? Int)?.let { devW = it }
                            (data["deviceHeight"] as? Int)?.let { devH = it }
                            (data["videoWidth"] as? Int)?.let { videoW = it }
                            (data["videoHeight"] as? Int)?.let { videoH = it }
                            connected = true
                            connecting = false
                            statusMsg = null
                            errorMsg = null
                        }
                        "resize" -> {
                            // 解码器输出格式变化（16 对齐 crop 差异）：仅显示尺寸变化，
                            // 触摸基准（videoW/videoH）保持不变，无需重连
                            (data["deviceWidth"] as? Int)?.let { devW = it }
                            (data["deviceHeight"] as? Int)?.let { devH = it }
                        }
                        "error" -> {
                            val msg = data["message"] as? String ?: "未知错误"
                            if (msg == "NO_VIDEO_FRAME" && !retriedSafeProfile) {
                                // 无视频帧：自动切换兼容档位重试一次（对应 Flutter _retriedSafeProfile）
                                retriedSafeProfile = true
                                connecting = false
                                statusMsg = "检测到无视频帧，自动切换兼容档位重试..."
                                maxSize = 720
                                bitRate = 4
                                maxFps = 20
                                scrcpyManager.stop()
                                scope.launch {
                                    delay(300)
                                    startSession()
                                }
                            } else {
                                connected = false
                                connecting = false
                                errorMsg = if (msg == "NO_VIDEO_FRAME")
                                    "✗ 设备未输出视频帧（已保存 server 日志）"
                                else
                                    "✗ $msg"
                                showMirror = false
                            }
                        }
                        "stopped" -> {
                            connected = false
                            connecting = false
                            statusMsg = null
                            showMirror = false
                        }
                    }
                }
            )
            if (!success) {
                connected = false
                connecting = false
                if (errorMsg == null) errorMsg = "连接失败"
                showMirror = false
            }
        }
    }

    // surface 就绪即开始连接（避免提前启动导致解码 surface 与显示 surface 不一致）
    LaunchedEffect(textureView) {
        val tv = textureView
        if (tv != null && showMirror && !connecting && !connected) {
            startSession()
        }
    }

    fun doDisconnect() {
        scrcpyManager.stop()
        connected = false
        connecting = false
        statusMsg = null
        showMirror = false
    }

    // Picker Dialogs
    if (showSizePicker) {
        PickerDialog(
            title = "最大分辨率",
            options = listOf(0, 720, 1080, 1440),
            labels = listOf("原始", "720p", "1080p", "1440p"),
            current = maxSize,
            onSelect = { maxSize = it },
            onDismiss = { showSizePicker = false }
        )
    }
    if (showBitratePicker) {
        PickerDialog(
            title = "码率",
            options = listOf(2, 4, 8, 16),
            labels = listOf("2 Mbps", "4 Mbps", "8 Mbps", "16 Mbps"),
            current = bitRate,
            onSelect = { bitRate = it },
            onDismiss = { showBitratePicker = false }
        )
    }
    if (showFpsPicker) {
        PickerDialog(
            title = "最大帧率",
            options = listOf(15, 30, 60),
            labels = listOf("15 fps", "30 fps", "60 fps"),
            current = maxFps,
            onSelect = { maxFps = it },
            onDismiss = { showFpsPicker = false }
        )
    }

    if (showMirror) {
        MirrorView(
            scrcpyManager = scrcpyManager,
            devW = devW,
            devH = devH,
            videoW = videoW,
            videoH = videoH,
            connected = connected,
            connecting = connecting,
            statusMsg = statusMsg,
            onTextureViewReady = { tv -> textureView = tv },
            onTextureViewDestroyed = { textureView = null },
            onDisconnect = { doDisconnect() },
            snackbarHostState = snackbarHostState,
            snackbarSuccess = snackbarSuccess
        )
    } else {
        ConfigPage(
            maxSize = maxSize,
            bitRate = bitRate,
            maxFps = maxFps,
            errorMsg = errorMsg,
            onSizeTap = { showSizePicker = true },
            onBitrateTap = { showBitratePicker = true },
            onFpsTap = { showFpsPicker = true },
            onConnect = {
                // 先切到镜像页（创建解码用 TextureView），surface 就绪后自动开始连接
                retriedSafeProfile = false
                showMirror = true
            }
        )
    }
}

// ── 配置页（对应 Flutter _buildConfigPage）────────────────────────────────────

@Composable
private fun ConfigPage(
    maxSize: Int,
    bitRate: Int,
    maxFps: Int,
    errorMsg: String?,
    onSizeTap: () -> Unit,
    onBitrateTap: () -> Unit,
    onFpsTap: () -> Unit,
    onConnect: () -> Unit
) {
    PageBody(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 顶部卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Bg1)
                    .border(1.dp, Bg3, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Primary.copy(alpha = 0.12f))
                        .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Cast,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "实时投屏控制",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.W700,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "基于 scrcpy 协议的硬件加速投屏\n支持触摸、多点、滚动操作",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 配置项卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Bg1)
                    .border(1.dp, Bg3, RoundedCornerShape(12.dp))
            ) {
                ConfigTile(
                    label = "最大分辨率",
                    value = if (maxSize == 0) "原始" else "${maxSize}p",
                    icon = Icons.Rounded.HighQuality,
                    onTap = onSizeTap
                )
                Divider(color = Bg3, thickness = 1.dp)
                ConfigTile(
                    label = "码率",
                    value = "$bitRate Mbps",
                    icon = Icons.Rounded.Speed,
                    onTap = onBitrateTap
                )
                Divider(color = Bg3, thickness = 1.dp)
                ConfigTile(
                    label = "最大帧率",
                    value = "$maxFps fps",
                    icon = Icons.Rounded.Timer,
                    onTap = onFpsTap
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 连接按钮
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text(
                    "连接",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = SpaceMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W700
                    )
                )
            }

            // 错误信息
            errorMsg?.let { msg ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (msg.startsWith("✗")) msg else "✗ $msg",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        color = Danger
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfigTile(
    label: String,
    value: String,
    icon: ImageVector,
    onTap: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = SpaceMono,
                fontSize = 13.sp,
                color = TextPrimary
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                color = TextSecondary
            )
        )
        if (onTap != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── 投屏画面（对应 Flutter _buildMirrorPage）──────────────────────────────────

@Composable
private fun MirrorView(
    scrcpyManager: ScrcpyManager,
    devW: Int,
    devH: Int,
    videoW: Int,
    videoH: Int,
    connected: Boolean,
    connecting: Boolean,
    statusMsg: String?,
    onTextureViewReady: (TextureView) -> Unit,
    onTextureViewDestroyed: () -> Unit,
    onDisconnect: () -> Unit,
    snackbarHostState: SnackbarHostState,
    snackbarSuccess: Boolean
) {
    val devIsLandscape = devW > devH
    // 手机端 + 横屏被控设备：旋转 90° 铺满竖屏（对应 Flutter RotatedBox quarterTurns:1）
    val shouldRotate = devIsLandscape && !isTablet()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Bg0,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (snackbarSuccess) Primary else Danger,
                    contentColor = Bg0
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 投屏区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.Black)
            ) {
                MirroredTexture(
                    devW = devW,
                    devH = devH,
                    videoW = videoW,
                    videoH = videoH,
                    shouldRotate = shouldRotate,
                    onTextureViewReady = onTextureViewReady,
                    onTextureViewDestroyed = onTextureViewDestroyed,
                    onTouch = { action, pointerId, x, y, pressure ->
                        scrcpyManager.sendTouch(action, pointerId, x, y, pressure)
                    }
                )

                // 连接中 / 状态遮罩
                if (!connected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CbeeLoader(message = statusMsg ?: "等待视频流...")
                    }
                }
            }

            // 底部虚拟导航栏（对应 Flutter _NavBar：菜单/返回/主页/最近任务）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Color(0xFF1A1A1A)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavBtn(icon = Icons.Rounded.Menu, onTap = {
                    scrcpyManager.sendKeycode(0, KEY_MENU)
                    scope.launch { delay(50); scrcpyManager.sendKeycode(1, KEY_MENU) }
                })
                NavBtn(icon = Icons.Rounded.ArrowBack, onTap = {
                    scrcpyManager.sendKeycode(0, KEY_BACK)
                    scope.launch { delay(50); scrcpyManager.sendKeycode(1, KEY_BACK) }
                })
                NavBtn(icon = Icons.Rounded.Circle, onTap = {
                    scrcpyManager.sendKeycode(0, KEY_HOME)
                    scope.launch { delay(50); scrcpyManager.sendKeycode(1, KEY_HOME) }
                })
                NavBtn(icon = Icons.Rounded.CropSquare, onTap = {
                    scrcpyManager.sendKeycode(0, KEY_SWITCH)
                    scope.launch { delay(50); scrcpyManager.sendKeycode(1, KEY_SWITCH) }
                })
            }
        }
    }
}

/**
 * 镜像纹理：保持设备宽高比居中显示；
 * 手机端横屏设备时整体旋转 90°（对应 Flutter RotatedBox + 坐标换算）。
 */
@Composable
private fun MirroredTexture(
    devW: Int,
    devH: Int,
    videoW: Int,
    videoH: Int,
    shouldRotate: Boolean,
    onTextureViewReady: (TextureView) -> Unit,
    onTextureViewDestroyed: () -> Unit,
    onTouch: (action: Int, pointerId: Long, x: Int, y: Int, pressure: Float) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }

        val textureViewFactory: (android.content.Context) -> TextureView = { ctx ->
            TextureView(ctx).also { tv ->
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        onTextureViewReady(tv)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        onTextureViewDestroyed()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }

        if (areaW <= 0f || areaH <= 0f || devW <= 0 || devH <= 0 || videoW <= 0 || videoH <= 0) {
            // 设备尺寸未知（尚未握手）：先铺满占位
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(videoW, videoH, shouldRotate) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val renderSize = Size(size.width.toFloat(), size.height.toFloat())
                                if (renderSize.width <= 0f || renderSize.height <= 0f) continue
                                when (event.type) {
                                    PointerEventType.Press -> event.changes.forEach { change ->
                                        val dev = toDeviceCoords(change.position, renderSize, videoW, videoH, shouldRotate)
                                        onTouch(ACTION_DOWN, change.id.value, dev.x.toInt(), dev.y.toInt(), change.pressure)
                                        change.consume()
                                    }
                                    PointerEventType.Move -> event.changes.forEach { change ->
                                        val dev = toDeviceCoords(change.position, renderSize, videoW, videoH, shouldRotate)
                                        onTouch(ACTION_MOVE, change.id.value, dev.x.toInt(), dev.y.toInt(), change.pressure)
                                        change.consume()
                                    }
                                    PointerEventType.Release -> event.changes.forEach { change ->
                                        val dev = toDeviceCoords(change.position, renderSize, videoW, videoH, shouldRotate)
                                        onTouch(ACTION_UP, change.id.value, dev.x.toInt(), dev.y.toInt(), 0f)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            ) {
                AndroidView(factory = textureViewFactory, modifier = Modifier.fillMaxSize())
            }
            return@BoxWithConstraints
        }

        // 可视区域的宽高比（宽/高）：
        //  - 竖屏设备 < 1，横屏设备 > 1
        //  - 手机端横屏设备会被旋转 90°，可视区域的宽高与设备正好相反
        // 注意：这里必须与 areaAspect 同为「宽/高」，否则竖屏设备会被算成又宽又扁的框。
        val visAspect = if (shouldRotate) devH.toFloat() / devW.toFloat()
                        else devW.toFloat() / devH.toFloat()
        val areaAspect = areaW / areaH
        val renderWpx: Float
        val renderHpx: Float
        if (visAspect > areaAspect) {
            // 可视区域比容器更“宽”：以宽度为准
            renderWpx = areaW
            renderHpx = areaW / visAspect
        } else {
            // 否则以高度为准
            renderHpx = areaH
            renderWpx = areaH * visAspect
        }
        val visualW = with(density) { renderWpx.toDp() }
        val visualH = with(density) { renderHpx.toDp() }

        // 可视区域（含触摸映射），尺寸 = 旋转后的显示尺寸
        Box(
            modifier = Modifier
                .size(visualW, visualH)
                .pointerInput(videoW, videoH, shouldRotate) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val renderSize = Size(size.width.toFloat(), size.height.toFloat())
                            if (renderSize.width <= 0f || renderSize.height <= 0f) continue
                            when (event.type) {
                                PointerEventType.Press -> event.changes.forEach { change ->
                                    val dev = toDeviceCoords(change.position, renderSize, videoW, videoH, shouldRotate)
                                    onTouch(ACTION_DOWN, change.id.value, dev.x.toInt(), dev.y.toInt(), change.pressure)
                                    change.consume()
                                }
                                PointerEventType.Move -> event.changes.forEach { change ->
                                    val dev = toDeviceCoords(change.position, renderSize, videoW, videoH, shouldRotate)
                                    onTouch(ACTION_MOVE, change.id.value, dev.x.toInt(), dev.y.toInt(), change.pressure)
                                    change.consume()
                                }
                                PointerEventType.Release -> event.changes.forEach { change ->
                                    val dev = toDeviceCoords(change.position, renderSize, videoW, videoH, shouldRotate)
                                    onTouch(ACTION_UP, change.id.value, dev.x.toInt(), dev.y.toInt(), 0f)
                                    change.consume()
                                }
                            }
                        }
                    }
                }
        ) {
            // AndroidView 在组合树中的位置固定：设备横竖屏切换时只改变尺寸/旋转，
            // 不会重建 TextureView（重建会让解码器绑定的 surface 失效，画面变黑）。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        if (shouldRotate) {
                            // 预旋转布局：宽高互换后顺时针旋转 90°（对应 Flutter RotatedBox quarterTurns:1）
                            // 用 requiredSize：互换后的宽度超过父容器宽度，普通 size 会被父约束截断
                            Modifier.requiredSize(visualH, visualW).rotate(90f)
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
            ) {
                AndroidView(factory = textureViewFactory, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 渲染坐标 → 设备坐标（与 Flutter _toDev 完全一致）。
 * 注意目标尺寸是服务端视频编码尺寸（握手 codecMeta，round8 后），不是显示尺寸：
 * scrcpy 服务端 PositionMapper 以此校验并映射到真实屏幕。
 */
private fun toDeviceCoords(
    local: Offset,
    renderSize: Size,
    devW: Int,
    devH: Int,
    isLandscape: Boolean
): Offset {
    return if (isLandscape) {
        val scaleX = devW / renderSize.height
        val scaleY = devH / renderSize.width
        Offset(
            local.y * scaleX,
            (renderSize.width - local.x) * scaleY
        )
    } else {
        val scaleX = devW / renderSize.width
        val scaleY = devH / renderSize.height
        Offset(local.x * scaleX, local.y * scaleY)
    }
}

@Composable
private fun NavBtn(icon: ImageVector, onTap: () -> Unit) {
    IconButton(
        onClick = onTap,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}
