package com.printserver.core.camera

import com.printserver.core.common.PrinterLog
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class MjpegServer(
    private val port: () -> Int,
    private val bus: FrameBus,
    private val streamer: CameraStreamer,
) {
    companion object {
        const val BOUNDARY = "androidhomeserverframe"
        private const val TAG = "MjpegServer"
    }

    @Volatile private var http: NanoHTTPD? = null
    val isRunning: Boolean get() = http != null && http!!.wasStarted()

    fun start() {
        if (isRunning) return
        val p = port()
        http = object : NanoHTTPD("0.0.0.0", p) {
            override fun serve(session: IHTTPSession): Response = handle(session)
        }
        http!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        PrinterLog.i(TAG, "MJPEG listening on 0.0.0.0:$p")
    }

    fun stop() {
        runCatching { http?.stop() }
        http = null
        PrinterLog.i(TAG, "Stopped")
    }

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = when (session.uri) {
        "/", "/view" -> viewPage()
        "/stream", "/video.mjpg" -> streamResponse()
        "/snapshot.jpg" -> snapshot()
        "/status" -> statusJson()
        else -> plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
    }

    private fun viewPage(): NanoHTTPD.Response {
        val html = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AndroidHomeServer cam</title>
<style>body{background:#111;color:#eee;font-family:sans-serif;margin:0;text-align:center}
img{width:100%;max-height:82vh;object-fit:contain;background:#000}
#st{padding:6px;font-size:13px;color:#9c9}</style></head><body>
<img id="i" alt="camera"><div id="st">connecting...</div>
<script>
var i=document.getElementById('i'),st=document.getElementById('st'),mode='stream',busy=false;
function poll(){
  if(busy)return; busy=true;
  fetch('/snapshot.jpg?'+Date.now()).then(function(r){
    if(!r.ok)throw 0; return r.blob();
  }).then(function(b){
    busy=false;
    if(i.src)URL.revokeObjectURL(i.src);
    i.src=URL.createObjectURL(b);
    st.textContent='live (snapshot mode)';
    setTimeout(poll,120);
  }).catch(function(){
    busy=false;
    st.textContent='camera offline - retrying...';
    setTimeout(poll,1000);
  });
}
i.onerror=function(){ if(mode==='stream'){mode='snap';poll();} };
i.onload=function(){ if(mode==='stream')st.textContent='live (mjpeg)'; };
i.src='/stream?'+Date.now();
setTimeout(function(){
  if(mode==='stream'&&(!i.complete||!i.naturalWidth)){mode='snap';poll();}
},2500);
</script></body></html>"""
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
    }

    private fun streamResponse(): NanoHTTPD.Response {
        if (!streamer.isRunning) return plain(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "camera not running")
        val sub = bus.subscribe() ?: return plain(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "too many viewers")
        val input: java.io.InputStream = object : java.io.InputStream() {
            private var buf: ByteArray? = null
            private var pos = 0
            override fun read(): Int {
                while (true) {
                    val b = buf
                    if (b != null && pos < b.size) {
                        val v = b[pos].toInt() and 0xFF
                        pos++
                        return v
                    }
                    if (http == null) return -1
                    val frame = sub.take(500)
                    if (frame != null) {
                        val head = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray()
                        buf = head + frame + "\r\n".toByteArray()
                        pos = 0
                    }
                }
            }
        }
        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            input
        )
    }

    private fun snapshot(): NanoHTTPD.Response {
        val f = bus.latest ?: return plain(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "no frame yet")
        return bytes(NanoHTTPD.Response.Status.OK, "image/jpeg", f)
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private fun statusJson(): NanoHTTPD.Response {
        val json = "{\"running\":${streamer.isRunning}," +
            "\"resolution\":\"${streamer.resolution}\"," +
            "\"fpsCap\":${streamer.fpsCap}," +
            "\"quality\":${streamer.jpegQuality}," +
            "\"torch\":${streamer.torchRequested}," +
            "\"viewers\":${bus.subscriberCount}," +
            "\"frames\":${bus.frameCount}}"
        return bytes(NanoHTTPD.Response.Status.OK, "application/json", json.toByteArray())
            .apply { addHeader("Access-Control-Allow-Origin", "*") }
    }

    private fun bytes(status: NanoHTTPD.Response.Status, mime: String, data: ByteArray): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, mime, ByteArrayInputStream(data), data.size.toLong())

    private fun plain(status: NanoHTTPD.Response.Status, msg: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain", msg)
}
