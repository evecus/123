package com.cablebee.assistant.models

enum class ConnectionType { WIFI, USB }

enum class DeviceState { ONLINE, OFFLINE, UNAUTHORIZED, CONNECTING }

data class AdbDevice(
    val serial: String,
    val model: String? = null,
    val product: String? = null,
    val transportId: String? = null,
    val connectionType: ConnectionType = ConnectionType.USB,
    var state: DeviceState = DeviceState.ONLINE
) {
    val displayName: String get() = model ?: serial

    val shortSerial: String
        get() {
            if (serial.contains(":")) return serial
            if (serial.length > 12) return "${serial.substring(0, 6)}…${serial.substring(serial.length - 4)}"
            return serial
        }

    val isWifi: Boolean get() = connectionType == ConnectionType.WIFI

    companion object {
        fun fromAdbLine(line: String): AdbDevice? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) {
                return AdbDevice(
                    serial = line.trim(),
                    connectionType = ConnectionType.WIFI,
                    state = DeviceState.OFFLINE
                )
            }
            val serial = parts[0]
            val stateStr = parts[1]
            val isWifi = serial.contains(":") || serial.contains(".")
            val state = when (stateStr) {
                "device" -> DeviceState.ONLINE
                "unauthorized" -> DeviceState.UNAUTHORIZED
                "offline" -> DeviceState.OFFLINE
                else -> DeviceState.OFFLINE
            }
            var model: String? = null
            var product: String? = null
            for (i in 2 until parts.size) {
                when {
                    parts[i].startsWith("model:") -> model = parts[i].removePrefix("model:").replace("_", " ")
                    parts[i].startsWith("product:") -> product = parts[i].removePrefix("product:")
                }
            }
            return AdbDevice(
                serial = serial,
                model = model,
                product = product,
                connectionType = if (isWifi) ConnectionType.WIFI else ConnectionType.USB,
                state = state
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is AdbDevice && other.serial == serial
    override fun hashCode(): Int = serial.hashCode()
}

data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
    val output: String get() = if (stdout.isNotEmpty()) stdout else stderr
}

data class AppInfo(
    val apkPath: String = "",
    val packageName: String = ""
)

data class PkgInfo(
    val packageName: String = "",
    val label: String = "",
    val icon: ByteArray? = null,
    val apkPath: String = "",
    val apkSize: Long = 0,
    val enabled: Boolean = true,
    val flags: Int = 0,
    val versionCode: Long = 0,
    val versionName: String = "",
    val firstInstallTime: Long = 0,
    val lastUpdateTime: Long = 0,
    val dataDir: String = "",
    val targetSdkVersion: Int = 0,
    val minSdkVersion: Int = 0,
    val error: String? = null
) {
    val isSystem: Boolean get() = (flags and 0x01) != 0

    companion object {
        fun fromJson(json: Map<String, Any?>): PkgInfo {
            val iconB64 = json["icon"] as? String
            val icon: ByteArray? = if (!iconB64.isNullOrEmpty()) {
                try { android.util.Base64.decode(iconB64, android.util.Base64.DEFAULT) } catch (_: Exception) { null }
            } else null
            return PkgInfo(
                packageName = json["package"] as? String ?: "",
                label = (json["label"] as? String) ?: (json["package"] as? String) ?: "",
                icon = icon,
                apkPath = json["apkPath"] as? String ?: "",
                apkSize = (json["apkSize"] as? Number)?.toLong() ?: 0,
                enabled = json["enabled"] as? Boolean ?: true,
                flags = (json["flags"] as? Number)?.toInt() ?: 0,
                versionCode = (json["versionCode"] as? Number)?.toLong() ?: 0,
                versionName = json["versionName"] as? String ?: "",
                firstInstallTime = (json["firstInstallTime"] as? Number)?.toLong() ?: 0,
                lastUpdateTime = (json["lastUpdateTime"] as? Number)?.toLong() ?: 0,
                dataDir = json["dataDir"] as? String ?: "",
                targetSdkVersion = (json["targetSdkVersion"] as? Number)?.toInt() ?: 0,
                minSdkVersion = (json["minSdkVersion"] as? Number)?.toInt() ?: 0,
                error = json["error"] as? String
            )
        }
    }
}

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val permissions: String,
    val size: String,
    val date: String
) {
    companion object {
        private val DATE_FIELD = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /**
         * 解析 `ls -l` 输出的一行。以 YYYY-MM-DD 日期字段为锚点，兼容两种格式：
         *  - 现代 toybox（Android 6.0+）：`perms links user group size date time name`（8+ 字段）
         *  - Android 5.0 toolbox：`perms user group size date time name`（7 字段，无 links 列）
         *  - Android 5.0 符号链接/目录行：size 列整体缺失，`perms user group date time name`
         */
        fun parseLsLine(line: String): FileEntry? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return null
            val perms = parts[0]
            if (perms.length < 10) return null
            if (!perms.startsWith("-") && !perms.startsWith("d") && !perms.startsWith("l") &&
                !perms.startsWith("b") && !perms.startsWith("c") &&
                !perms.startsWith("p") && !perms.startsWith("s")) return null
            val dateIdx = parts.indexOfFirst { DATE_FIELD.matches(it) }
            if (dateIdx < 2) return null
            // size 列：现代格式在 date 前一字段；5.0 链接/目录行该位置是 user/group，按 0 处理
            val size = parts.getOrNull(dateIdx - 1)?.toLongOrNull() ?: 0L
            val nameStart = dateIdx + 2 // 跳过 date 与 time 两列
            if (nameStart >= parts.size) return null
            val rawName = parts.subList(nameStart, parts.size).joinToString(" ")
            val arrowIdx = rawName.indexOf(" -> ")
            val name = if (arrowIdx >= 0) rawName.substring(0, arrowIdx) else rawName
            if (name == "." || name == ".." || name.isEmpty()) return null
            val isDir = perms.startsWith("d") || perms.startsWith("l")
            return FileEntry(
                permissions = perms,
                isDirectory = isDir,
                size = size.toString(),
                date = "${parts[dateIdx]} ${parts[dateIdx + 1]}",
                name = name
            )
        }
    }
}

data class ProcessInfo(
    val user: String = "",
    val pid: Int = 0,
    val ppid: Int = 0,
    val name: String = "",
    val rss: Long = 0,
    val vsz: Long = 0
)

data class LogLine(
    val timestamp: String = "",
    val level: String = "",
    val tag: String = "",
    val pid: Int = 0,
    val message: String = ""
) {
    companion object {
        fun parse(line: String): LogLine {
            // Format: "MM-DD HH:MM:SS.mmm LEVEL/TAG( PID): message"
            val regex = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\w)/(.+?)\((\d+)\):\s*(.*)$""")
            val m = regex.find(line) ?: return LogLine(message = line)
            return LogLine(
                timestamp = m.groupValues[1],
                level = m.groupValues[2],
                tag = m.groupValues[3],
                pid = m.groupValues[4].toIntOrNull() ?: 0,
                message = m.groupValues[5]
            )
        }
    }
}

data class FastbootResult(
    val exitCode: Int,
    val output: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

data class UsbDevice(
    val name: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val deviceName: String = "",
    val serialNumber: String = ""
) {
    val displayId: String
        get() = "${vendorId.toString(16).padStart(4, '0')}:${productId.toString(16).padStart(4, '0')}"
}
