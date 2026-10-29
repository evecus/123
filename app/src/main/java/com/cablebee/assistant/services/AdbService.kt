package com.cablebee.assistant.services

import android.util.Log
import com.cablebee.assistant.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 全原生实现：通过 jniLibs 中的 libcablebee_adb.so 执行 adb 命令。
 *
 * 与原 Flutter 项目的 MainActivity.kt 行为保持一致：
 *  - 设置 HOME / TMPDIR / ANDROID_ADB_SERVER_PORT=15037 环境变量
 *  - 并行读取 stdout / stderr，避免长输出时管道阻塞
 *  - pair 命令通过 stdin 写入配对码
 *  - connect 命令在等待授权时最多重试 30 次
 */
class AdbService {
    private val TAG = "AdbService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _devices = MutableStateFlow<List<AdbDevice>>(emptyList())
    val devices: StateFlow<List<AdbDevice>> = _devices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AdbDevice?>(null)
    val selectedDevice: StateFlow<AdbDevice?> = _selectedDevice.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    val hasDevice: Boolean get() = _selectedDevice.value != null

    private var missCount = 0
    private var pollJob: Job? = null
    private val connectedSerials = mutableSetOf<String>()

    fun selectDevice(device: AdbDevice?) {
        _selectedDevice.value = device
    }

    suspend fun startServer(): Boolean {
        refreshDevices()
        startPolling()
        return true
    }

