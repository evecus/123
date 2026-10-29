package com.cablebee.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * 配对本机（mDNS + 通知栏 RemoteInput）——与 Flutter 版 android MainActivity.kt 的
 * self-pair 流程逐行对齐：
 *  1. 监听 _adb-tls-pairing._tcp / _adb-tls-connect._tcp，自动发现配对端口
 *  2. 立即发通知（端口未知时用户输入「端口:配对码」，已知时只输配对码）
 *  3. 收到配对码 → adb pair 127.0.0.1:<port>
 *  4. 成功 → 用 mDNS 已发现的 connect 端口（降级 adb devices）通知 UI 自动连接
 */
class SelfPairManager(
    private val appContext: Context,
    private val adbService: AdbService,
) {
    companion object {
        private const val TAG = "SelfPairManager"
        private const val NOTIF_CHANNEL_ID = "cablebee_self_pair"
        private const val NOTIF_ID = 9001
        private const val NOTIF_REQ_REPLY = 9002
        private const val NOTIF_REQ_CANCEL = 9003
        private const val ACTION_PAIR_CODE = "com.cablebee.ACTION_PAIR_CODE"
        private const val ACTION_CANCEL_PAIR = "com.cablebee.ACTION_CANCEL_PAIR"
        private const val EXTRA_CODE = "pair_code"
        private const val EXTRA_PORT = "pair_port"
        private const val TLS_PAIRING_TYPE = "_adb-tls-pairing._tcp"
        private const val TLS_CONNECT_TYPE = "_adb-tls-connect._tcp"
    }

    data class SelfPairEvent(
        val type: String,          // "success" | "error"
        val message: String,
        val connectPort: Int? = null,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<SelfPairEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SelfPairEvent> = _events.asSharedFlow()

    private var nsdManager: NsdManager? = null
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveredPairPort = -1
    private var discoveredConnectPort = -1
    private var selfPairingActive = false
    private var pairSucceeded = false
    private var receiverRegistered = false

    // ── 广播接收：通知栏 RemoteInput 提交 ──────────────────────────────────────

    private val pairCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAIR_CODE -> {
                    val portFromIntent = intent.getIntExtra(EXTRA_PORT, -1)
                    val results = RemoteInput.getResultsFromIntent(intent)
                    val raw = results?.getCharSequence(EXTRA_CODE)?.toString()?.trim() ?: ""

                    // 解析输入：mDNS 已知端口时 raw 就是配对码；否则格式为「端口:配对码」
                    val port: Int
                    val code: String
                    if (portFromIntent > 0) {
                        port = portFromIntent
                        code = raw
                    } else if (raw.contains(":")) {
                        val parts = raw.split(":", limit = 2)
                        port = parts[0].trim().toIntOrNull() ?: -1
                        code = parts[1].trim()
                    } else {
                        port = if (discoveredPairPort > 0) discoveredPairPort else -1
                        code = raw
                    }

                    Log.i(TAG, "selfPair: got code=$code port=$port from notification (raw=$raw)")
                    if (port > 0 && code.isNotEmpty()) {
                        showPairingInProgressNotification()
                        executeSelfPair(port, code)
                    } else {
                        Log.w(TAG, "selfPair: invalid port=$port or empty code, ignoring")
                        if (selfPairingActive) showPairCodeNotification()
                    }
                }
                ACTION_CANCEL_PAIR -> {
                    Log.i(TAG, "selfPair: cancelled by user")
                    stopSelfPairDiscovery()
                    dismissNotification()
                }
            }
        }
    }

    /** 注册广播（在 MainActivity.onCreate 调用） */
    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_PAIR_CODE)
            addAction(ACTION_CANCEL_PAIR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(pairCodeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(pairCodeReceiver, filter)
        }
        receiverRegistered = true
    }

    fun release() {
        runCatching { if (receiverRegistered) appContext.unregisterReceiver(pairCodeReceiver) }
        receiverRegistered = false
        stopSelfPairDiscovery()
        dismissNotification()
    }

    /** App 回到前台：配对成功后关闭成功通知（对应 Flutter onResume） */
    fun onAppForeground() {
        if (pairSucceeded) {
            pairSucceeded = false
            dismissNotification()
        }
    }

    // ── 端口探测 ──────────────────────────────────────────────────────────────

    /** 绑定失败 = 端口被占用 = adbd 在此端口等待 */
    private fun isPortListening(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: Exception) {
        true
    }

    private fun isLocalHost(hostAddress: String?): Boolean {
        if (hostAddress == null) return false
        if (hostAddress == "127.0.0.1") return true
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.any { it.hostAddress == hostAddress }
                ?: false
        } catch (_: Exception) { false }
    }

    // ── mDNS 发现 ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            scope.launch { _events.emit(SelfPairEvent("error", "需要 Android 11 及以上")) }
            return
        }
        if (selfPairingActive) {
            Log.i(TAG, "selfPair: already active, skip")
            return
        }
        selfPairingActive = true
        discoveredPairPort = -1
        discoveredConnectPort = -1

        ensureNotifChannel()
        nsdManager = appContext.getSystemService(NsdManager::class.java)

        // 立刻发通知——端口未知，让用户手动填端口+配对码
        showPairCodeNotification(port = -1)

        val pairingListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.i(TAG, "selfPair: pairing discovery started")
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "selfPair: pairing discovery start failed code=$code")
            }
            override fun onDiscoveryStopped(type: String) {
                Log.i(TAG, "selfPair: pairing discovery stopped")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "selfPair: pairing discovery stop failed code=$code")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "selfPair: pairing service found ${info.serviceName}")
                nsdManager?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "selfPair: pairing resolve failed code=$code")
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val hostAddr = resolved.host?.hostAddress
                        val port = resolved.port
                        Log.i(TAG, "selfPair: pairing resolved host=$hostAddr port=$port")
                        if (isLocalHost(hostAddr) && isPortListening(port)) {
                            discoveredPairPort = port
                            Log.i(TAG, "selfPair: valid pairing port=$port, updating notification")
                            if (selfPairingActive) showPairCodeNotification()
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "selfPair: pairing service lost")
            }
        }

        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.i(TAG, "selfPair: connect discovery started")
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "selfPair: connect discovery start failed code=$code")
            }
            override fun onDiscoveryStopped(type: String) {
                Log.i(TAG, "selfPair: connect discovery stopped")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "selfPair: connect discovery stop failed code=$code")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "selfPair: connect service found ${info.serviceName}")
                nsdManager?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "selfPair: connect resolve failed code=$code")
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val hostAddr = resolved.host?.hostAddress
                        val port = resolved.port
                        Log.i(TAG, "selfPair: connect resolved host=$hostAddr port=$port")
                        if (isLocalHost(hostAddr) && isPortListening(port)) {
                            discoveredConnectPort = port
                            Log.i(TAG, "selfPair: valid connect port=$port (will use after pairing)")
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "selfPair: connect service lost")
                if (discoveredConnectPort != -1) discoveredConnectPort = -1
            }
        }

        pairingDiscoveryListener = pairingListener
        connectDiscoveryListener = connectListener
        runCatching {
            nsdManager?.discoverServices(TLS_PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, pairingListener)
            nsdManager?.discoverServices(TLS_CONNECT_TYPE, NsdManager.PROTOCOL_DNS_SD, connectListener)
        }.onFailure { Log.e(TAG, "selfPair: discoverServices failed", it) }

        openWirelessDebuggingSettings()
    }

    /** 跳转系统开发者选项无线调试页（与 Flutter 一致：带 toggle_adb_wireless 参数） */
    private fun openWirelessDebuggingSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "selfPair: failed to open dev settings: ${e.message}")
            try {
                appContext.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            } catch (_: Exception) {}
        }
    }

    private fun stopSelfPairDiscovery() {
        selfPairingActive = false
        pairingDiscoveryListener?.let {
            runCatching { nsdManager?.stopServiceDiscovery(it) }
        }
        pairingDiscoveryListener = null
        connectDiscoveryListener?.let {
            runCatching { nsdManager?.stopServiceDiscovery(it) }
        }
        connectDiscoveryListener = null
    }

    // ── 执行配对 ──────────────────────────────────────────────────────────────

    private fun executeSelfPair(port: Int, code: String) {
        scope.launch {
            Log.i(TAG, "selfPair: executing adb pair 127.0.0.1:$port code=$code")
            val pairRes = adbService.pair("127.0.0.1", port, code)
            val ok = pairRes.output.contains("Successfully", ignoreCase = true)
            Log.i(TAG, "selfPair: pair result ok=$ok output=${pairRes.output}")

            if (ok) {
                val connectPort = if (discoveredConnectPort > 0) {
                    Log.i(TAG, "selfPair: using mDNS connect port=$discoveredConnectPort")
                    discoveredConnectPort
                } else {
                    Log.w(TAG, "selfPair: connect port not yet discovered, falling back to adb devices")
                    findConnectPortFromDevices()
                }
                stopSelfPairDiscovery()
                pairSucceeded = true
                showPairSuccessNotification()
                val finalPort = connectPort ?: 5555
                bringAppToForeground()
                // 等 App 回到前台再发事件
                delay(600)
                _events.emit(
                    SelfPairEvent(
                        type = "success",
                        message = "配对成功",
                        connectPort = finalPort,
                    )
                )
            } else {
                dismissNotification()
                _events.emit(
                    SelfPairEvent(
                        type = "error",
                        message = pairRes.output.ifEmpty { "配对失败，请检查配对码" },
                    )
                )
            }
        }
    }

    private fun bringAppToForeground() {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return
        launchIntent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching { appContext.startActivity(launchIntent) }
    }

    /**
     * 降级方案：pair 成功后 adb devices 里找已授权的 127.0.0.1:xxx。
     * 正常情况下 discoveredConnectPort 已由 mDNS 提前拿到，这个函数几乎用不到。
     */
    private suspend fun findConnectPortFromDevices(): Int? {
        // pair 刚完成，adbd 可能还没把新密钥加进 authorized_keys，稍等一下
        delay(1_000)
        val lines = adbService.rawDeviceLines()
        val line = lines.firstOrNull { it.startsWith("127.0.0.1:") && it.contains("\tdevice") }
        return line?.substringBefore("\t")?.substringAfter(":")?.toIntOrNull()
    }

    // ── 通知 ──────────────────────────────────────────────────────────────────

    private fun ensureNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "CableBee 配对本机",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "无线调试配对本机提示"
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /**
     * 发配对通知。
     * discoveredPairPort <= 0 → 单框输入「端口:配对码」格式，如 39987:123456
     * discoveredPairPort >  0 → 单框只输配对码（端口已由 mDNS 自动获取）
     */
    private fun showPairCodeNotification(port: Int = -1) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        val portKnown = discoveredPairPort > 0

        val replyIntent = Intent(ACTION_PAIR_CODE).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_PORT, if (portKnown) discoveredPairPort else -1)
        }
        val replyPi = PendingIntent.getBroadcast(
            appContext,
            NOTIF_REQ_REPLY,
            replyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 始终单个输入框，避免 ColorOS 多 RemoteInput 不工作
        val inputHint = if (portKnown) "配对码（6位数字，如 123456）"
                        else "端口:配对码（如 39987:123456）"
        val singleInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel(inputHint)
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_info, "输入配对码", replyPi
        )
            .addRemoteInput(singleInput)
            .build()

        val cancelIntent = Intent(ACTION_CANCEL_PAIR).apply { setPackage(appContext.packageName) }
        val cancelPi = PendingIntent.getBroadcast(
            appContext,
            NOTIF_REQ_CANCEL,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_info, "取消", cancelPi
        ).build()

        val (title, bigText) = if (portKnown) {
            "CableBee 配对本机（端口 $discoveredPairPort）" to
            "已自动检测到端口 $discoveredPairPort\n点「输入配对码」，输入无线调试弹窗中的6位配对码即可"
        } else {
            "CableBee 配对本机" to
            "点「输入配对码」，按格式输入：\n端口:配对码\n例如：39987:123456\n（端口和配对码均来自无线调试弹窗）"
        }

        val notif = NotificationCompat.Builder(appContext, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (portKnown) "输入6位配对码" else "输入格式：端口:配对码")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .addAction(replyAction)
            .addAction(cancelAction)
            .setOngoing(true)
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    /** 将通知更新为「正在配对中…」，无输入框，防止用户重复提交 */
    private fun showPairingInProgressNotification() {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(appContext, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CableBee 正在配对…")
            .setContentText("正在执行 adb pair，请稍候")
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    /** 配对成功后显示「配对成功」通知，用户返回 App 后自动关闭 */
    private fun showPairSuccessNotification() {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(appContext, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CableBee 配对成功 ✓")
            .setContentText("已成功配对本机，返回 App 即可使用")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun dismissNotification() {
        appContext.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
