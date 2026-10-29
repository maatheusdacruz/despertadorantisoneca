#!/usr/bin/env python3
"""Simula o celular: assina e envia POST /ring a cada 2 s, para testar o computador.

Uso: python simular_celular.py [--host 127.0.0.1:8765] [--key HEX] [--bad-sig] [--keep]
Por padrão lê a chave de ~/.despertador.json e para quando o computador responde dismissed=true.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

from despertador_pc import CONFIG_PATH, PORT, RING_PATH, sign

INTERVAL_S = 2


def ring_once(host: str, key: str, bad_sig: bool = False) -> dict:
    ts = int(time.time())
    sig = "0" * 64 if bad_sig else sign(key, RING_PATH, ts)
    req = urllib.request.Request(f"http://{host}{RING_PATH}", data=b"", method="POST",
                                 headers={"X-Ts": str(ts), "X-Sig": sig})
    with urllib.request.urlopen(req, timeout=3) as resp:
        return json.loads(resp.read())


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=f"127.0.0.1:{PORT}")
    parser.add_argument("--key", help="chave hex (padrão: a de ~/.despertador.json)")
    parser.add_argument("--bad-sig", action="store_true", help="envia assinatura inválida (espera 403)")
    parser.add_argument("--keep", action="store_true", help="continua mesmo depois de dismissed=true")
    args = parser.parse_args(argv)

    key = args.key or json.loads(CONFIG_PATH.read_text(encoding="utf-8"))["key"]
    print(f"Simulando celular -> {args.host} (Ctrl+C para sair)")
    while True:
        try:
            body = ring_once(args.host, key, args.bad_sig)
            print(time.strftime("%H:%M:%S"), "resposta:", body, flush=True)
            if body.get("dismissed") and not args.keep:
                print("Alarme desligado pelo computador.")
                return 0
        except urllib.error.HTTPError as exc:
            print(time.strftime("%H:%M:%S"), f"HTTP {exc.code}", flush=True)
        except (urllib.error.URLError, OSError) as exc:
            print(time.strftime("%H:%M:%S"), f"falha de rede: {exc}", flush=True)
        time.sleep(INTERVAL_S)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