    fun killServer() {
        stopPolling()
        scope.launch {
            for (d in _devices.value) {
                runCatching { adb("disconnect", d.serial) }
            }
            _devices.value = emptyList()
            _selectedDevice.value = null
            _serverRunning.value = false
        }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refreshDevices()
                delay(3000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 返回 `adb devices` 的原始输出行（已去掉表头与空行）。
     * 供 SelfPairManager 降级查找 127.0.0.1:xxx 连接端口使用。
     */
    suspend fun rawDeviceLines(): List<String> {
        val r = adb("devices", timeoutMs = 8_000)
        return r.stdout.lines().drop(1).filter { it.isNotBlank() }
    }

    suspend fun refreshDevices() {
        try {
            val r = adb("devices", timeoutMs = 8_000)
            val online = r.stdout.lines()
                .drop(1)
                .filter { it.contains("\tdevice") }
                .map { it.substringBefore("\t").trim() }
                .distinct()            // 仅返回本 App 自己连接的设备（与原版一致），USB 直连设备也保留
            val connected = online.filter { connectedSerials.contains(it) || !it.contains(":") }

            _devices.value = connected.map { s ->
                AdbDevice(
                    serial = s,
                    connectionType = if (s.contains(":")) ConnectionType.WIFI else ConnectionType.USB,
                    state = DeviceState.ONLINE
                )
            }
            _serverRunning.value = _devices.value.isNotEmpty()

            if (_selectedDevice.value != null) {
                val match = _devices.value.find { it.serial == _selectedDevice.value!!.serial }
                if (match != null) {
                    _selectedDevice.value = match
                    missCount = 0
                } else {
                    missCount++
                    if (missCount >= 2) {
                        _selectedDevice.value = null
                        missCount = 0
                    }
                }
            }
            if (_selectedDevice.value == null && missCount == 0 && _devices.value.size == 1) {
                _selectedDevice.value = _devices.value.first()
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshDevices failed", e)
        }
    }

    // ── 连接 ──────────────────────────────────────────────────────────────────

    suspend fun connect(host: String, port: Int = 5555): AdbCommandResult {
        val serial = "$host:$port"
        return try {
            Log.i(TAG, "connect: $serial")
            val r = adb("connect", serial, timeoutMs = 20_000)
            val out = r.output.lowercase()
            when {
                out.contains("connected") || out.contains("already connected") -> {
                    connectedSerials.add(serial)
                    Log.i(TAG, "connect OK: $serial")
                    refreshDevices()
                    AdbCommandResult(0, "connected to $serial", "")
                }
                out.contains("offline") || out.contains("unauthorized") ||
                out.contains("failed to authenticate") -> {
                    Log.i(TAG, "connect: waiting for authorization on $serial")
                    var authorized = false
                    repeat(30) { attempt ->
                        if (authorized) return@repeat
                        delay(1_000)
                        if (attempt % 5 == 0) runCatching { adb("connect", serial, timeoutMs = 5_000) }
                        val devOut = adb("devices", timeoutMs = 5_000).stdout
                        if (devOut.lines().any { it.startsWith(serial) && it.contains("\tdevice") }) {
                            authorized = true
                            connectedSerials.add(serial)
                            Log.i(TAG, "connect: authorized after ${attempt + 1}s")
                        }
                    }
                    if (authorized) {
                        refreshDevices()
                        AdbCommandResult(0, "connected to $serial", "")
                    } else {
                        AdbCommandResult(1, "", "授权超时，请在设备上点击「允许 USB 调试」后重试")
                    }
                }
                else -> AdbCommandResult(1, "", r.output)
            }
        } catch (e: Exception) {
            AdbCommandResult(1, "", e.message ?: "connect failed")
        }
    }

    suspend fun disconnect(serial: String): AdbCommandResult {
        connectedSerials.remove(serial)
        runCatching { adb("disconnect", serial, timeoutMs = 5_000) }
        refreshDevices()
        return AdbCommandResult(0, "disconnected", "")
    }

    suspend fun pair(host: String, port: Int, code: String): AdbCommandResult {
        return try {
            val out = withContext(Dispatchers.IO) { adbPair(host, port, code) }
            AdbCommandResult(0, out, "")
        } catch (e: Exception) {
            AdbCommandResult(1, "", e.message ?: "pair failed")
        }
    }

    suspend fun enableTcpip(port: Int = 5555): AdbCommandResult {
        return shell("setprop service.adb.tcp.port $port && stop adbd && start adbd")
    }

    // ── Shell ─────────────────────────────────────────────────────────────────

    suspend fun shell(command: String, timeoutMs: Int = 15000): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        return try {
            val r = adb("-s", _selectedDevice.value!!.serial, "shell", command, timeoutMs = timeoutMs.toLong())
            if (r.exit == 0 || r.stdout.isNotEmpty()) {
                AdbCommandResult(0, r.stdout, "")
            } else {
                AdbCommandResult(1, "", r.stderr)
            }
        } catch (e: Exception) {
            AdbCommandResult(1, "", e.message ?: "shell failed")
        }
    }

    fun shellStream(command: String, timeoutMs: Int = 120000): Flow<String> = callbackFlow {
        if (!hasDevice) { close(); return@callbackFlow }
        val serial = _selectedDevice.value!!.serial
        val path = BinaryManager.getAdbPath() ?: run { close(); return@callbackFlow }
        try {
            val cmd = mutableListOf(path, "-s", serial, "shell", command)
            val proc = ProcessBuilder(cmd).apply {
                environment()["HOME"] = BinaryManager.getFilesDir()?.absolutePath ?: ""
                environment()["TMPDIR"] = BinaryManager.getCacheDir()?.absolutePath ?: ""
                environment()["ANDROID_ADB_SERVER_PORT"] = BinaryManager.ADB_SERVER_PORT
            }.redirectErrorStream(false).start()

            val reader = proc.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + timeoutMs
            val sendThread = Thread {
                try {
                    while (System.currentTimeMillis() < deadline && !isClosedForSend) {
                        val line = reader.readLine() ?: break
                        trySend(line)
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            proc.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            proc.destroyForcibly()
            sendThread.join(2_000)
            close()
        } catch (e: Exception) {
            close(e)
        }
    }.flowOn(Dispatchers.IO)

    // ── push / pull ───────────────────────────────────────────────────────────

    suspend fun push(localPath: String, remotePath: String): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        val r = adb("-s", _selectedDevice.value!!.serial, "push", localPath, remotePath, timeoutMs = 120_000)
        return if (r.exit == 0) AdbCommandResult(0, "pushed: $remotePath", "")
        else AdbCommandResult(1, "", r.output)
    }

    suspend fun pushAsset(assetPath: String, remotePath: String): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        return try {
            // assets 在 Compose 中通过 AssetManager 读取
            // 调用方应使用 pushAssetFromBytes，此处保留接口以兼容
            AdbCommandResult(1, "", "use pushAssetFromBytes instead")
        } catch (e: Exception) {
            AdbCommandResult(1, "", e.toString())
        }
    }

    suspend fun pushAssetFromBytes(bytes: ByteArray, assetName: String, remotePath: String): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        return try {
            val cacheDir = BinaryManager.getCacheDir() ?: return AdbCommandResult(1, "", "no cache dir")
            val tmpFile = File(cacheDir, assetName)
            tmpFile.writeBytes(bytes)
            push(tmpFile.absolutePath, remotePath)
        } catch (e: Exception) {
            AdbCommandResult(1, "", e.toString())
        }
    }

    suspend fun pull(remotePath: String, localPath: String): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        val r = adb("-s", _selectedDevice.value!!.serial, "pull", remotePath, localPath, timeoutMs = 120_000)
        return if (r.exit == 0) AdbCommandResult(0, "pulled: $localPath", "")
        else AdbCommandResult(1, "", r.output)
    }

    /**
     * 把文本内容写入被连接设备的文件。
     * Android 5.0 无 base64 工具，`echo ... | base64 -d > file` 方案不可用，
     * 统一走「本地临时文件 + adb push」实现。
     */
    suspend fun writeTextFile(remotePath: String, content: String): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        return try {
            val cacheDir = BinaryManager.getCacheDir() ?: return AdbCommandResult(1, "", "no cache dir")
            val tmpFile = File(cacheDir, "cablebee_write_tmp")
            tmpFile.writeText(content, Charsets.UTF_8)
            push(tmpFile.absolutePath, remotePath)
        } catch (e: Exception) {
            AdbCommandResult(1, "", e.toString())
        }
    }

