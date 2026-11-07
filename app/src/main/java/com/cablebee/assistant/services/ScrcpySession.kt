package com.cablebee.assistant.services

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * ScrcpySession — 适配 scrcpy-server v3.x 协议（Compose 版本）
 *
 * 与原 Flutter 项目 ScrcpySession.kt 协议实现一致，差异仅在：
 *  - 用 Android 原生 [SurfaceTexture]（由 Compose 层的 TextureView 提供）
 *    替代 Flutter 的 TextureRegistry.SurfaceTextureEntry
 *  - 事件通过高阶回调 [onEvent] 推给上层（替代 Flutter EventSink）
 *
 * v3.x 协议关键变化：
 * 1. 启动参数改为 key=value 格式
 * 2. video / control 各自独立 TCP 连接（audio 已禁用）
 * 3. 握手：dummy(1) + deviceName(64) + codecMeta(12) = 77 字节
 *    codecMeta = codec_id(4) + width(4) + height(4)，大端序
 * 4. 帧头 config 标志：bit63 = 1（PACKET_FLAG_CONFIG），不再是 Long.MIN_VALUE
 *    key frame 标志：bit62 = 1（PACKET_FLAG_KEY_FRAME）
 *    实际 PTS 只用低 62 位
 */
class ScrcpySession(
    private val surfaceTexture: SurfaceTexture,
    private val onEvent: (type: String, data: Map<String, Any?>) -> Unit,
) {
    companion object {
        private const val TAG = "ScrcpySession"
        const val SCRCPY_PORT = 5005

        // v3.x 帧标志（高位）
        private const val PACKET_FLAG_CONFIG    = Long.MIN_VALUE          // bit63
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 62
        private const val PTS_MASK              = (1L shl 62) - 1L       // 低62位

        // control 消息类型
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TOUCH   = 2
        const val TYPE_INJECT_SCROLL  = 3
        const val TYPE_BACK_OR_SCREEN = 4
    }

    @Volatile private var running = false

    private var videoSocket:   Socket? = null
    private var controlSocket: Socket? = null
    private var controlOut:    java.io.OutputStream? = null
    private var codec:         MediaCodec? = null
    private var surface:       Surface? = null
    private var decodeThread:  Thread? = null
    @Volatile private var decodedFrames: Long = 0

    var deviceWidth:  Int = 0; private set
    var deviceHeight: Int = 0; private set

    /**
     * 服务端视频编码尺寸（握手 codecMeta，round8 后，如 1088x1920）。
     * scrcpy 服务端 PositionMapper.map() 会严格校验触摸/滚轮事件中的 screenSize 与该尺寸
     * 相等（`videoSize.equals(clientVideoSize)`），不等时返回 null 静默丢弃事件。
     * 因此触摸事件的 w/h 必须始终取该值，不能用解码器 crop 后的显示尺寸覆盖。
     */
    @Volatile var videoWidth:  Int = 0; private set
    @Volatile var videoHeight: Int = 0; private set

    // ── 连接（push/forward/server 已由调用方完成）────────────────────────────

    fun connect() {
        if (running) return
        running = true
        Thread {
            try { _connect() }
            catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                onEvent("error", mapOf("message" to (e.message ?: "连接失败")))
                stop()
            }
        }.also { it.isDaemon = true; it.name = "scrcpy-connect" }.start()
    }

    private fun _connect() {
        onEvent("status", mapOf("msg" to "连接中..."))

        // ── 1. 连接 video socket（重试最多 30 次等待 server 启动）────────────
        var attempt = 0
        while (attempt < 30 && running) {
            try {
                videoSocket = Socket("127.0.0.1", SCRCPY_PORT)
                break
            } catch (e: IOException) {
                attempt++
                Log.w(TAG, "connect attempt $attempt: ${e.message}")
                onEvent("status", mapOf("msg" to "连接中... ($attempt/30)"))
                Thread.sleep(300)
            }
        }
        val vSock = videoSocket
            ?: throw IOException("无法连接 scrcpy server，请检查设备 ADB 授权")

        // ── 2. 立即连接 control socket（必须在 LocalServerSocket 关闭前）─────
        val cSock = Socket("127.0.0.1", SCRCPY_PORT)
        controlSocket = cSock
        controlOut = cSock.getOutputStream()
        Log.i(TAG, "control socket connected: isConnected=${cSock.isConnected} localPort=${cSock.localPort}")

        // ── 3. 读取握手头（v3.x）────────────────────────────────────────────
        val videoIn = vSock.getInputStream()
        val header = videoIn.readExactly(77)
        if (header.size < 77) throw IOException("握手数据不足：${header.size}/77")

        val deviceName = String(header, 1, 64).trimEnd('\u0000')
        val codecId    = ByteBuffer.wrap(header, 65, 4).order(ByteOrder.BIG_ENDIAN).int
        val w          = ByteBuffer.wrap(header, 69, 4).order(ByteOrder.BIG_ENDIAN).int
        val h          = ByteBuffer.wrap(header, 73, 4).order(ByteOrder.BIG_ENDIAN).int

        Log.i(TAG, "handshake v3: name=$deviceName codec=0x${Integer.toHexString(codecId)} ${w}x${h}")
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
            throw IOException("握手分辨率无效：${w}x${h}")
        }

        deviceWidth  = w
        deviceHeight = h
        videoWidth   = w
        videoHeight  = h
        onEvent("connected", mapOf(
            "deviceName"   to deviceName,
            "deviceWidth"  to deviceWidth,
            "deviceHeight" to deviceHeight,
            "videoWidth"   to videoWidth,
            "videoHeight"  to videoHeight,
        ))

        initDecoder()
        startDecode(videoIn)
    }

    // ── MediaCodec 解码 ──────────────────────────────────────────────────────

    private fun initDecoder() {
        surfaceTexture.setDefaultBufferSize(deviceWidth, deviceHeight)
        surface = Surface(surfaceTexture)
        codec = MediaCodec.createDecoderByType("video/avc").also { c ->
            val fmt = MediaFormat.createVideoFormat("video/avc", deviceWidth, deviceHeight)
            // 允许横竖屏切换（分辨率变化）时解码器自适应，而不必重建
            val maxEdge = maxOf(deviceWidth, deviceHeight)
            fmt.setInteger(MediaFormat.KEY_MAX_WIDTH, maxEdge)
            fmt.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxEdge)
            c.configure(fmt, surface, null, 0)
            c.start()
        }
        Log.i(TAG, "decoder ready ${deviceWidth}x${deviceHeight}")
    }

    private fun startDecode(videoIn: InputStream) {
        decodeThread = Thread {
            val c = codec ?: return@Thread
            val timeoutUs = 10_000L
            var consecutiveInvalidFrames = 0

            try {
                while (running) {
                    val meta = videoIn.readExactly(12)
                    if (meta.size < 12) break

                    val ptsAndFlags = ByteBuffer.wrap(meta, 0, 8).order(ByteOrder.BIG_ENDIAN).long
                    val frameSize = ByteBuffer.wrap(meta, 8, 4).order(ByteOrder.BIG_ENDIAN).int and 0x7FFFFFFF

                    if (frameSize <= 0 || frameSize > 10_000_000) {
                        Log.w(TAG, "invalid frameSize=$frameSize ptsAndFlags=$ptsAndFlags")
                        consecutiveInvalidFrames++
                        if (consecutiveInvalidFrames >= 20) {
                            throw IOException("视频流格式异常（连续无效帧），请重试并降低分辨率/码率")
                        }
                        continue
                    }
                    consecutiveInvalidFrames = 0

                    val frameData = videoIn.readExactly(frameSize)
                    if (frameData.size < frameSize) break

                    val isConfig  = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                    val pts       = if (isConfig) 0L else (ptsAndFlags and PTS_MASK)
                    val flags     = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                    val inputIdx = c.dequeueInputBuffer(timeoutUs)
                    if (inputIdx >= 0) {
                        val buf = c.getInputBuffer(inputIdx) ?: continue
                        buf.clear()
                        buf.put(frameData, 0, frameSize)
                        c.queueInputBuffer(inputIdx, 0, frameSize, pts, flags)
                    }

                    if (!isConfig) {
                        val info = MediaCodec.BufferInfo()
                        while (running) {
                            val outputIdx = c.dequeueOutputBuffer(info, timeoutUs)
                            when {
                                outputIdx >= 0 -> c.releaseOutputBuffer(outputIdx, true)
                                outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val of = c.outputFormat
                                    Log.i(TAG, "output format changed: $of")
                                    // 解码器输出宽高可能是按 16 对齐的 coded 尺寸（如 1088 对 1080），
                                    // 需用裁剪框得到真实显示尺寸用于渲染 buffer。
                                    // 注意：触摸基准（videoWidth/videoHeight）绝不能改用 crop 后尺寸——
                                    // 服务端 PositionMapper 校验的是握手 codecMeta（coded）尺寸。
                                    val codedW = of.getInteger(MediaFormat.KEY_WIDTH)
                                    val codedH = of.getInteger(MediaFormat.KEY_HEIGHT)
                                    var cropW = codedW
                                    var cropH = codedH
                                    if (of.containsKey("crop-left") && of.containsKey("crop-right") &&
                                        of.containsKey("crop-top") && of.containsKey("crop-bottom")) {
                                        cropW = of.getInteger("crop-right") - of.getInteger("crop-left") + 1
                                        cropH = of.getInteger("crop-bottom") - of.getInteger("crop-top") + 1
                                    }
                                    if (cropW > 0 && cropH > 0) {
                                        surfaceTexture.setDefaultBufferSize(cropW, cropH)
                                    }
                                    if (codedW > 0 && codedH > 0 && (codedW != videoWidth || codedH != videoHeight)) {
                                        // coded 尺寸发生变化：设备旋转/重新协商，服务端 videoSize 已改变，
                                        // 触摸基准必须同步更新为新 coded 尺寸，否则事件会被服务端丢弃。
                                        videoWidth  = codedW
                                        videoHeight = codedH
                                        onEvent("connected", mapOf(
                                            "deviceName"   to "scrcpy",
                                            "deviceWidth"  to if (cropW > 0) cropW else codedW,
                                            "deviceHeight" to if (cropH > 0) cropH else codedH,
                                            "videoWidth"   to videoWidth,
                                            "videoHeight"  to videoHeight,
                                        ))
                                    } else if (cropW > 0 && cropH > 0) {
                                        // 仅 crop 差异（16 对齐填充）：显示比例微调，触摸基准不变
                                        onEvent("resize", mapOf(
                                            "deviceWidth"  to cropW,
                                            "deviceHeight" to cropH,
                                        ))
                                    }
                                }
                                outputIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* ignore */ }
                                else -> break
                            }
                        }
                        decodedFrames++
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "decode error", e)
                    onEvent("error", mapOf("message" to (e.message ?: "视频解码失败")))
                }
            }
            if (running && decodedFrames == 0L) {
                onEvent("error", mapOf("message" to "NO_VIDEO_FRAME"))
            }
            Log.i(TAG, "decode thread ended")
        }.also { it.isDaemon = true; it.name = "scrcpy-decode"; it.start() }
    }

    // ── 控制 ─────────────────────────────────────────────────────────────────

    /**
     * 发送触摸事件。x/y 为服务端视频坐标系（握手 codecMeta 尺寸）下的坐标；
     * w/h 固定取握手 codecMeta 尺寸（videoWidth/videoHeight），
     * 服务端 PositionMapper 会校验其与服务端 videoSize 一致，否则丢弃事件。
     */
    fun sendTouch(action: Int, pointerId: Long, x: Int, y: Int, pressure: Float = 1f) {
        val w = videoWidth
        val h = videoHeight
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "sendTouch: video size not ready (${w}x${h}), dropping event")
            return
        }
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_TOUCH.toByte())
        buf.put(action.toByte())
        buf.putLong(pointerId)
        buf.putInt(x); buf.putInt(y)
        buf.putShort((w and 0xFFFF).toShort())
        buf.putShort((h and 0xFFFF).toShort())
        buf.putShort((pressure * 65535).toInt().toShort())
        buf.putInt(if (action == 0) 1 else 0)
        buf.putInt(if (action == 0) 1 else 0)
        sendControl(buf.array())
    }

    fun sendScroll(x: Int, y: Int, hScroll: Int, vScroll: Int) {
        val w = videoWidth
        val h = videoHeight
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "sendScroll: video size not ready (${w}x${h}), dropping event")
            return
        }
        val buf = ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_SCROLL.toByte())
        buf.putInt(x); buf.putInt(y)
        buf.putShort((w and 0xFFFF).toShort()); buf.putShort((h and 0xFFFF).toShort())
        buf.putInt(hScroll); buf.putInt(vScroll)
        buf.put(0)
        sendControl(buf.array())
    }

    fun sendKeycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0) {
        val buf = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode); buf.putInt(repeat); buf.putInt(metaState)
        sendControl(buf.array())
    }

    fun sendBackOrScreenOn() = sendControl(byteArrayOf(TYPE_BACK_OR_SCREEN.toByte()))

    private val controlExecutor = Executors.newSingleThreadExecutor()

    private fun sendControl(data: ByteArray) {
        val out = controlOut
        if (out == null) {
            Log.w(TAG, "sendControl: controlOut is null, dropping ${data.size}B")
            return
        }
        controlExecutor.execute {
            try {
                out.write(data)
                out.flush()
                Log.d(TAG, "sendControl: sent ${data.size}B type=0x${data[0].toInt().and(0xFF).toString(16)}")
            } catch (e: Exception) {
                Log.e(TAG, "sendControl failed: ${e.javaClass.simpleName} msg=${e.message}")
            }
        }
    }

    // ── 停止 ─────────────────────────────────────────────────────────────────

    fun stop() {
        running = false
        controlExecutor.shutdown()
        try { videoSocket?.close()   } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        codec = null; surface = null
        onEvent("stopped", emptyMap())
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun InputStream.readExactly(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }
}
