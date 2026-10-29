package br.com.despertador

import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Dados lidos do QR de pareamento `PAIR|<host>:<porta>|<chave>`. */
data class Pairing(val host: String, val key: String)

/** Protocolo compartilhado com o computador (ver README e desktop/despertador_pc.py). */
object Protocol {
    const val RING_PATH = "/ring"
    private val KEY_RE = Regex("[0-9a-f]{64}")
    private val NONCE_RE = Regex("[0-9a-f]{32}")
    private val HOST_RE = Regex("""([A-Za-z0-9.-]+):(\d{1,5})""")
    private val DISMISSED_RE = Regex(""""dismissed"\s*:\s*true""")

    /** HMAC-SHA256 em hex; a chave são os bytes UTF-8 da própria string hex. */
    fun hmacHex(keyHex: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun sign(keyHex: String, path: String, ts: Long): String = hmacHex(keyHex, "$path:$ts")

    /** Código de 6 dígitos (com zeros à esquerda) para o nonce do QR. */
    fun computeCode(keyHex: String, nonce: String): String {
        val digest = hmacHex(keyHex, "code:$nonce")
        return "%06d".format(digest.substring(0, 8).toLong(16) % 1_000_000)
    }

    /** Extrai o nonce de `DESP:<nonce>`; null se o QR não for de alarme. */
    fun parseAlarmQr(text: String): String? {
        if (!text.startsWith("DESP:")) return null
        return text.removePrefix("DESP:").trim().takeIf { NONCE_RE.matches(it) }
    }

    /** Lê `PAIR|host:porta|chave`; null se inválido ou se o host não for de rede local. */
    fun parsePairing(text: String): Pairing? {
        val parts = text.trim().split("|")
        if (parts.size != 3 || parts[0] != "PAIR") return null
        val (host, key) = parts[1] to parts[2]
        val m = HOST_RE.matchEntire(host) ?: return null
        if (m.groupValues[2].toInt() !in 1..65535 || !KEY_RE.matches(key)) return null
        return if (isLanHost(m.groupValues[1])) Pairing(host, key) else null
    }

    /** O app só fala HTTP puro com a LAN: IPv4 privado/loopback/link-local ou nome `.local`. */
    fun isLanHost(host: String): Boolean {
        if (host.endsWith(".local", ignoreCase = true)) return true
        val octets = host.split(".").mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (octets.size != 4 || host.split(".").size != 4) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    fun parseDismissed(body: String): Boolean = DISMISSED_RE.containsMatchIn(body)

    fun ringUrl(host: String): URL = URL("http://$host$RING_PATH")
}
