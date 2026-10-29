package com.stv.instalador

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class ActivationResult {
    data class Authorized(val downloadUrl: String, val sha256: String, val size: Long) : ActivationResult()
    data class Denied(val reason: String, val message: String?) : ActivationResult()
}

object PanelApi {
    private const val TIMEOUT_MS = 20_000

    @Throws(IOException::class)
    fun activate(code: String, deviceId: String): ActivationResult {
        val url = URL(BuildConfig.PANEL_URL.trimEnd('/') + "/api/public/ativar")
        require(url.protocol == "https") { "O painel precisa usar HTTPS" }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("code", code).put("device_id", deviceId).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (e: Exception) {
                if (status == 404) throw IOException("O painel ainda não tem o endereço de ativação (/api/public/ativar).")
                throw IOException("Resposta inesperada do painel (HTTP $status).")
            }

            if (status in 200..299 && json.optBoolean("ok", false)) {
                val dl = json.optString("download_url")
                val sha = json.optString("sha256").lowercase()
                if (!dl.startsWith("https://") || sha.length != 64) {
                    throw IOException("O painel autorizou, mas não enviou link seguro ou assinatura do arquivo.")
                }
                return ActivationResult.Authorized(dl, sha, json.optLong("size", -1))
            }
            if (status >= 500) throw IOException("Erro no servidor do painel (HTTP $status).")
            return ActivationResult.Denied(
                json.optString("reason", "invalido"),
                json.optString("message").takeIf { it.isNotBlank() }
            )
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    fun download(downloadUrl: String, target: File, expectedSize: Long, onProgress: (Int, Long) -> Unit): String {
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download recusado pelo servidor (HTTP ${conn.responseCode}). Valide o código novamente.")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var last = -2
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                        if (pct != last) { last = pct; onProgress(pct, read) }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }
}