    // ── 设备能力探测 ──────────────────────────────────────────────────────────

    private var cachedSdkInt: Int? = null

    /**
     * 被连接设备的 SDK 版本（结果缓存）。
     * Android 5.0/5.1 = 21/22 只有 toolbox：无 awk/sed/sort/head/tail/wc/base64/unzip，
     * `ls` 不支持 `-L` 且长列表无 links 列，`ps` 不支持 `-A -o`。
     */
    suspend fun deviceSdkInt(): Int {
        cachedSdkInt?.let { return it }
        val res = shell("getprop ro.build.version.sdk")
        val sdk = res.stdout.trim().toIntOrNull() ?: 23 // 探测失败按现代版本处理
        cachedSdkInt = sdk
        return sdk
    }

    // ── 文件列表 ──────────────────────────────────────────────────────────────

    suspend fun listFiles(path: String): List<FileEntry> {
        // Android 5.0/5.1 的 toolbox ls 不支持 -L（遇到未知选项会直接报错退出），
        // 其长列表输出也无 links 列（由 FileEntry.parseLsLine 以日期字段为锚点兼容）。
        val cmd = if (deviceSdkInt() >= 23) "ls -laL \"$path\" 2>&1" else "ls -la \"$path\" 2>&1"
        val result = shell(cmd)
        return result.stdout.split("\n")
            .mapNotNull { FileEntry.parseLsLine(it) }
    }

    // ── 应用管理 ──────────────────────────────────────────────────────────────

    suspend fun listPackages(includeSystem: Boolean = false): List<AppInfo> {
        val cmd = if (includeSystem) "pm list packages -f 2>&1" else "pm list packages -f -3 2>&1"
        val result = shell(cmd)
        return result.stdout.split("\n")
            .filter { it.startsWith("package:") }
            .mapNotNull { line ->
                val raw = line.substring(8)
                val lastEq = raw.lastIndexOf('=')
                if (lastEq < 0) return@mapNotNull null
                val apkPath = raw.substring(0, lastEq)
                val packageName = raw.substring(lastEq + 1).trim()
                if (packageName.isEmpty()) return@mapNotNull null
                AppInfo(apkPath = apkPath, packageName = packageName)
            }
    }

