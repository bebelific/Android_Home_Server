package com.printserver.core.files

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

object StorageProvider {
    fun resolveRoot(context: Context, configured: String): File {
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.isDirectory || f.mkdirs()) return f
        }
        val candidates = mutableListOf<File>()
        if (Build.VERSION.SDK_INT < 29 || Environment.isExternalStorageLegacy()) {
            Environment.getExternalStorageDirectory()?.let { candidates.add(File(it, "HomeServer")) }
        }
        context.getExternalFilesDir(null)?.let { candidates.add(File(it, "share")) }
        candidates.add(File(context.filesDir, "share"))
        for (c in candidates) {
            if (c.isDirectory || c.mkdirs()) return c
        }
        return context.filesDir
    }

    fun safeResolve(root: File, relativePath: String): File {
        val cleaned = relativePath.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
        val target = File(root, cleaned)
        val rootAbs = root.canonicalFile.absolutePath
        val targetAbs = target.canonicalFile.absolutePath
        require(targetAbs == rootAbs || targetAbs.startsWith(rootAbs + File.separator)) { "path traversal blocked" }
        return target
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
            "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"; "html", "htm" -> "text/html"; "css" -> "text/css"
            "js", "json" -> "application/javascript"; "xml" -> "application/xml"
            "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
            "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> "application/octet-stream"
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    fun htmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
