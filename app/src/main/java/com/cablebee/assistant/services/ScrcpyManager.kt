package com.cablebee.assistant.services

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import kotlinx.coroutines.*

/**
 * 投屏管理器：编排 scrcpy-server 部署 / forward / 启动 / 连接的全流程。
 *
 * 与原 Flutter 项目的 remote_screen.dart + ScrcpyChannel 行为一致：
 *   1. 推送 scrcpy-server 资源到设备
 *   2. adb shell 启动 com.genymobile.scrcpy.Server v3.3.4（tunnel_forward 模式）
 *   3. adb forward tcp:5005 → localabstract:scrcpy
 *   4. 等待 server socket 就绪（grep /proc/net/unix）
 *   5. 由 [ScrcpySession] 连接 video/control socket 并解码 H.264
 */
class ScrcpyManager(
    private val adb: AdbService,
    private val context: Context,
) {
    private val TAG = "ScrcpyManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var session: ScrcpySession? = null
    @Volatile var connected: Boolean = false; private set
    @Volatile var connecting: Boolean = false; private set

    /** 设备显示分辨率（crop 后真实尺寸，握手/格式变化时由 session 写入，仅用于 UI 布局） */
    @Volatile var deviceWidth: Int = 1080; private set
    @Volatile var deviceHeight: Int = 1920; private set

    /**
     * 服务端视频编码尺寸（握手 codecMeta，round8 后）。
     * scrcpy 服务端 PositionMapper 会严格校验触摸事件中的 screenSize 与该尺寸一致，
     * 不一致时静默丢弃事件，因此它是指触摸坐标映射的唯一基准。
     */
    @Volatile var videoWidth: Int = 0; private set
    @Volatile var videoHeight: Int = 0; private set

    /**
     * 启动一次投屏会话。
     * @param surfaceTexture 由上层 TextureView 提供，用于 MediaCodec 渲染
     * @param maxSize 最大边长（0 表示原始）
     * @param bitRateMbps 视频码率（Mbps）
     * @param maxFps 最大帧率
     * @param onEvent 事件回调：status / connected / error / stopped
     */
    suspend fun start(
        surfaceTexture: SurfaceTexture,
        maxSize: Int,
        bitRateMbps: Int,
        maxFps: Int,
        onEvent: (type: String, data: Map<String, Any?>) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        connecting = true
        try {
            // 0. 根据设备物理分辨率自动调参
            onEvent("status", mapOf("msg" to "检测设备分辨率..."))
            val (tunedMaxSize, tunedBitRate, tunedFps) = autoTuneByResolution(maxSize, bitRateMbps, maxFps)
            onEvent("status", mapOf("msg" to "正在推送 server..."))

            // 1. 推送 scrcpy-server（优先 /data/local/tmp/，回退 /sdcard/）
            //    /data/local/tmp 位于可执行分区，app_process 能正常加载 dex；
            //    /sdcard 是 FUSE + noexec 且 dalvik-cache 不可写，Android 5.0 上会报
            //    "Dex cache directory isn't writable / 无法打开DEX文件"。
            val serverBytes = try {
                context.assets.open("scrcpy-server").use { it.readBytes() }
            } catch (e: Exception) {
                onEvent("error", mapOf("message" to "无法读取 scrcpy-server 资源: ${e.message}"))
                return@withContext false
            }
            var remotePath = "/data/local/tmp/scrcpy_server.apk"
            var pushRes = adb.pushAssetFromBytes(serverBytes, "scrcpy-server.apk", remotePath)
            if (!pushRes.isSuccess) {
                remotePath = "/sdcard/scrcpy_server.apk"
                pushRes = adb.pushAssetFromBytes(serverBytes, "scrcpy-server.apk", remotePath)
            }
            if (!pushRes.isSuccess) {
                onEvent("error", mapOf("message" to "推送失败: ${pushRes.stderr}"))
                return@withContext false
            }
            // 回退到 /sdcard 时重定向 ANDROID_DATA，否则 dalvik-cache 不可写导致 dex 加载失败
            val androidData = if (remotePath.startsWith("/sdcard")) "ANDROID_DATA=/sdcard " else ""

            // 2. 启动 scrcpy-server v3.3.4
            onEvent("status", mapOf("msg" to "启动 server..."))
            val serverCmd = "CLASSPATH=$remotePath ${androidData}app_process ./ com.genymobile.scrcpy.Server 3.3.4 " +
                "tunnel_forward=true video=true audio=false control=true " +
                "max_size=$tunedMaxSize video_bit_rate=${tunedBitRate * 1000000} max_fps=$tunedFps " +
                "send_device_meta=true send_frame_meta=true send_dummy_byte=true send_codec_meta=true " +
                "cleanup=true power_on=true >/dev/null 2>&1 &"
            runCatching { adb.shell(serverCmd, timeoutMs = 3000) }

            // 3. adb forward
            onEvent("status", mapOf("msg" to "建立隧道..."))
            val serial = adb.selectedDevice.value?.serial ?: run {
                onEvent("error", mapOf("message" to "未选择设备"))
                return@withContext false
            }
            adb.forward("tcp:5005", "localabstract:scrcpy")

            // 4. 等待 server socket 就绪
            //    Android 5.0 无 grep -c，直接读 /proc/net/unix 在本地判断；
            //    app_process 冷启动 + dex 加载在低端电视上可能超过 6 秒，放宽到约 30s。
            onEvent("status", mapOf("msg" to "等待 server 就绪..."))
            var socketReady = false
            for (i in 0 until 60) {
                delay(500)
                val check = adb.shell("cat /proc/net/unix 2>/dev/null", timeoutMs = 3000)
                if (check.stdout.contains("scrcpy")) { socketReady = true; break }
            }
            if (!socketReady) delay(1000)

            // 5. 启动 ScrcpySession
            onEvent("status", mapOf("msg" to "连接视频流..."))
            session?.stop()
            val s = ScrcpySession(surfaceTexture) { type, data ->
                when (type) {
                    "connected" -> {
                        (data["deviceWidth"] as? Int)?.let { deviceWidth = it }
                        (data["deviceHeight"] as? Int)?.let { deviceHeight = it }
                        (data["videoWidth"] as? Int)?.let { videoWidth = it }
                        (data["videoHeight"] as? Int)?.let { videoHeight = it }
                    }
                    "resize" -> {
                        // 解码器输出格式变化（16 对齐 crop 差异）：仅显示尺寸变化，触摸基准不变
                        (data["deviceWidth"] as? Int)?.let { deviceWidth = it }
                        (data["deviceHeight"] as? Int)?.let { deviceHeight = it }
                    }
                    "stopped" -> { connected = false; connecting = false }
                    "error"   -> { connected = false; connecting = false }
                }
                onEvent(type, data)
            }
            session = s
            s.connect()
            connected = true
            connecting = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            connecting = false
            connected = false
            onEvent("error", mapOf("message" to (e.message ?: "投屏启动失败")))
            false
        }
    }

    fun stop() {
        connecting = false
        connected = false
        session?.stop()
        session = null
    }

    // 设备坐标触摸（w/h 由 ScrcpySession 按服务端握手视频尺寸自动附加：
    // scrcpy 服务端 PositionMapper 会校验客户端 screenSize 与服务端 videoSize 一致，
    // 不一致时静默丢弃事件，因此这里绝不能再由上层传入布局尺寸）
    fun sendTouch(action: Int, pointerId: Long, x: Int, y: Int, pressure: Float = 1f) {
        session?.sendTouch(action, pointerId, x, y, pressure)
    }
    fun sendScroll(x: Int, y: Int, hScroll: Int, vScroll: Int) {
        session?.sendScroll(x, y, hScroll, vScroll)
    }
    fun sendKeycode(action: Int, keycode: Int) {
        session?.sendKeycode(action, keycode)
    }
    fun sendBackOrScreenOn() {
        session?.sendBackOrScreenOn()
    }

    /** 读取设备物理分辨率并自动选择更稳的投屏参数 */
    private suspend fun autoTuneByResolution(userMax: Int, userBit: Int, userFps: Int): Triple<Int, Int, Int> {
        val res = runCatching { adb.shell("wm size 2>/dev/null || wm size") }.getOrNull()
            ?: return Triple(userMax, userBit, userFps)
        val m = Regex("""Physical size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(res.stdout)
            ?: Regex("""(\d+)x(\d+)""").find(res.stdout)
            ?: return Triple(userMax, userBit, userFps)
        val w = m.groupValues[1].toIntOrNull() ?: 0
        val h = m.groupValues[2].toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) return Triple(userMax, userBit, userFps)

        val longEdge = maxOf(w, h)
        val shortEdge = minOf(w, h)
        val pixelCount = w * h
        val aspect = longEdge.toDouble() / shortEdge

        // 1) maxSize：优先按短边
        var maxSize = shortEdge.coerceIn(720, 1440)
        maxSize = (maxSize / 16) * 16
        if (maxSize < 720) maxSize = 720

        // 2) bitrate
        val megaPixels = pixelCount / 1_000_000.0
        var bitRate = (megaPixels * 3.8).toInt()
        bitRate = bitRate.coerceIn(4, 14)

        // 3) fps
        var fps = 30
        if (megaPixels >= 4.0) fps = 24
        if (aspect >= 2.1) {
            maxSize = (shortEdge / 16) * 16
            if (maxSize < 720) maxSize = 720
            if (fps > 25) fps = 25
        }

        // 尊重用户选择（不强制升档）
        if (maxSize > userMax && userMax > 0) maxSize = userMax
        if (bitRate > userBit) bitRate = userBit
        if (fps > userFps) fps = userFps
        return Triple(maxSize, bitRate, fps)
    }
}
