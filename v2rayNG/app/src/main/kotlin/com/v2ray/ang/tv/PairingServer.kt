package com.v2ray.ang.tv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * TV 上的局域网 HTTP 端点：手机扫码打开页面，粘贴分享链接 / 订阅地址后 POST 回来。
 * 纯 java.net 实现，不引入 HTTP 服务依赖，API 21 可用。
 */
class PairingServer(
    private val token: String,
    private val pageHtml: String,
    private val onImport: (String) -> ImportResult
) {
    data class ImportResult(val ok: Boolean, val count: Int)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var stopped = false
    private val startedAt = System.currentTimeMillis()

    fun start(): Int {
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName("0.0.0.0"))
        server.soTimeout = ACCEPT_TIMEOUT_MS
        socket = server
        Thread({ loop(server) }, "tv-pairing-server").apply {
            isDaemon = true
            start()
        }
        return server.localPort
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { socket?.close() }
    }

    private fun loop(server: ServerSocket) {
        try {
            while (!stopped && System.currentTimeMillis() - startedAt < PairingSession.TTL_MS) {
                val client = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                runCatching { handle(client) }
            }
        } finally {
            stopped = true
            runCatching { server.close() }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            it.soTimeout = IO_TIMEOUT_MS
            val input = it.getInputStream()
            val output = it.getOutputStream()
            val requestLine = readLine(input)
                ?: return respond(output, 400, TEXT, "Bad Request")
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(output, 400, TEXT, "Bad Request")
            val method = parts[0]
            val target = parts[1]
            val headers = readHeaders(input)
            if (!authorized(target, headers)) {
                return respond(output, 403, TEXT, "Forbidden")
            }
            val path = runCatching { URI(target).path }.getOrNull().orEmpty()
            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(output, 200, HTML, pageHtml)
                method == "POST" && path == "/import" ->
                    handleImport(input, output, headers)
                else -> respond(output, 404, TEXT, "Not Found")
            }
        }
    }

    private fun handleImport(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val length = headers["content-length"]?.toIntOrNull()
        if (length == null || length <= 0) {
            return respond(output, 400, JSON, FAILURE_JSON)
        }
        if (length > MAX_BODY) {
            drain(input, length)
            return respond(output, 413, JSON, FAILURE_JSON)
        }
        val body = ByteArray(length)
        if (!readFully(input, body)) {
            return respond(output, 400, JSON, FAILURE_JSON)
        }
        val text = String(body, Charsets.UTF_8)
        val result = runCatching { onImport(text) }.getOrDefault(ImportResult(false, 0))
        respond(output, 200, JSON, "{\"ok\":${result.ok},\"count\":${result.count}}")
    }

    private fun authorized(target: String, headers: Map<String, String>): Boolean {
        val rawQuery = runCatching { URI(target).rawQuery }.getOrNull()
        val queryToken = rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("t=") }
            ?.substringAfter("t=")
        return constantTimeEquals(queryToken, token) ||
            constantTimeEquals(headers["x-pairing-token"], token)
    }

    private fun constantTimeEquals(candidate: String?, expected: String): Boolean {
        if (candidate == null || candidate.length != expected.length) return false
        var diff = 0
        for (index in candidate.indices) {
            diff = diff or (candidate[index].code xor expected[index].code)
        }
        return diff == 0
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (buffer.size() == 0) null else buffer.toString("US-ASCII")
            }
            if (value == '\n'.code) break
            if (value == '\r'.code) continue
            buffer.write(value)
        }
        return buffer.toString("US-ASCII")
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val result = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            if (name.isNotEmpty()) result[name] = line.substringAfter(':', "").trim()
        }
        return result
    }

    private fun readFully(input: InputStream, body: ByteArray): Boolean {
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun drain(input: InputStream, length: Int) {
        val buffer = ByteArray(4096)
        var remaining = minOf(length, MAX_BODY + buffer.size)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            remaining -= read
        }
    }

    private fun respond(output: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> ""
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private companion object {
        const val BACKLOG = 8
        const val ACCEPT_TIMEOUT_MS = 1000
        const val IO_TIMEOUT_MS = 5000
        const val MAX_BODY = 256 * 1024
        const val TEXT = "text/plain; charset=utf-8"
        const val HTML = "text/html; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"
        const val FAILURE_JSON = "{\"ok\":false,\"count\":0}"
    }
}
