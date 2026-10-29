"""Testes do protocolo e do núcleo do despertador (sem interface gráfica).

Rodar: python -m unittest -v test_protocolo
"""
import json
import queue
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import despertador_pc as dp

VECTOR = json.loads((Path(__file__).resolve().parent.parent / "protocol-vector.json").read_text())
KEY = VECTOR["key"]
T0 = 1_700_000_000.0


class VetorCompartilhado(unittest.TestCase):
    """Mesmo vetor usado nos testes Kotlin (protocol-vector.json)."""

    def test_assinatura_bate_com_o_vetor(self):
        self.assertEqual(VECTOR["signature"], dp.sign(KEY, VECTOR["path"], VECTOR["ts"]))

    def test_hmac_do_codigo_bate_com_o_vetor(self):
        self.assertEqual(VECTOR["code_hmac"], dp.hmac_hex(KEY, "code:" + VECTOR["nonce"]))

    def test_codigo_bate_com_o_vetor_e_preserva_zeros_a_esquerda(self):
        code = dp.compute_code(KEY, VECTOR["nonce"])
        self.assertEqual(VECTOR["code"], code)
        self.assertEqual(6, len(code))
        self.assertTrue(code.startswith("0"), "o vetor deve exercitar o zero à esquerda")


class Assinatura(unittest.TestCase):
    def setUp(self):
        self.core = dp.AlarmCore(KEY, queue.Queue())

    def _ok(self, ts=T0, sig=None, now=T0):
        return self.core.verify_request("/ring", str(int(ts)), sig or dp.sign(KEY, "/ring", int(ts)), now)

    def test_aceita_assinatura_valida(self):
        self.assertTrue(self._ok())

    def test_rejeita_assinatura_invalida(self):
        self.assertFalse(self._ok(sig="0" * 64))

    def test_rejeita_assinatura_de_outro_path(self):
        self.assertFalse(self._ok(sig=dp.sign(KEY, "/outro", int(T0))))

    def test_rejeita_chave_errada(self):
        self.assertFalse(self._ok(sig=dp.sign("f" * 64, "/ring", int(T0))))

    def test_aceita_diferenca_de_exatos_300s_e_rejeita_acima(self):
        self.assertTrue(self._ok(now=T0 + 300))
        self.assertFalse(self._ok(now=T0 + 301))
        self.assertFalse(self._ok(now=T0 - 301))

    def test_rejeita_cabecalhos_ausentes_ou_malformados(self):
        self.assertFalse(self.core.verify_request("/ring", None, None, T0))
        self.assertFalse(self.core.verify_request("/ring", "abc", "x", T0))


