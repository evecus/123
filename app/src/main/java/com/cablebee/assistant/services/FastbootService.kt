package com.cablebee.assistant.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.cablebee.assistant.models.FastbootResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Fastboot 服务：直接在 App 进程内通过 [FastbootSession]（USB bulk transfer）
 * 实现 fastboot 协议，**不依赖任何外部 fastboot 二进制**，从根本上规避
 * 「exec 出的子进程无法可靠继承 USB fd / 无法直接 open 设备节点」的问题。
 *
 * 与原 Flutter 项目的 MainActivity.kt + FastbootSession.kt 行为保持一致。
 */
class FastbootService {
    private val TAG = "FastbootService"
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _deviceConnected = MutableStateFlow(false)
    val deviceConnected: StateFlow<Boolean> = _deviceConnected.asStateFlow()

    private val _deviceSerial = MutableStateFlow<String?>(null)
    val deviceSerial: StateFlow<String?> = _deviceSerial.asStateFlow()

    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    /** fastboot 命令执行过程中的 INFO / 进度行实时推送 */
    private val _progress = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val progress: SharedFlow<String> = _progress.asSharedFlow()

    private var session: FastbootSession? = null
    private var sessionDeviceName: String? = null

    private val ACTION_USB_PERMISSION = "com.cablebee.USB_PERMISSION"

    /** 等待 USB 授权后继续执行的 fastboot 命令（对应 Flutter pendingFastbootArgs） */
    private var pendingRunArgs: List<String>? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB permission: granted=$granted device=${device?.deviceName}")

