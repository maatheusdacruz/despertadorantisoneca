package br.com.despertador

import java.io.IOException
import java.net.HttpURLConnection

sealed interface RingOutcome {
    data class Ok(val dismissed: Boolean) : RingOutcome
    data class Fail(val reason: String) : RingOutcome
}

object RingClient {
    /** POST /ring assinado. Bloqueante: chamar fora da thread principal. */
    fun ring(host: String, key: String, nowSec: Long = System.currentTimeMillis() / 1000): RingOutcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = Protocol.ringUrl(host).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = Config.RING_TIMEOUT_MS
            conn.readTimeout = Config.RING_TIMEOUT_MS
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.setRequestProperty("X-Ts", nowSec.toString())
            conn.setRequestProperty("X-Sig", Protocol.sign(key, Protocol.RING_PATH, nowSec))
            conn.outputStream.close()
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                RingOutcome.Fail("HTTP $code")
            } else {
                RingOutcome.Ok(Protocol.parseDismissed(conn.inputStream.bufferedReader().use { it.readText() }))
            }
        } catch (e: IOException) {
            RingOutcome.Fail(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
