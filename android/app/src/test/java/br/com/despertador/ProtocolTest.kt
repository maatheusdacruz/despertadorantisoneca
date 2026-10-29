package br.com.despertador

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Usa o mesmo protocol-vector.json dos testes Python (desktop/test_protocolo.py). */
class ProtocolTest {
    private val vector: JSONObject = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "protocol-vector.json") }
            .first { it.exists() }
        JSONObject(file.readText())
    }
    private val key = vector.getString("key")

    @Test fun `assinatura bate com o vetor`() {
        assertEquals(vector.getString("signature"), Protocol.sign(key, vector.getString("path"), vector.getLong("ts")))
    }

    @Test fun `hmac do codigo bate com o vetor`() {
        assertEquals(vector.getString("code_hmac"), Protocol.hmacHex(key, "code:" + vector.getString("nonce")))
    }

    @Test fun `codigo bate com o vetor e preserva zero a esquerda`() {
        val code = Protocol.computeCode(key, vector.getString("nonce"))
        assertEquals(vector.getString("code"), code)
        assertEquals(6, code.length)
        assertTrue(code.startsWith("0"))
    }

    @Test fun `qr de alarme`() {
        val nonce = vector.getString("nonce")
        assertEquals(nonce, Protocol.parseAlarmQr("DESP:$nonce"))
        assertNull(Protocol.parseAlarmQr("PAIR|192.168.0.2:8765|$key"))
        assertNull(Protocol.parseAlarmQr("DESP:curto"))
        assertNull(Protocol.parseAlarmQr("https://exemplo.com"))
    }

    @Test fun `qr de pareamento valido`() {
        val p = Protocol.parsePairing("PAIR|192.168.0.10:8765|$key")
        assertNotNull(p)
        assertEquals("192.168.0.10:8765", p!!.host)
        assertEquals(key, p.key)
    }

    @Test fun `qr de pareamento invalido`() {
        assertNull(Protocol.parsePairing("PAIR|192.168.0.10:8765|abc"))
        assertNull(Protocol.parsePairing("PAIR|192.168.0.10|$key"))
        assertNull(Protocol.parsePairing("PAIR|192.168.0.10:99999|$key"))
        assertNull(Protocol.parsePairing("DESP:$key"))
        assertNull(Protocol.parsePairing("PAIR|192.168.0.10:8765|$key|extra"))
    }

    @Test fun `pareamento recusa host fora da rede local`() {
        assertNull(Protocol.parsePairing("PAIR|8.8.8.8:8765|$key"))
        assertNull(Protocol.parsePairing("PAIR|exemplo.com:8765|$key"))
        assertNotNull(Protocol.parsePairing("PAIR|meupc.local:8765|$key"))
        assertNotNull(Protocol.parsePairing("PAIR|10.0.2.2:8765|$key"))
    }

    @Test fun `faixas privadas`() {
        assertTrue(Protocol.isLanHost("172.16.0.1"))
        assertTrue(Protocol.isLanHost("172.31.255.1"))
        assertFalse(Protocol.isLanHost("172.32.0.1"))
        assertFalse(Protocol.isLanHost("192.169.0.1"))
        assertFalse(Protocol.isLanHost("300.1.1.1"))
    }

    @Test fun `interpreta resposta do ring`() {
        assertTrue(Protocol.parseDismissed("""{"dismissed": true}"""))
        assertTrue(Protocol.parseDismissed("""{"dismissed":true}"""))
        assertFalse(Protocol.parseDismissed("""{"dismissed": false}"""))
        assertFalse(Protocol.parseDismissed(""))
    }
}