            val args = pendingRunArgs
            pendingRunArgs = null
            if (granted && args != null) {
                // 授权后自动继续执行之前被挂起的命令（对应 Flutter runFastbootWithSession 续跑）
                scope.launch { run(args) }
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // 注册 USB 权限广播，授权后自动续跑挂起的 fastboot 命令
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext?.registerReceiver(
                    usbPermissionReceiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext?.registerReceiver(
                    usbPermissionReceiver,
                    IntentFilter(ACTION_USB_PERMISSION)
                )
            }
        }.onFailure { Log.e(TAG, "register usbPermissionReceiver failed", it) }
    }

    fun release() {
        runCatching { appContext?.unregisterReceiver(usbPermissionReceiver) }
            .onFailure { Log.w(TAG, "unregister usbPermissionReceiver failed", it) }
    }

    // ── 设备发现 ─────────────────────────────────────────────────────────────

    /**
     * 找到处于 fastboot 模式的 USB 设备。
     * 策略（宽松匹配，优先级从高到低）：
     *  1. class=0xFF, subclass=0x42, protocol=0x03  （标准 fastboot）
     *  2. class=0xFF, subclass=0x00, protocol=0x00  （部分 OEM fastboot）
     *  3. class=0xFF, 任意 subclass/protocol         （Vendor Specific 兜底）
     */
    private fun findFastbootDevice(usbManager: UsbManager): UsbDevice? {
        var fallbackDevice: UsbDevice? = null
        for (device in usbManager.deviceList.values) {
            Log.d(TAG, "USB scan: ${device.deviceName} vid=${device.vendorId} pid=${device.productId} ifaces=${device.interfaceCount}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val cls  = iface.interfaceClass
                val sub  = iface.interfaceSubclass
                val prot = iface.interfaceProtocol
                Log.d(TAG, "  iface[$i] class=0x${cls.toString(16)} sub=0x${sub.toString(16)} prot=0x${prot.toString(16)}")
                if (cls == 0xFF && sub == 0x42 && prot == 0x03) {
                    Log.i(TAG, "fastboot device (standard): ${device.deviceName}")
                    return device
                }
                if (cls == 0xFF && sub == 0x00 && prot == 0x00) {
                    Log.i(TAG, "fastboot device (OEM 0x00/0x00): ${device.deviceName}")
                    return device
                }
                if (cls == 0xFF) {
                    fallbackDevice = device
                }
            }
        }
        if (fallbackDevice != null) {
            Log.i(TAG, "fastboot device (fallback vendor-specific): ${fallbackDevice!!.deviceName}")
        } else {
            Log.i(TAG, "no fastboot device found")
        }
        return fallbackDevice
    }

    suspend fun devices(): FastbootResult = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext FastbootResult(-1, "not initialized")
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findFastbootDevice(usbManager) ?: run {
            closeSession()
            _deviceConnected.value = false
            _deviceSerial.value = null
            _needsPermission.value = false
            return@withContext FastbootResult(0, "")
        }

        if (!usbManager.hasPermission(device)) {
            // 主动弹权限框
            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, pi)
            _deviceConnected.value = false
            _deviceSerial.value = null
            _needsPermission.value = true
            return@withContext FastbootResult(0, "(需要 USB 权限，请重试)")
        }

        // 已有权限：打开/复用 session，用 getvar:product 验证
        _needsPermission.value = false
        val s = getOrOpenSession(usbManager, device)
        if (s == null) {
            _deviceConnected.value = false
            _deviceSerial.value = null
            return@withContext FastbootResult(0, "")
        }
        val (exitCode, _) = try { s.runCommand("getvar:product") } catch (e: Exception) {
            Log.e(TAG, "getDevices session error: ${e.message}")
            closeSession()
            return@withContext FastbootResult(0, "")
        }
        val connected = exitCode == 0
        if (!connected) closeSession()
        _deviceConnected.value = connected
        _deviceSerial.value = if (connected) device.deviceName else null
        FastbootResult(0, if (connected) "${device.deviceName}\tfastboot" else "")
    }

    // ── session 管理 ────────────────────────────────────────────────────────

    private fun getOrOpenSession(usbManager: UsbManager, device: UsbDevice): FastbootSession? {
        val existing = session
        if (existing != null && sessionDeviceName == device.deviceName) {
            return existing
        }
        existing?.close()
        session = null
        sessionDeviceName = null
        val s = FastbootSession.open(usbManager, device) ?: return null
        session = s
        sessionDeviceName = device.deviceName
        return s
    }

    private fun closeSession() {
        session?.close()
        session = null
        sessionDeviceName = null
    }

    // ── 执行 fastboot 命令 ──────────────────────────────────────────────────

    /**
     * args 约定（与原 Dart 层 FastbootService 对齐）：
     *   ["getvar", "<var>"]
     *   ["flash", "<partition>", "<localPath>"]
     *   ["erase", "<partition>"]
     *   ["reboot"] / ["reboot", "<target>"]
     *   ["flashing", "unlock"|"lock"]
     *   ["-w"]
     *   其余任意参数：拼成一条 fastboot 命令原样发送
     */
    suspend fun run(args: List<String>): FastbootResult = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext FastbootResult(-1, "not initialized")
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findFastbootDevice(usbManager) ?: run {
            return@withContext FastbootResult(1, "error: no devices/emulators found")
        }

        // 必须先获得 USB 权限
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, pi)
            // 挂起命令，用户授权后由 usbPermissionReceiver 自动续跑（对应 Flutter 行为）
            pendingRunArgs = args
            return@withContext FastbootResult(1, "error: 需要 USB 权限，请在系统弹窗中授权后重试")
        }

        val s = getOrOpenSession(usbManager, device) ?: run {
            return@withContext FastbootResult(1, "error: 无法打开 USB 设备或声明 fastboot 接口")
        }

        // 启动前台 Service，防止 App 进程本身在后台被系统冻结/杀死
        FastbootForegroundService.start(ctx)
        try {
            Log.d(TAG, "fastboot session exec: ${args.joinToString(" ")}")
            _progress.emit("\$ fastboot ${args.joinToString(" ")}")

            val onInfo: (String) -> Unit = { line ->
                Log.d(TAG, "fastboot info: $line")
                _progress.tryEmit(line)
            }

            val (exitCode, output) = if (args.isNotEmpty() && args[0] == "flash" && args.size >= 3) {
                // flash <partition> <localPath>
                s.flash(args[1], File(args[2]), onInfo)
            } else if (args == listOf("-w")) {
                // -w：等价于依次 erase userdata 和 erase cache
                onInfo("Erasing 'userdata'")
                val (c1, o1) = s.runCommand("erase:userdata", onInfo)
                if (c1 != 0) {
                    c1 to o1
                } else {
                    onInfo("Erasing 'cache'")
                    val (c2, o2) = s.runCommand("erase:cache", onInfo)
                    c2 to listOf(o1, o2).filter { it.isNotEmpty() }.joinToString("\n")
                }
            } else {
                val wireCmd = buildWireCommand(args)
                s.runCommand(wireCmd, onInfo)
            }

            Log.d(TAG, "fastboot session result: exit=$exitCode output=${output.take(200)}")

            val isRebootCmd = args.isNotEmpty() && args[0] == "reboot"
            val looksLikeUsbDrop = output.contains("USB read failed") || output.contains("USB write failed")
            if (looksLikeUsbDrop) {
                closeSession()
                if (isRebootCmd) {
                    return@withContext FastbootResult(0, "设备已重启（读取响应时连接按预期断开）")
                }
            }
            FastbootResult(exitCode, output)
        } catch (e: Exception) {
            Log.e(TAG, "fastboot session error: ${e.message}", e)
            closeSession()
            FastbootResult(-1, e.message ?: "fastboot error")
        } finally {
            FastbootForegroundService.stop(ctx)
        }
    }

    /** 把 fastboot 命令行参数数组转换成 AOSP fastboot wire protocol 的命令字符串 */
    private fun buildWireCommand(args: List<String>): String {
        if (args.isEmpty()) return ""
        return when (args[0]) {
            "getvar"   -> "getvar:${args.getOrElse(1) { "all" }}"
            "erase"    -> "erase:${args.getOrElse(1) { "" }}"
            "flashing" -> "flashing:${args.getOrElse(1) { "" }}"
            "reboot"   -> if (args.size >= 2) "reboot-${args[1]}" else "reboot"
            "oem"      -> "oem ${args.drop(1).joinToString(" ")}"
            else       -> args.joinToString(":")
        }
    }

    // ── 命令封装 ──────────────────────────────────────────────────────────────

    suspend fun getVar(variable: String) = run(listOf("getvar", variable))
    suspend fun flash(part: String, path: String) = run(listOf("flash", part, path))
    suspend fun erase(partition: String) = run(listOf("erase", partition))
    suspend fun reboot(target: String? = null) =
        if (target != null) run(listOf("reboot", target)) else run(listOf("reboot"))
    suspend fun oemUnlock() = run(listOf("flashing", "unlock"))
    suspend fun oemLock()   = run(listOf("flashing", "lock"))
    suspend fun wipeData()  = run(listOf("-w"))
    suspend fun runRaw(args: List<String>) = run(args)

    suspend fun getAllVars(): Map<String, String> {
        val r = run(listOf("getvar", "all"))
        val map = mutableMapOf<String, String>()
        for (line in r.output.split("\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }

    /** 返回当前所有已连接 USB 设备的接口参数，用于排查设备识别问题 */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext "not initialized"
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val list = usbManager.deviceList.values
        if (list.isEmpty()) return@withContext "未发现任何 USB 设备"
        val buf = StringBuilder()
        for (device in list) {
            buf.appendLine("设备: ${device.deviceName}")
            buf.appendLine("  VID=0x${device.vendorId.toString(16).uppercase()}  PID=0x${device.productId.toString(16).uppercase()}  权限=${usbManager.hasPermission(device)}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                buf.appendLine("  接口[$i] class=0x${iface.interfaceClass.toString(16).uppercase()} sub=0x${iface.interfaceSubclass.toString(16).uppercase()} prot=0x${iface.interfaceProtocol.toString(16).uppercase()}")
            }
            buf.appendLine()
        }
        buf.toString().trimEnd()
    }
}
