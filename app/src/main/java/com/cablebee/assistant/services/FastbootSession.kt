package com.cablebee.assistant.services

import android.hardware.usb.*
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "FastbootSession"

// Fastboot 协议常量
private const val TIMEOUT_MS   = 30_000
private const val MAX_PACKET   = 65536

/**
 * 在 App 进程内通过 USB bulk transfer 实现 fastboot 协议，
 * 不依赖外部 fastboot 二进制，完全规避 Android 子进程 USB 权限问题。
 *
 * Fastboot wire protocol（AOSP）：
 *   发送：command 字符串（最长 64 字节）
 *   接收：4字节 tag（OKAY/FAIL/DATA/INFO）+ payload
 */
class FastbootSession(
    private val connection: UsbDeviceConnection,
    private val epIn:       UsbEndpoint,   // BULK IN
    private val epOut:      UsbEndpoint,   // BULK OUT
) {
    private val log = StringBuilder()

    /** 执行一条 fastboot 命令，返回 (exitCode, output)。
     *  [onInfo] 会在每收到一个 INFO 包时立即回调一次，用于实时展示执行过程
     *  （对应电脑端 fastboot.exe 里逐行打印 "Sending 'xxx' ..." 这类进度信息）。 */
    fun runCommand(command: String, onInfo: ((String) -> Unit)? = null): Pair<Int, String> {
        log.clear()
        return try {
            send(command)
            val result = readResponses(onInfo)
            result
        } catch (e: Exception) {
            Log.e(TAG, "fastboot command failed: ${e.message}")
            Pair(1, "error: ${e.message}")
        }
    }

    /**
     * flash 分区：先发 download 命令传数据，再发 flash 命令
     * [onInfo] 同 [runCommand]，用于实时展示 download/flash 过程中的 INFO 提示。
     */
    fun flash(partition: String, file: File, onInfo: ((String) -> Unit)? = null): Pair<Int, String> {
        log.clear()
        return try {
            if (!file.exists()) return Pair(1, "error: file not found: ${file.path}")
            val data = file.readBytes()
            val sizeHex = "%08x".format(data.size)

            // 1. download:<size>
            onInfo?.invoke("Sending '$partition' (${data.size / 1024} KB)")
            send("download:$sizeHex")
            val dlResp = readUntilDataOrOkay(onInfo)
            if (dlResp.first != 0) return dlResp

            // 2. 发送原始数据
            var lastPctLine = -1
            sendRaw(data) { pct ->
                // 每 10% 上报一次，避免过于频繁刷新终端
                if (pct / 10 != lastPctLine) {
                    lastPctLine = pct / 10
                    onInfo?.invoke("Sending '$partition' ... ${pct}%")
                }
            }
            val dataResp = readResponses(onInfo)
            if (dataResp.first != 0) return dataResp

            // 3. flash:<partition>
            onInfo?.invoke("Writing '$partition'")
            send("flash:$partition")
            readResponses(onInfo)
        } catch (e: Exception) {
            Pair(1, "error: ${e.message}")
        }
    }

    // ── 私有：协议实现 ────────────────────────────────────────────────────────

    private fun send(command: String) {
        val bytes = command.toByteArray(Charsets.UTF_8)
        val sent  = connection.bulkTransfer(epOut, bytes, bytes.size, TIMEOUT_MS)
        Log.d(TAG, "send '$command' sent=$sent")
        if (sent < 0) throw Exception("USB write failed (sent=$sent)")
    }

    private fun sendRaw(data: ByteArray, onProgress: ((Int) -> Unit)? = null) {
        var offset = 0
        var lastReportedPct = -1
        while (offset < data.size) {
            val chunk = minOf(MAX_PACKET, data.size - offset)
            val buf   = data.copyOfRange(offset, offset + chunk)
            val sent  = connection.bulkTransfer(epOut, buf, chunk, TIMEOUT_MS)
            if (sent < 0) throw Exception("USB data write failed at offset $offset")
            offset += sent
            if (onProgress != null) {
                val pct = (offset * 100L / data.size).toInt()
                if (pct != lastReportedPct) {   // 节流：百分比变化时才回调一次，避免刷屏
                    lastReportedPct = pct
                    onProgress(pct)
                }
            }
        }
    }

    /** 读取响应直到 OKAY 或 FAIL；每收到一个 INFO 包立即通过 onInfo 回调一次 */
    private fun readResponses(onInfo: ((String) -> Unit)? = null): Pair<Int, String> {
        val output = StringBuilder()
        repeat(256) {
            val buf  = ByteArray(MAX_PACKET)
            val recv = connection.bulkTransfer(epIn, buf, buf.size, TIMEOUT_MS)
            if (recv < 4) throw Exception("USB read failed (recv=$recv)")

            val tag     = String(buf, 0, 4, Charsets.UTF_8)
            val payload = if (recv > 4) String(buf, 4, recv - 4, Charsets.UTF_8).trim() else ""
            Log.d(TAG, "recv tag=$tag payload=$payload")

            when (tag) {
                "OKAY" -> {
                    if (payload.isNotEmpty()) output.appendLine(payload)
                    return Pair(0, output.toString().trim())
                }
                "FAIL" -> {
                    output.appendLine("FAILED: $payload")
                    return Pair(1, output.toString().trim())
                }
                "INFO" -> {
                    output.appendLine(payload)
                    onInfo?.invoke(payload)   // ← 实时通知上层，对应 fastboot.exe 逐行打印过程信息
                }
                "DATA" -> return Pair(0, payload) // 返回 DATA payload（大小）给调用者
                else   -> {
                    output.appendLine(String(buf, 0, recv, Charsets.UTF_8).trim())
                    return Pair(0, output.toString().trim())
                }
            }
        }
        throw Exception("too many INFO responses")
    }

    /** 读取直到收到 DATA 或 OKAY/FAIL（用于 download 命令）；INFO 同样实时回调 */
    private fun readUntilDataOrOkay(onInfo: ((String) -> Unit)? = null): Pair<Int, String> {
        repeat(64) {
            val buf  = ByteArray(MAX_PACKET)
            val recv = connection.bulkTransfer(epIn, buf, buf.size, TIMEOUT_MS)
            if (recv < 4) throw Exception("USB read failed (recv=$recv)")
            val tag     = String(buf, 0, 4, Charsets.UTF_8)
            val payload = if (recv > 4) String(buf, 4, recv - 4, Charsets.UTF_8).trim() else ""
            when (tag) {
                "DATA" -> return Pair(0, payload)
                "OKAY" -> return Pair(0, payload)
                "FAIL" -> return Pair(1, "FAILED: $payload")
                "INFO" -> onInfo?.invoke(payload)
            }
        }
        throw Exception("no DATA response for download")
    }

    companion object {
        /**
         * 从 UsbDevice 找到 fastboot 接口和 BULK IN/OUT 端点，打开连接。
         * 返回 null 表示接口不兼容。
         */
        fun open(usbManager: UsbManager, device: UsbDevice): FastbootSession? {
            // 找 fastboot 接口：class=0xFF
            var fastbootIface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 0xFF) {
                    fastbootIface = iface
                    break
                }
            }
            if (fastbootIface == null) {
                Log.e(TAG, "no vendor-specific interface found")
                return null
            }

            // 找 BULK IN 和 BULK OUT 端点
            var epIn:  UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until fastbootIface.endpointCount) {
                val ep = fastbootIface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN)  epIn  = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }
            }
            if (epIn == null || epOut == null) {
                Log.e(TAG, "missing BULK endpoints: epIn=$epIn epOut=$epOut")
                return null
            }

            val connection = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "openDevice failed")
                return null
            }
            if (!connection.claimInterface(fastbootIface, true)) {
                connection.close()
                Log.e(TAG, "claimInterface failed")
                return null
            }

            Log.i(TAG, "fastboot session opened: ${device.deviceName} epIn=${epIn.address} epOut=${epOut.address}")
            return FastbootSession(connection, epIn, epOut)
        }
    }

    fun close() {
        try { connection.close() } catch (_: Exception) {}
    }
}