    suspend fun installApk(localPath: String) = shell("pm install -r \"$localPath\"")
    suspend fun uninstall(pkg: String) = shell("pm uninstall $pkg")
    suspend fun forceStop(pkg: String) = shell("am force-stop $pkg")
    suspend fun clearData(pkg: String) = shell("pm clear $pkg")

    // ── 设备信息 ──────────────────────────────────────────────────────────────

    suspend fun getDeviceInfo(): Map<String, String> {
        // 注意：分隔符不能含 < >，否则在部分 shell 里会被当成重定向，导致字段错位
        // （此前用 <<<SEP>>> 会破坏脚本，Android ID 等字段出现命令碎片）
        val sep = "__CBEE_SEP__"
        val e = "echo $sep"
        val result = shell(
            "getprop ro.product.model; $e; " +
            "getprop ro.product.brand; $e; " +
            "getprop ro.product.manufacturer; $e; " +
            "getprop ro.board.platform; $e; " +
            "getprop ro.product.cpu.abi; $e; " +
            "cat /proc/cpuinfo; $e; " +
            "wm size; $e; " +
            "wm density; $e; " +
            "cat /proc/meminfo 2>/dev/null | grep MemTotal; $e; " +
            "df /data; $e; " +
            "dumpsys battery; $e; " +
            // sysfs 电压（µV），比 dumpsys 更准
            "for d in /sys/class/power_supply/*; do " +
            "  n=\`cat \"\$d/type\" 2>/dev/null\`; " +
            "  if [ \"\$n\" = Battery ] || [ \"\$n\" = battery ]; then " +
            "    cat \"\$d/voltage_now\" 2>/dev/null; break; " +
            "  fi; " +
            "done; $e; " +
            "getprop ro.build.version.release; $e; " +
            "getprop ro.build.version.sdk; $e; " +
            "getprop ro.build.version.security_patch; $e; " +
            "cat /proc/version; $e; " +
            "getprop ro.serialno; $e; " +
            "ip addr show wlan0 2>/dev/null; ip addr show wlan1 2>/dev/null; " +
            "ifconfig wlan0 2>/dev/null; ifconfig wlan1 2>/dev/null; $e; " +
            "cat /sys/class/net/wlan0/address 2>/dev/null; echo; " +
            "cat /sys/class/net/wlan1/address 2>/dev/null; echo; " +
            "ip -o link show 2>/dev/null; echo; " +
            "getprop ro.boot.wifimacaddr; echo; " +
            "getprop ro.boot.wifimac; echo; " +
            "getprop ro.ril.oem.wifimac; echo; " +
            "getprop persist.sys.wifi.mac; echo; " +
            "dumpsys wifi 2>/dev/null | grep -i Mac; $e; " +
            "settings get secure android_id"
        )
        val parts = result.stdout.split(sep)
        fun at(i: Int): String = if (i < parts.size) parts[i].trim() else ""

        var resolution = "无"
        Regex("""(\d+x\d+)""").find(at(6))?.groupValues?.get(1)?.let { resolution = it }

        var dpi = "无"
        Regex("""(\d+)""").find(at(7))?.groupValues?.get(1)?.let { dpi = "$it dpi" }

        var memory = "无"
        Regex("""(\d+)\s*kB""").find(at(8))?.groupValues?.get(1)?.toIntOrNull()?.let { kb ->
            memory = "${"%.1f".format(kb / 1024.0 / 1024.0)} GB"
        }

        var storageTotal = "无"
        var storageFree = "无"
        val dfLine = at(9).lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
        val dfParts = dfLine.split(Regex("\\s+"))
        if (dfParts.size >= 4) {
            fun parseK(s: String): Int? = s.replace(Regex("[^\\d]"), "").toIntOrNull()
            parseK(dfParts[1])?.let { storageTotal = "${"%.1f".format(it / 1024.0 / 1024.0)} GB" }
            parseK(dfParts[3])?.let { storageFree = "${"%.1f".format(it / 1024.0 / 1024.0)} GB" }
        }

        // ── 电池 ─────────────────────────────────────────────────────────────
        val batteryDump = at(10)
        val voltageNowRaw = at(11).lines().map { it.trim() }.firstOrNull { it.matches(Regex("""\d+""")) }
        var batteryLevel = "无"
        var batteryVolt = "无"
        var batteryTemp = "无"
        val hasBattery = !Regex("""(?m)^\s*present:\s*false""", RegexOption.IGNORE_CASE)
            .containsMatchIn(batteryDump)

        if (hasBattery) {
            Regex("""(?m)^\s*level:\s*(\d+)""").find(batteryDump)?.groupValues?.get(1)?.let {
                batteryLevel = "$it%"
            }
            Regex("""(?m)^\s*temperature:\s*(-?\d+)""").find(batteryDump)?.groupValues?.get(1)?.let { raw ->
                batteryTemp = "${"%.1f".format((raw.toIntOrNull() ?: 0) / 10.0)} °C"
            }
        }

        // 电压优先 sysfs voltage_now（µV），其次 dumpsys 的 voltage:（mV）
        batteryVolt = formatBatteryVoltage(
            sysfsUv = voltageNowRaw?.toLongOrNull(),
            dumpsysText = batteryDump
        )

        // ── 网络 ─────────────────────────────────────────────────────────────
        var ipAddr = "无"
        Regex("""inet\s+(?:addr[:\s]+)?(\d+\.\d+\.\d+\.\d+)""")
            .findAll(at(17))
            .map { it.groupValues[1] }
            .firstOrNull { it != "127.0.0.1" }
            ?.let { ipAddr = it }

        var mac = parseWifiMac(at(18))
        if (mac == "无") mac = parseWifiMac(at(17))

        var cores = at(5).lines().count { it.startsWith("processor") }.toString()
        if (cores.isNotEmpty() && cores != "0") cores = "$cores 核"

        var kernel = "无"
        Regex("""Linux version (\S+)""").find(at(15))?.groupValues?.get(1)?.let { kernel = it }

        // android_id 可能被污染，只保留合法十六进制
        var androidId = at(19).lines().map { it.trim() }.firstOrNull { line ->
            line.isNotEmpty() &&
                !line.contains(" ") &&
                !line.contains("getprop") &&
                !line.contains("echo") &&
                line.matches(Regex("""[0-9a-fA-F]+"""))
        } ?: "无"

        return mapOf(
            "model" to at(0).ifEmpty { "无" },
            "brand" to at(1).ifEmpty { "无" },
            "manufacturer" to at(2).ifEmpty { "无" },
            "platform" to at(3).ifEmpty { "无" },
            "cpu_abi" to at(4).ifEmpty { "无" },
            "cpu_cores" to cores.ifEmpty { "无" },
            "resolution" to resolution,
            "dpi" to dpi,
            "memory" to memory,
            "storage_total" to storageTotal,
            "storage_free" to storageFree,
            "battery_level" to batteryLevel,
            "battery_voltage" to batteryVolt,
            "battery_temp" to batteryTemp,
            "android" to at(12).ifEmpty { "无" },
            "sdk" to at(13).ifEmpty { "无" },
            "security_patch" to at(14).ifEmpty { "无" },
            "kernel" to kernel,
            "serial" to at(16).ifEmpty { "无" },
            "ip" to ipAddr,
            "mac" to mac,
            "android_id" to androidId
        )
    }

