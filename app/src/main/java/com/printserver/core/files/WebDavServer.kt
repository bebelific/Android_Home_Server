package com.printserver.core.files

import android.util.Base64
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.PrinterLog
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

class WebDavServer(
    private val port: () -> Int,
    private val root: () -> File,
    private val username: () -> String,
    private val passwordHash: () -> String,
) {
    companion object { private const val TAG = "WebDav" }

    @Volatile private var http: NanoHTTPD? = null
    val isRunning: Boolean get() = http != null && http!!.wasStarted()

    fun start() {
        if (isRunning) return
        val p = port()
        http = object : NanoHTTPD("0.0.0.0", p) {
            override fun serve(session: IHTTPSession): Response = handle(session)
        }
        http!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        PrinterLog.i(TAG, "WebDAV/HTTP listening on 0.0.0.0:$p root=${root().absolutePath}")
    }

    fun stop() {
        runCatching { http?.stop() }
        http = null
        PrinterLog.i(TAG, "Stopped")
    }

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!checkAuth(session)) {
            return plain(NanoHTTPD.Response.Status.UNAUTHORIZED, "auth required").apply {
                addHeader("WWW-Authenticate", "Basic realm=\"HomeServer\"")
            }
        }
        val rel = session.uri.removePrefix("/")
        return try {
            when (session.method) {
                NanoHTTPD.Method.OPTIONS -> options()
                NanoHTTPD.Method.PROPFIND -> propfind(rel, session.headers["depth"] ?: "1")
                NanoHTTPD.Method.GET -> get(rel, session)
                NanoHTTPD.Method.HEAD -> head(rel)
                NanoHTTPD.Method.PUT -> put(rel, session)
                NanoHTTPD.Method.POST -> post(rel, session)
                NanoHTTPD.Method.DELETE -> delete(rel)
                NanoHTTPD.Method.MKCOL -> mkcol(rel)
                NanoHTTPD.Method.MOVE -> move(rel, session)
                NanoHTTPD.Method.COPY -> copy(rel, session)
                else -> plain(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "unsupported")
            }
        } catch (e: Exception) {
            PrinterLog.w(TAG, "${session.method} /$rel failed: ${e.message}")
            plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun checkAuth(session: NanoHTTPD.IHTTPSession): Boolean {
        val hash = passwordHash()
        if (hash.isBlank()) return true
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", true)) return false
        return try {
            val decoded = String(Base64.decode(header.substring(6).trim(), Base64.NO_WRAP), Charsets.UTF_8)
            val user = decoded.substringBefore(':')
            val pass = decoded.substringAfter(':', "")
            user == username() && PreferencesManager.sha256(pass).equals(hash, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun options(): NanoHTTPD.Response =
        plain(NanoHTTPD.Response.Status.OK, "").apply {
            addHeader("Allow", "OPTIONS, GET, HEAD, PUT, POST, DELETE, MKCOL, PROPFIND, MOVE, COPY")
            addHeader("DAV", "1")
        }

    private fun target(rel: String): File = StorageProvider.safeResolve(root(), rel)

    private fun get(rel: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val f = target(rel)
        if (!f.exists()) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        if (f.isDirectory) return dirPage(f, rel)
        val mime = StorageProvider.guessMime(f.name)
        val range = sessionRange(f.length(), session.headers["range"])
        return when {
            range == null ->
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, FileInputStream(f), f.length())
            range.first >= f.length() ->
                plain(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "range out of bounds")
            else -> {
                val count = range.last - range.first + 1
                val stream = FileInputStream(f)
                var skipped = 0L
                while (skipped < range.first) {
                    val s = stream.skip(range.first - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, LimitedStream(stream, count), count)
                    .apply {
                        addHeader("Content-Range", "bytes ${range.first}-${range.last}/${f.length()}")
                        addHeader("Accept-Ranges", "bytes")
                    }
            }
        }
    }

    private fun sessionRange(length: Long, header: String?): LongRange? {
        if (header.isNullOrBlank()) return null
        val m = Regex("bytes=(\\d*)-(\\d*)").find(header.trim()) ?: return null
        val (a, b) = m.destructured
        return when {
            a.isEmpty() && b.isEmpty() -> null
            a.isEmpty() -> {
                val suffix = b.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                val start = maxOf(0, length - suffix)
                start until length
            }
            else -> {
                val start = a.toLong()
                val end = b.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                if (end < start) null else start..end
            }
        }
    }

    private class LimitedStream(private val src: java.io.InputStream, private val limit: Long) : java.io.InputStream() {
        private var remaining = limit
        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = src.read()
            if (v >= 0) remaining--
            return v
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
        override fun close() { src.close() }
    }

    private fun move(rel: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        transfer(rel, session, deleteSource = true)

    private fun copy(rel: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        transfer(rel, session, deleteSource = false)

    private fun transfer(rel: String, session: NanoHTTPD.IHTTPSession, deleteSource: Boolean): NanoHTTPD.Response {
        val destHeader = session.headers["destination"]
            ?: return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "no Destination header")
        val destRel = try {
            java.net.URI(destHeader).path?.removePrefix("/") ?: destHeader.removePrefix("/")
        } catch (_: Exception) {
            destHeader.removePrefix("/").substringAfterLast("/")
        }
        if (destRel.isBlank()) return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad destination")
        val src = target(rel)
        if (!src.exists()) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        val dst = target(destRel)
        val overwrite = (session.headers["overwrite"] ?: "T").equals("T", true)
        if (dst.exists() && !overwrite) return plain(NanoHTTPD.Response.Status.PRECONDITION_FAILED, "destination exists")
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.deleteRecursively()
        val ok = if (deleteSource) {
            src.renameTo(dst) || runCatching { src.copyRecursively(dst, overwrite = true); src.deleteRecursively() }.isSuccess
        } else {
            runCatching { src.copyRecursively(dst, overwrite = true) }.isSuccess
        }
        PrinterLog.i(TAG, "${if (deleteSource) "MOVE" else "COPY"} /$rel -> /$destRel ok=$ok")
        return if (ok) plain(NanoHTTPD.Response.Status.CREATED, "moved")
        else plain(NanoHTTPD.Response.Status.FORBIDDEN, "transfer failed")
    }

    private fun head(rel: String): NanoHTTPD.Response {
        val f = target(rel)
        if (!f.exists()) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", "").apply {
            addHeader("Content-Length", if (f.isDirectory) "0" else f.length().toString())
        }
    }

    private fun put(rel: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val f = target(rel)
        f.parentFile?.mkdirs()
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        session.inputStream.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = len
                while (remaining != 0L) {
                    val want = if (remaining < 0) buf.size else minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (remaining > 0) remaining -= n
                }
            }
        }
        PrinterLog.i(TAG, "PUT /$rel (${f.length()} bytes)")
        return plain(NanoHTTPD.Response.Status.CREATED, "created")
    }

    private fun post(rel: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val dir = target(rel)
        dir.mkdirs()
        when (val flag = session.parms["rootDir"]) {
            "mkdir" -> {
                File(dir, sanitizeName(session.parms["newdir"] ?: "")).mkdirs()
                PrinterLog.i(TAG, "MKDIR /$rel")
            }
            null -> {
                var count = 0
                for ((_, tmpPath) in files) {
                    val src = File(tmpPath)
                    if (!src.isFile) continue
                    src.copyTo(uniqueChild(dir, sanitizeName(src.name.ifBlank { "upload.bin" })), overwrite = true)
                    src.delete()
                    count++
                }
                PrinterLog.i(TAG, "POST /$rel uploaded $count file(s)")
            }
            else -> if (flag.startsWith("rename:")) {
                val oldName = flag.removePrefix("rename:")
                val newName = sanitizeName(session.parms["newname"] ?: "")
                if (newName.isNotBlank()) {
                    val rc = File(dir, sanitizeName(oldName)).renameTo(File(dir, newName))
                    PrinterLog.i(TAG, "RENAME /$rel: $oldName -> $newName ok=$rc")
                }
            } else if (flag.startsWith("del:")) {
                File(dir, sanitizeName(flag.removePrefix("del:"))).deleteRecursively()
                PrinterLog.w(TAG, "DELETE inside /$rel")
            }
        }
        return redirect(rel)
    }

    private fun delete(rel: String): NanoHTTPD.Response {
        val f = target(rel)
        if (!f.exists()) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        return if (f.deleteRecursively()) plain(NanoHTTPD.Response.Status.NO_CONTENT, "")
        else plain(NanoHTTPD.Response.Status.FORBIDDEN, "delete failed")
    }

    private fun mkcol(rel: String): NanoHTTPD.Response =
        if (target(rel).mkdirs()) plain(NanoHTTPD.Response.Status.CREATED, "created")
        else plain(NanoHTTPD.Response.Status.FORBIDDEN, "mkcol failed")

    private fun propfind(rel: String, depth: String): NanoHTTPD.Response {
        val f = target(rel)
        if (!f.exists()) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">")
        appendProp(sb, f, rel)
        if (f.isDirectory && depth != "0") {
            f.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                appendProp(sb, child, if (rel.isEmpty()) child.name else "$rel/${child.name}")
            }
        }
        sb.append("</D:multistatus>")
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.MULTI_STATUS,
            "application/xml; charset=utf-8", sb.toString()
        )
    }

    private fun appendProp(sb: StringBuilder, f: File, href: String) {
        sb.append("<D:response><D:href>")
            .append(StorageProvider.htmlEscape(if (href.isEmpty()) "/" else "/$href"))
            .append("</D:href><D:propstat><D:prop>")
        if (f.isDirectory) sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
        else sb.append("<D:resourcetype/><D:getcontentlength>").append(f.length()).append("</D:getcontentlength>")
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    private fun dirPage(dir: File, rel: String): NanoHTTPD.Response {
        val base = if (rel.isEmpty()) "" else "/$rel"
        val entries = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>AndroidHomeServer</title>")
        sb.append("<style>body{font-family:sans-serif;margin:2em}table{border-collapse:collapse;width:100%}td{padding:.4em;border-bottom:1px solid #ddd}form{margin:.4em 0}</style></head><body>")
        sb.append("<h2>/").append(StorageProvider.htmlEscape(rel)).append("</h2>")
        sb.append("<p>").append(StorageProvider.humanSize(dir.usableSpace)).append(" free of ")
        sb.append(StorageProvider.humanSize(dir.totalSpace)).append("</p>")
        sb.append("<form method='POST' action='$base' enctype='multipart/form-data'>")
        sb.append("<input type='file' name='file'/><button type='submit'>Upload</button></form>")
        sb.append("<form method='POST' action='$base'><input type='hidden' name='rootDir' value='mkdir'/>")
        sb.append("<input type='text' name='newdir' placeholder='folder name'/><button type='submit'>Create folder</button></form>")
        sb.append("<table><tr><th>Name</th><th>Size</th></tr>")
        if (rel.isNotEmpty()) sb.append("<tr><td colspan='2'><a href='").append(parentHref(rel)).append("'>&#8593; parent</a></td></tr>")
        for (e in entries) {
            val href = "$base/${StorageProvider.htmlEscape(e.name)}"
            if (e.isDirectory) {
                sb.append("<tr><td><a href='").append(href).append("/'>&#128193; ").append(StorageProvider.htmlEscape(e.name)).append("</a></td><td>-</td></tr>")
            } else {
                sb.append("<tr><td><a href='").append(href).append("'>&#128196; ").append(StorageProvider.htmlEscape(e.name)).append("</a> ")
                sb.append("<form style='display:inline' method='POST' action='$base'><input type='hidden' name='rootDir' value='rename:${StorageProvider.htmlEscape(e.name)}'/><input type='text' name='newname' placeholder='new name' style='width:90px'/><button type='submit'>ren</button></form> ")
                sb.append("<form style='display:inline' method='POST' action='$base'><input type='hidden' name='rootDir' value='del:${StorageProvider.htmlEscape(e.name)}'/><button type='submit'>x</button></form></td>")
                sb.append("<td>").append(StorageProvider.humanSize(e.length())).append("</td></tr>")
            }
        }
        sb.append("</table></body></html>")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", sb.toString())
    }

    private fun parentHref(rel: String): String =
        rel.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "/$it/" }

    private fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) { candidate = File(dir, "$stem-$i$ext"); i++ }
        return candidate
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "upload.bin" }

    private fun redirect(rel: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", if (rel.isEmpty()) "/" else "/$rel/")
        }

    private fun plain(status: NanoHTTPD.Response.Status, msg: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain", msg)
}
