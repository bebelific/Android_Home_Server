package com.printserver.core.files

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import com.printserver.core.common.PrinterLog
import java.io.File

object UsbStorage {
    data class Volume(val path: String, val writable: Boolean, val freeBytes: Long, val totalBytes: Long)

    fun detect(context: Context? = null): List<Volume> {
        val out = LinkedHashMap<String, Volume>()
        val storageRoot = File("/storage")
        storageRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val name = dir.name.lowercase()
            if (name == "emulated" || name == "self" || name.startsWith("enc_")) return@forEach
            if (!dir.canRead()) return@forEach
            out[dir.absolutePath] = Volume(
                path = dir.absolutePath,
                writable = testWrite(dir),
                freeBytes = dir.usableSpace,
                totalBytes = dir.totalSpace,
            )
        }
        if (out.isEmpty() && Build.VERSION.SDK_INT >= 24 && context != null) {
            runCatching {
                val sm = context.getSystemService(StorageManager::class.java)
                sm.storageVolumes.forEach { v ->
                    val path = runCatching {
                        val m = v.javaClass.getMethod("getPath")
                        m.invoke(v) as? String
                    }.getOrNull() ?: return@forEach
                    val f = File(path)
                    if (f.isDirectory && f.canRead() && !out.containsKey(path)) {
                        out[path] = Volume(path, testWrite(f), f.usableSpace, f.totalSpace)
                    }
                }
            }.onFailure { PrinterLog.w(TAG, "volume reflect failed: ${it.message}") }
        }
        return out.values.toList()
    }

    fun firstWritable(context: Context? = null): Volume? = detect(context).firstOrNull { it.writable }

    private fun testWrite(dir: File): Boolean = runCatching {
        val t = File(dir, ".hswrite")
        val ok = t.createNewFile()
        if (ok) t.delete()
        ok
    }.getOrDefault(false)

    private const val TAG = "UsbStorage"
}