    /** 格式化电池电压：sysfs µV 优先，否则 dumpsys mV */
    private fun formatBatteryVoltage(sysfsUv: Long?, dumpsysText: String): String {
        // sysfs voltage_now 一般为微伏，例如 3850000
        if (sysfsUv != null && sysfsUv > 0) {
            val v = when {
                sysfsUv >= 1_000_000 -> sysfsUv / 1_000_000.0 // µV → V
                sysfsUv >= 1000 -> sysfsUv / 1000.0           // 已是 mV
                else -> sysfsUv.toDouble()
            }
            if (v in 2.0..6.0) return "${"%.2f".format(v)} V"
            if (v in 2000.0..6000.0) return "${"%.2f".format(v / 1000.0)} V"
        }
        // dumpsys：只匹配独立 voltage: 行，排除 Max charging voltage
        val m = Regex("""(?m)^\s*voltage:\s*(\d+)""").find(dumpsysText) ?: return "无"
        val raw = m.groupValues[1].toLongOrNull() ?: return "无"
        return when {
            raw >= 1_000_000 -> "${"%.2f".format(raw / 1_000_000.0)} V" // µV
            raw in 2000..6000 -> "${"%.2f".format(raw / 1000.0)} V"      // mV 典型范围
            raw in 2..6 -> "${"%.2f".format(raw.toDouble())} V"         // 已是 V 整数
            raw > 1000 -> "${"%.2f".format(raw / 1000.0)} V"
            else -> "无"
        }
    }

