package com.cablebee.assistant.services

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the adb binary bundled as a fake .so file in jniLibs.
 *
 * The adb binary is stored as `libcablebee_adb.so` in jniLibs so that
 * Android ABI splits automatically include only the matching ABI's binary.
 *
 * At runtime we locate the file via [ApplicationInfo.nativeLibraryDir],
 * which points directly to the already-extracted .so files on disk.
 * We just chmod +x once and it's ready to run.
 *
 * Fastboot is implemented in-process via [FastbootSession] (USB bulk transfer),
 * so no fastboot binary is needed.
 */
object BinaryManager {
    private const val TAG = "BinaryManager"
    const val ADB_SERVER_PORT = "15037"

    private var adbPath: String? = null
    private var isReady = false
    private var filesDir: File? = null
    private var cacheDir: File? = null

    fun initialize(context: Context) {
        try {
            filesDir = context.filesDir
            cacheDir = context.cacheDir
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val adb = File("$nativeDir/libcablebee_adb.so")
            if (!adb.exists()) {
                throw Exception("libcablebee_adb.so not found in $nativeDir")
            }
            adbPath = adb.absolutePath
            chmod(adbPath!!)
            isReady = true
            Log.i(TAG, "adb binary ready: $adbPath (size=${adb.length()})")
        } catch (e: Exception) {
            isReady = false
            Log.e(TAG, "BinaryManager.initialize error", e)
        }
    }

    fun getAdbPath(): String? = adbPath
    fun isReady(): Boolean = isReady
    fun getFilesDir(): File? = filesDir
    fun getCacheDir(): File? = cacheDir

    fun reExtract(context: Context) {
        isReady = false
        initialize(context)
    }

    private fun chmod(path: String) {
        try {
            val p = ProcessBuilder("chmod", "755", path).start()
            p.waitFor()
        } catch (_: Exception) {}
    }
}
