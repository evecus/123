package com.cablebee.assistant.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.cablebee.assistant.models.UsbDevice
import kotlinx.coroutines.flow.*

/**
 * USB 设备列表服务。
 * 与 Flutter 版 usb_service.dart + 原生 MainActivity 的 USB 事件通道行为一致：
 *  - initialize 时读取一次设备列表
 *  - 监听 ACTION_USB_DEVICE_ATTACHED / DETACHED 广播自动刷新
 *  - serialNumber 尝试读取（需要 USB 权限，失败回退空串）
 */
class UsbService(private val context: Context) {
    companion object {
        private const val TAG = "UsbService"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val devices: StateFlow<List<UsbDevice>> = _devices.asStateFlow()

    private val _hostSupported = MutableStateFlow(false)
    val hostSupported: StateFlow<Boolean> = _hostSupported.asStateFlow()

    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "usb event: ${intent.action}")
                    refresh()
                }
            }
        }
    }

    private var receiverRegistered = false

    fun initialize() {
        _hostSupported.value = context.packageManager.hasSystemFeature("android.hardware.usb.host")
        // 监听 USB 插拔（对应 Flutter 原生侧 onNewIntent → usb_events）
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(usbEventReceiver, filter)
                }
                receiverRegistered = true
            }.onFailure { Log.e(TAG, "register usbEventReceiver failed", it) }
        }
        refresh()
    }

    fun release() {
        runCatching {
            if (receiverRegistered) {
                context.unregisterReceiver(usbEventReceiver)
                receiverRegistered = false
            }
        }
    }

    fun refresh() {
        val deviceList = usbManager.deviceList.values.map { device ->
            UsbDevice(
                name = device.deviceName ?: "",
                vendorId = device.vendorId,
                productId = device.productId,
                deviceName = device.deviceName ?: "",
                // 需要 USB 权限才能读取，失败时回退空串（与 Flutter 端 runCatching 一致）
                serialNumber = runCatching { device.serialNumber }.getOrNull() ?: ""
            )
        }
        _devices.value = deviceList
    }
}