    /** 从混合文本中提取第一个有效 Wi‑Fi MAC（排除随机化占位地址） */
    private fun parseWifiMac(raw: String): String {
        if (raw.isBlank()) return "无"
        val macRe = Regex("""(?i)(?:link/ether|HWaddr|Mac\s*Address[=:\s]+|MAC[=:\s]+)?([0-9a-f]{2}(?::[0-9a-f]{2}){5})""")
        val invalid = setOf(
            "02:00:00:00:00:00",
            "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff"
        )
        for (m in macRe.findAll(raw)) {
            val candidate = m.groupValues[1].lowercase()
            if (candidate !in invalid && !candidate.startsWith("02:00:00")) {
                return candidate
            }
        }
        // 单独一行只有 MAC 的情况（sysfs address）
        for (line in raw.lines()) {
            val t = line.trim().lowercase()
            if (t.matches(Regex("""[0-9a-f]{2}(?::[0-9a-f]{2}){5}""")) && t !in invalid) {
                return t
            }
        }
        return "无"
    }

    suspend fun screenshot(savePath: String): AdbCommandResult {
        shell("screencap -p /sdcard/cablebee_sc.png")
        return pull("/sdcard/cablebee_sc.png", savePath)
    }

    // ── 调试设置 ──────────────────────────────────────────────────────────────

    suspend fun setAnimationScale(scale: Double): AdbCommandResult {
        val s = "%.1f".format(scale)
        return shell(
            "settings put global window_animation_scale $s; " +
            "settings put global transition_animation_scale $s; " +
            "settings put global animator_duration_scale $s"
        )
    }

    suspend fun setWmSize(w: Int, h: Int) = shell("wm size ${w}x$h")
    suspend fun resetWmSize() = shell("wm size reset")
    suspend fun setWmDensity(dpi: Int) = shell("wm density $dpi")
    suspend fun resetWmDensity() = shell("wm density reset")
    suspend fun reboot(mode: String? = null) = shell(if (mode != null) "reboot $mode" else "reboot")

    // ── Logcat ────────────────────────────────────────────────────────────────

    fun logcat(filter: String? = null, level: String = "V"): Flow<String> {
        val cmd = StringBuilder("logcat -v time")
        if (filter != null) cmd.append(" -s $filter")
        cmd.append(" *:$level")
        return shellStream(cmd.toString())
    }

    // ── forward ───────────────────────────────────────────────────────────────

    suspend fun forward(local: String, remote: String): AdbCommandResult {
        if (!hasDevice) return AdbCommandResult(-1, "", "No device selected")
        runCatching { adb("-s", _selectedDevice.value!!.serial, "forward", "--remove", local, timeoutMs = 5_000) }
        val r = adb("-s", _selectedDevice.value!!.serial, "forward", local, remote, timeoutMs = 5_000)
        return if (r.exit == 0) AdbCommandResult(0, r.output, "")
        else AdbCommandResult(1, "", r.output)
    }

