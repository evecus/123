package com.cablebee.assistant.services

/**
 * 网络地址解析 / 格式化，同时支持 IPv4、域名与 IPv6。
 *
 * 支持的输入：
 *   192.168.1.5            → 默认端口
 *   192.168.1.5:5555
 *   my-phone.local:5555
 *   [2001:db8::1]:5555     → 带方括号的 IPv6（推荐，可带端口）
 *   [2001:db8::1]
 *   2001:db8::1            → 不带方括号的 IPv6：无法区分端口，整体视为主机，使用默认端口
 *
 * adb 对 IPv6 的规范写法是 "[addr]:port"，所以序列号、adb connect / pair 的参数都统一用 [format]。
 */
object NetAddress {
    const val DEFAULT_ADB_PORT = 5555

    /**
     * @param defaultPort 未写端口时使用的默认值；传 null 表示“必须写端口”（用于配对）。
     *                    此时不带方括号的 IPv6 按“最后一个冒号后面是端口”拆分。
     * @return 主机（不含方括号）与端口；格式不合法返回 null
     */
    fun parse(raw: String, defaultPort: Int? = DEFAULT_ADB_PORT): Pair<String, Int>? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        // 1) [v6] 或 [v6]:port
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end <= 1) return null
            val host = s.substring(1, end)
            val rest = s.substring(end + 1)
            val port = when {
                rest.isEmpty() -> defaultPort ?: return null
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
            return validate(host, port)
        }

        // 2) 按冒号个数区分：0 个 = 主机；1 个 = 主机:端口；≥2 个 = 裸 IPv6
        return when (s.count { it == ':' }) {
            0 -> validate(s, defaultPort ?: return null)
            1 -> {
                val host = s.substringBefore(':')
                // 与原行为一致：端口写错时回落到默认端口（配对场景下 defaultPort 为 null，则判为不合法）
                val port = s.substringAfter(':').toIntOrNull() ?: defaultPort ?: return null
                validate(host, port)
            }
            else -> {
                if (defaultPort != null) {
                    validate(s, defaultPort)
                } else {
                    val idx = s.lastIndexOf(':')
                    val port = s.substring(idx + 1).toIntOrNull() ?: return null
                    validate(s.substring(0, idx), port)
                }
            }
        }
    }

    /** 规范化为 adb 使用的 "host:port"；IPv6 自动加方括号 */
    fun format(host: String, port: Int): String {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return if (h.contains(':')) "[$h]:$port" else "$h:$port"
    }

    private fun validate(host: String, port: Int): Pair<String, Int>? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || port !in 1..65535) return null
        return h to port
    }
}
