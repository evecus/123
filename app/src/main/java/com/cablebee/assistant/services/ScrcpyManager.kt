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

    /** 设备分辨率，握手成功后由 session 写入 */
    @Volatile var deviceWidth: Int = 1080; private set
    @Volatile var deviceHeight: Int = 1920; private set

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

            // 1. 推送 scrcpy-server（优先 /sdcard/，回退 /data/local/tmp/）
            val serverBytes = try {
                context.assets.open("scrcpy-server").use { it.readBytes() }
            } catch (e: Exception) {
                onEvent("error", mapOf("message" to "无法读取 scrcpy-server 资源: ${e.message}"))
                return@withContext false
            }
            var remotePath = "/sdcard/scrcpy_server.apk"
            var pushRes = adb.pushAssetFromBytes(serverBytes, "scrcpy-server.apk", remotePath)
            if (!pushRes.isSuccess) {
                remotePath = "/data/local/tmp/scrcpy_server.apk"
                pushRes = adb.pushAssetFromBytes(serverBytes, "scrcpy-server.apk", remotePath)
            }
            if (!pushRes.isSuccess) {
                onEvent("error", mapOf("message" to "推送失败: ${pushRes.stderr}"))
                return@withContext false
            }

            // 2. 启动 scrcpy-server v3.3.4
            onEvent("status", mapOf("msg" to "启动 server..."))
            val serverCmd = "CLASSPATH=$remotePath app_process ./ com.genymobile.scrcpy.Server 3.3.4 " +
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
            onEvent("status", mapOf("msg" to "等待 server 就绪..."))
            var socketReady = false
            for (i in 0 until 30) {
                delay(200)
                val check = adb.shell("grep -c scrcpy /proc/net/unix 2>/dev/null || echo 0", timeoutMs = 2000)
                val count = check.stdout.trim().toIntOrNull() ?: 0
                if (count > 0) { socketReady = true; break }
            }
            if (!socketReady) delay(1000)

            // 5. 启动 ScrcpySession
            onEvent("status", mapOf("msg" to "连接视频流..."))
            session?.stop()
            val s = ScrcpySession(surfaceTexture) { type, data ->
                when (type) {
                    "connected" -> {
                        data["deviceWidth"]?.let { deviceWidth = it as Int }
                        data["deviceHeight"]?.let { deviceHeight = it as Int }
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

    // 设备坐标触摸
    fun sendTouch(action: Int, pointerId: Long, x: Int, y: Int, w: Int, h: Int, pressure: Float = 1f) {
        session?.sendTouch(action, pointerId, x, y, w, h, pressure)
    }
    fun sendScroll(x: Int, y: Int, w: Int, h: Int, hScroll: Int, vScroll: Int) {
        session?.sendScroll(x, y, w, h, hScroll, vScroll)
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