    // ── 内部：执行 adb 二进制 ──────────────────────────────────────────────────

    private data class AdbResult(val exit: Int, val stdout: String, val stderr: String) {
        val isSuccess get() = exit == 0 || stdout.isNotEmpty()
        val output    get() = (stdout + stderr).trim()
    }

    /**
     * 所有 adb 子进程调用的统一入口。
     * 内部使用 Process.waitFor / Thread.join 会阻塞线程，因此必须在 IO 线程执行，
     * 否则从 Compose 的 rememberCoroutineScope()（Main 线程）调用时会冻结 UI 并触发 ANR。
     */
    private suspend fun adb(vararg args: String, timeoutMs: Long = 15_000): AdbResult =
        withContext(Dispatchers.IO) { adbBlocking(*args, timeoutMs = timeoutMs) }

    private fun adbBlocking(vararg args: String, timeoutMs: Long = 15_000): AdbResult {
        val path = BinaryManager.getAdbPath() ?: return AdbResult(1, "", "adb not ready")
        val cmd = mutableListOf(path) + args.toList()
        Log.d(TAG, "exec: ${cmd.joinToString(" ")}")

        val proc = ProcessBuilder(cmd).apply {
            environment()["HOME"] = BinaryManager.getFilesDir()?.absolutePath ?: ""
            environment()["TMPDIR"] = BinaryManager.getCacheDir()?.absolutePath ?: ""
            environment()["ANDROID_ADB_SERVER_PORT"] = BinaryManager.ADB_SERVER_PORT
        }.redirectErrorStream(false).start()

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val stdoutThread = Thread {
            try { stdoutBuf.append(proc.inputStream.bufferedReader().readText()) }
            catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        val stderrThread = Thread {
            try { stderrBuf.append(proc.errorStream.bufferedReader().readText()) }
            catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        val exited = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) {
            Log.w(TAG, "adb timeout after ${timeoutMs}ms, killing")
            proc.destroyForcibly()
        }

        stdoutThread.join(2_000)
        stderrThread.join(2_000)

        val stdout = stdoutBuf.toString()
        val stderr = stderrBuf.toString()
        val exit   = if (exited) runCatching { proc.exitValue() }.getOrDefault(1) else 1

        Log.d(TAG, "exit=$exit stdout=${stdout.take(200)} stderr=${stderr.take(200)}")
        return AdbResult(exit, stdout, stderr)
    }

    /** pair 需要往 stdin 写配对码 */
    private fun adbPair(host: String, port: Int, code: String, timeoutMs: Long = 30_000): String {
        val path = BinaryManager.getAdbPath() ?: throw Exception("adb not ready")
        val cmd = listOf(path, "pair", "$host:$port")
        Log.d(TAG, "exec pair: ${cmd.joinToString(" ")}")

        val proc = ProcessBuilder(cmd).apply {
            environment()["HOME"] = BinaryManager.getFilesDir()?.absolutePath ?: ""
            environment()["TMPDIR"] = BinaryManager.getCacheDir()?.absolutePath ?: ""
            environment()["ANDROID_ADB_SERVER_PORT"] = BinaryManager.ADB_SERVER_PORT
            redirectErrorStream(true)
        }.start()

        try {
            proc.outputStream.bufferedWriter().use {
                it.write("$code\n"); it.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "pair stdin write failed: ${e.message}")
        }

        val outBuf = StringBuilder()
        val readThread = Thread {
            try { outBuf.append(proc.inputStream.bufferedReader().readText()) }
            catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        val exited = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) proc.destroyForcibly()
        readThread.join(2_000)

        val out = outBuf.toString()
        Log.d(TAG, "pair exit=${if (exited) proc.exitValue() else 1} out=${out.take(200)}")
        return out
    }
}
