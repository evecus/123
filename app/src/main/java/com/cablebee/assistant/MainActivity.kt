package com.cablebee.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cablebee.assistant.models.AdbDevice
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.services.BinaryManager
import com.cablebee.assistant.services.FastbootService
import com.cablebee.assistant.services.PkgServerService
import com.cablebee.assistant.services.ScrcpyManager
import com.cablebee.assistant.services.SelfPairManager
import com.cablebee.assistant.services.UsbService
import com.cablebee.assistant.ui.screens.*
import com.cablebee.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val adbService = AdbService()
    private val fastbootService = FastbootService()
    private val scrcpyManager by lazy { ScrcpyManager(adbService, applicationContext) }
    private val pkgServerService by lazy { PkgServerService(adbService, applicationContext) }
    private lateinit var usbService: UsbService
    private lateinit var selfPairManager: SelfPairManager

    // Android 13+ 通知权限申请（配对本机通知栏用）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BinaryManager.initialize(applicationContext)
        usbService = UsbService(applicationContext)
        usbService.initialize()
        // FastbootService 必须初始化（注册 USB 权限广播 + 持有 appContext）
        fastbootService.initialize(applicationContext)
        selfPairManager = SelfPairManager(applicationContext, adbService)
        selfPairManager.register()
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch { adbService.startServer() }

        enableEdgeToEdge()
        setContent {
            CableBeeTheme {
                // 启动闪屏（对应 Flutter _InitGate）：初始化完成后进入主界面
                var ready by remember { mutableStateOf(false) }
                if (ready) {
                    App()
                } else {
                    InitGate(onReady = { ready = true })
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @Composable
    private fun App() {
        var screen by remember { mutableStateOf("home") }
        var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }

        // 系统返回键：非主页时先回到主页（对应 Flutter 路由自动返回）
        BackHandler(enabled = screen != "home") {
            screen = "home"
        }

        when (screen) {
            "home" -> HomeScreen(
                adbService = adbService,
                selfPairManager = selfPairManager,
                onDeviceClick = { device ->
                    selectedDevice = device
                    adbService.selectDevice(device)
                    screen = "device"
                },
                onFastbootClick = { screen = "fastboot" },
                onSettingsClick = { screen = "settings" }
            )
            "device" -> {
                val device = selectedDevice
                if (device != null) {
                    DeviceScreen(
                        device = device,
                        adbService = adbService,
                        scrcpyManager = scrcpyManager,
                        pkgServerService = pkgServerService,
                        onBack = { screen = "home" }
                    )
                }
            }
            "fastboot" -> FastbootScreen(
                fastbootService = fastbootService,
                onBack = { screen = "home" }
            )
            "settings" -> SettingsScreen(
                adbService = adbService,
                context = LocalContext.current,
                onBack = { screen = "home" }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // USB 插拔事件：刷新设备列表（对应 Flutter 原生 onNewIntent → usb_events）
        when (intent.action) {
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED,
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> usbService.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // App 回到前台：刷新设备状态（对应 Flutter didChangeAppLifecycleState.resumed）
        lifecycleScope.launch { adbService.refreshDevices() }
        usbService.refresh()
        selfPairManager.onAppForeground()
    }

    override fun onDestroy() {
        adbService.stopPolling()
        usbService.release()
        fastbootService.release()
        selfPairManager.release()
        super.onDestroy()
    }
}

// ── Init Gate：启动闪屏（对应 Flutter main.dart _InitGate）──────────────────────

@Composable
fun InitGate(onReady: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        // 与 Flutter 一致：初始化后固定展示 600ms 再进入主页
        delay(600)
        onReady()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = 500)) +
                scaleIn(initialScale = 0.85f, animationSpec = tween(durationMillis = 600))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Primary.copy(alpha = 0.12f))
                        .border(1.5.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Cable,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "CableBee",
                    fontFamily = SpaceMono,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.W700,
                    color = TextPrimary,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    "ADB Assistant",
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(52.dp))

                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Primary
                )
            }
        }
    }
}
