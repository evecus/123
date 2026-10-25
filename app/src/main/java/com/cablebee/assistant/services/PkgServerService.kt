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
            val checkRes = adb.shell("wc -c < $dexPath 2>/dev/null")
            val remoteSize = checkRes.stdout.trim().toLongOrNull() ?: 0
            if (remoteSize == localSize) {
                activeDex = dexPath
                deployed = true
                return
            }
            // Try push
            try {
                adb.push(tmpFile.absolutePath, dexPath)
                val verifyRes = adb.shell("wc -c < $dexPath 2>/dev/null")
                val pushedSize = verifyRes.stdout.trim().toLongOrNull() ?: 0
                if (pushedSize == localSize) {
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