class Sessao(unittest.TestCase):
    def setUp(self):
        self.events = queue.Queue()
        self.core = dp.AlarmCore(KEY, self.events)

    def drain(self):
        out = []
        while not self.events.empty():
            out.append(self.events.get_nowait())
        return out

    def test_primeiro_ring_cria_sessao_com_nonce_de_16_bytes_hex(self):
        self.assertEqual({"dismissed": False}, self.core.handle_ring(T0))
        ev = self.drain()
        self.assertEqual("alarm_start", ev[0][0])
        nonce = ev[0][1]
        self.assertRegex(nonce, r"^[0-9a-f]{32}$")

    def test_ring_e_idempotente_enquanto_a_sessao_esta_ativa(self):
        self.core.handle_ring(T0)
        self.core.handle_ring(T0 + 2)
        self.core.handle_ring(T0 + 4)
        starts = [e for e in self.drain() if e[0] == "alarm_start"]
        self.assertEqual(1, len(starts))

    def test_codigo_correto_encerra_sessao_e_responde_dismissed_por_30s(self):
        self.core.handle_ring(T0)
        nonce = self.drain()[0][1]
        self.assertTrue(self.core.submit_code(dp.compute_code(KEY, nonce), T0 + 5))
        self.assertEqual(("dismissed",), self.drain()[-1][:1])
        self.assertEqual({"dismissed": True}, self.core.handle_ring(T0 + 6))
        self.assertEqual({"dismissed": True}, self.core.handle_ring(T0 + 5 + 29))
        self.assertEqual([], self.drain(), "não deve abrir sessão nova dentro dos 30 s")
        self.assertEqual({"dismissed": False}, self.core.handle_ring(T0 + 5 + 31))
        self.assertEqual("alarm_start", self.drain()[0][0])

    def test_codigo_errado_nao_encerra(self):
        self.core.handle_ring(T0)
        nonce = self.drain()[0][1]
        wrong = "000000" if dp.compute_code(KEY, nonce) != "000000" else "000001"
        self.assertFalse(self.core.submit_code(wrong, T0 + 1))
        self.assertFalse(self.core.submit_code("12", T0 + 1))
        self.assertFalse(self.core.submit_code("abcdef", T0 + 1))
        self.assertEqual({"dismissed": False}, self.core.handle_ring(T0 + 2))

    def test_codigo_aceita_espacos(self):
        self.core.handle_ring(T0)
        nonce = self.drain()[0][1]
        code = dp.compute_code(KEY, nonce)
        self.assertTrue(self.core.submit_code(f" {code[:3]} {code[3:]} ", T0 + 1))

    def test_sem_sessao_nenhum_codigo_vale(self):
        self.assertFalse(self.core.submit_code("000000", T0))

    def test_sessao_nova_tem_nonce_diferente(self):
        self.core.handle_ring(T0)
        n1 = self.drain()[0][1]
        self.core.submit_code(dp.compute_code(KEY, n1), T0 + 1)
        self.drain()
        self.core.handle_ring(T0 + 100)
        n2 = self.drain()[0][1]
        self.assertNotEqual(n1, n2)

    def test_watchdog_fecha_apos_90s_sem_ring(self):
        self.core.handle_ring(T0)
        self.drain()
        self.core.tick(T0 + 90)
        self.assertEqual([], self.drain())
        self.core.tick(T0 + 91)
        self.assertEqual([("alarm_stop",)], self.drain())
        self.core.tick(T0 + 200)
        self.assertEqual([], self.drain(), "alarm_stop só uma vez")

    def test_ring_renova_o_watchdog(self):
        self.core.handle_ring(T0)
        self.core.handle_ring(T0 + 80)
        self.drain()
        self.core.tick(T0 + 150)
        self.assertEqual([], self.drain())

    def test_depois_do_watchdog_um_ring_abre_sessao_nova(self):
        self.core.handle_ring(T0)
        self.core.tick(T0 + 100)
        self.drain()
        self.core.handle_ring(T0 + 101)
        self.assertEqual("alarm_start", self.drain()[0][0])


class Http(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.events = queue.Queue()
        cls.core = dp.AlarmCore(KEY, cls.events)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), dp.make_handler(cls.core))
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def post(self, path="/ring", headers=None):
        req = urllib.request.Request(self.url + path, data=b"", method="POST", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, None

    def test_ring_assinado_responde_200_e_json(self):
        import time
        ts = int(time.time())
        status, body = self.post(headers={"X-Ts": str(ts), "X-Sig": dp.sign(KEY, "/ring", ts)})
        self.assertEqual(200, status)
        self.assertIn("dismissed", body)

    def test_assinatura_invalida_da_403(self):
        import time
        status, _ = self.post(headers={"X-Ts": str(int(time.time())), "X-Sig": "0" * 64})
        self.assertEqual(403, status)

    def test_sem_cabecalhos_da_403(self):
        self.assertEqual(403, self.post()[0])

    def test_horario_muito_diferente_da_403(self):
        ts = int(__import__("time").time()) - 1000
        status, _ = self.post(headers={"X-Ts": str(ts), "X-Sig": dp.sign(KEY, "/ring", ts)})
        self.assertEqual(403, status)

    def test_path_desconhecido_da_404(self):
        self.assertEqual(404, self.post(path="/x")[0])


class Pareamento(unittest.TestCase):
    def test_conteudo_do_qr_de_pareamento(self):
        self.assertEqual(f"PAIR|192.168.0.10:8765|{KEY}", dp.pair_payload("192.168.0.10", 8765, KEY))

    def test_conteudo_do_qr_de_alarme(self):
        self.assertEqual("DESP:abc", dp.alarm_payload("abc"))

    def test_chave_e_gerada_uma_vez_e_reutilizada(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "cfg.json"
            k1 = dp.load_or_create_key(p)
            k2 = dp.load_or_create_key(p)
            self.assertEqual(k1, k2)
            self.assertRegex(k1, r"^[0-9a-f]{64}$")
            self.assertEqual(0o600, p.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
