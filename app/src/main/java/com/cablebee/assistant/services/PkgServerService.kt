package com.cablebee.assistant.services

import android.content.Context
import android.util.Log
import com.cablebee.assistant.models.PkgInfo
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class PkgServerService(
    private val adb: AdbService,
    private val context: Context
) {
    private val TAG = "PkgServerService"
    private var deployed = false
    private var activeDex: String? = null

    companion object {
        private const val ASSET_NAME = "pkgserver.apk"
        private const val PREFERRED_DEX = "/data/local/tmp/pkgserver.apk"
        private const val FALLBACK_DEX = "/sdcard/pkgserver.apk"
    }

    fun streamPackages(sortBy: String = "package"): Flow<PkgInfo> = flow {
        ensureDeployed()
        val dex = activeDex!!
        val androidData = if (dex.startsWith("/sdcard")) "ANDROID_DATA=/sdcard " else ""
        val cmd = "CLASSPATH=$dex ${androidData}app_process ./ com.cablebee.pkgserver.Main --sort=$sortBy 2>/dev/null"

        // 真正流式：每读到一行 JSON 就立刻 emit
        adb.shellStream(cmd, timeoutMs = 120000).collect { line ->
            val t = line.trim()
            if (t.isEmpty() || !t.startsWith("{")) return@collect
            try {
                val json = JSONObject(t)
                val map = mutableMapOf<String, Any?>()
                json.keys().forEach { key -> map[key] = json.get(key) }
                emit(PkgInfo.fromJson(map))
            } catch (_: Exception) {}
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * 流式输出全部应用信息。
     *
     * 优先走 pkgserver（dex 内反射 IPackageManager，快且带图标/版本等完整详情）；
     * 若输出为 0 条——Android 5.0/5.1 上 IPackageManager.getAllPackages() 自 API 26
     * 才加入，pkgserver 调用会抛 NoSuchMethodException 导致无任何输出——则回退到
     * `pm list packages -f` 获取包名列表，再逐包 [getPackage] 补详情。
     */
    fun streamAllPackages(sortBy: String = "package"): Flow<PkgInfo> = flow {
        var count = 0
        streamPackages(sortBy).collect { pkg ->
            count++
            emit(pkg)
        }
        if (count > 0) return@flow

        Log.w(TAG, "streamPackages yielded 0 items, falling back to pm list packages")
        val apps = adb.listPackages(includeSystem = true)
        for (app in apps) {
            val info = try {
                getPackage(app.packageName)
            } catch (_: Exception) {
                null
            }
            if (info != null) emit(info)
            else emit(PkgInfo(packageName = app.packageName, apkPath = app.apkPath, label = app.packageName))
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun getPackage(packageName: String): PkgInfo? {
        ensureDeployed()
        val dex = activeDex!!
        val androidData = if (dex.startsWith("/sdcard")) "ANDROID_DATA=/sdcard " else ""
        val res = adb.shell(
            "CLASSPATH=$dex ${androidData}app_process ./ com.cablebee.pkgserver.Main \"$packageName\" 2>/dev/null"
        )
        for (line in res.stdout.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            try {
                val json = JSONObject(t)
                val map = mutableMapOf<String, Any?>()
                json.keys().forEach { key -> map[key] = json.get(key) }
                return PkgInfo.fromJson(map)
            } catch (_: Exception) {}
        }
        return null
    }

    fun invalidate() {
        deployed = false
        activeDex = null
    }

    /**
     * 读取被连接设备上文件的大小。
     * Android 5.0 无 wc 工具，改用 ls -l 输出解析：以 YYYY-MM-DD 日期字段为锚点
     * （现代 toybox 有 links 列、5.0 toolbox 没有，日期前一列即 size）。
     */
    private suspend fun remoteFileSize(path: String): Long? {
        val res = adb.shell("ls -l \"$path\" 2>/dev/null")
        val line = res.stdout.lines().firstOrNull { it.isNotBlank() } ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0].length < 10) return null
        val dateIdx = parts.indexOfFirst { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) }
        if (dateIdx < 2) return null
        return parts.getOrNull(dateIdx - 1)?.toLongOrNull()
    }

    private suspend fun ensureDeployed() {
        if (deployed && activeDex != null) return

        // Copy asset to temp file
        val tmpFile = java.io.File(context.cacheDir, ASSET_NAME)
        try {
            context.assets.open(ASSET_NAME).use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy pkgserver.apk from assets", e)
            throw Exception("无法读取 pkgserver.apk")
        }
        val localSize = tmpFile.length()

        for (dexPath in listOf(PREFERRED_DEX, FALLBACK_DEX)) {
            // Check remote file size
            if (remoteFileSize(dexPath) == localSize) {
                activeDex = dexPath
                deployed = true
                return
            }
            // Try push
            try {
                adb.push(tmpFile.absolutePath, dexPath)
                if (remoteFileSize(dexPath) == localSize) {
                    adb.shell("chmod 644 $dexPath 2>/dev/null")
                    activeDex = dexPath
                    deployed = true
                    return
                }
            } catch (_: Exception) { continue }
        }
        throw Exception("无法将 pkgserver.apk 推送到被连接设备")
    }
}
