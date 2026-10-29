#!/usr/bin/env python3
"""Despertador - lado do computador.

Recebe os avisos do celular (POST /ring), mostra em tela cheia um QR de uso único
e só encerra o alarme quando o código de 6 dígitos (que o celular só mostra depois
de escanear o QR) for digitado aqui.

Uso: python despertador_pc.py [--debug] [--port 8765] [--ip 192.168.0.10] [--windowed]
"""
import argparse
import hashlib
import hmac
import json
import os
import platform
import queue
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# ---------------------------------------------------------------- constantes
PORT = 8765
CONFIG_PATH = Path.home() / ".despertador.json"
MAX_CLOCK_SKEW_S = 300      # diferença máxima de horário aceita no /ring
DISMISSED_TTL_S = 30        # tempo em que /ring responde dismissed=true após o código correto
RING_TIMEOUT_S = 90         # sem /ring durante o alarme -> fecha a tela
RING_PATH = "/ring"
CODE_RE = re.compile(r"[0-9]{6}")


# ------------------------------------------------------------------ protocolo
def hmac_hex(key_hex: str, message: str) -> str:
    """HMAC-SHA256 com a chave sendo os bytes UTF-8 da string hex."""
    return hmac.new(key_hex.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def sign(key_hex: str, path: str, ts: int) -> str:
    return hmac_hex(key_hex, f"{path}:{ts}")


def compute_code(key_hex: str, nonce: str) -> str:
    digest = hmac_hex(key_hex, f"code:{nonce}")
    return f"{int(digest[:8], 16) % 1_000_000:06d}"


def pair_payload(ip: str, port: int, key_hex: str) -> str:
    return f"PAIR|{ip}:{port}|{key_hex}"


def alarm_payload(nonce: str) -> str:
    return f"DESP:{nonce}"


def load_or_create_key(path: Path = CONFIG_PATH) -> str:
    """Lê a chave de pareamento; se não existir, gera 32 bytes aleatórios (hex)."""
    if path.exists():
        try:
            key = json.loads(path.read_text(encoding="utf-8"))["key"]
        except (ValueError, KeyError, TypeError) as exc:
            raise SystemExit(f"Arquivo de configuração inválido ({path}): {exc}. "
                             "Corrija ou apague-o (será preciso parear o celular de novo).")
        if not re.fullmatch(r"[0-9a-f]{64}", str(key)):
            raise SystemExit(f"A chave em {path} não tem 64 caracteres hexadecimais.")
        return key
    key = secrets.token_hex(32)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"key": key}, f)
    os.replace(tmp, path)
    return key


def get_lan_ip() -> str:
    """IP da interface de saída (o UDP connect não envia nenhum pacote)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


# ----------------------------------------------------------------------- núcleo
class AlarmCore:
    """Estado do alarme. Thread-safe; fala com a interface por uma fila de eventos.

    Eventos: ("alarm_start", nonce), ("alarm_stop",), ("dismissed",).
    """

    def __init__(self, key_hex: str, events: "queue.Queue", debug: bool = False):
        self.key = key_hex
        self.events = events
        self.debug = debug
        self.last_seen = None            # horário do último /ring válido
        self._lock = threading.Lock()
        self._nonce = None
        self._last_ring = 0.0
        self._dismissed_until = 0.0

    def verify_request(self, path, ts_header, sig_header, now: float) -> bool:
        if not ts_header or not sig_header:
            return False
        try:
            ts = int(ts_header)
        except ValueError:
            return False
        if abs(now - ts) > MAX_CLOCK_SKEW_S:
            return False
        expected = sign(self.key, path, ts)
        return hmac.compare_digest(expected.encode(), sig_header.encode())

    def handle_ring(self, now: float) -> dict:
        with self._lock:
            self.last_seen = now
            if now < self._dismissed_until:
                return {"dismissed": True}
            if self._nonce is None:
                self._nonce = secrets.token_hex(16)
                self.events.put(("alarm_start", self._nonce))
                if self.debug:
                    print(f"[debug] alarme iniciado; código esperado: "
                          f"{compute_code(self.key, self._nonce)}", flush=True)
            self._last_ring = now
            return {"dismissed": False}

    def submit_code(self, text: str, now: float) -> bool:
        digits = "".join(text.split())
        with self._lock:
            if self._nonce is None or not CODE_RE.fullmatch(digits):
                return False
            if not hmac.compare_digest(compute_code(self.key, self._nonce).encode(), digits.encode()):
                return False
            self._nonce = None
            self._dismissed_until = now + DISMISSED_TTL_S
            self.events.put(("dismissed",))
            return True

    def tick(self, now: float) -> None:
        """Watchdog: fecha o alarme se o celular parou de avisar."""
        with self._lock:
            if self._nonce is not None and now - self._last_ring > RING_TIMEOUT_S:
                self._nonce = None
                self.events.put(("alarm_stop",))


def make_handler(core: AlarmCore, debug: bool = False):
    class Handler(BaseHTTPRequestHandler):
        def _reply(self, status: int, body: dict = None):
            payload = json.dumps(body).encode() if body is not None else b""
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            length = min(int(self.headers.get("Content-Length") or 0), 4096)
            if length:
                self.rfile.read(length)
            if self.path != RING_PATH:
                return self._reply(404)
            now = time.time()
            if not core.verify_request(self.path, self.headers.get("X-Ts"), self.headers.get("X-Sig"), now):
                return self._reply(403)
            self._reply(200, core.handle_ring(now))

        def log_message(self, fmt, *args):
            if debug:
                print(f"[http] {self.address_string()} {fmt % args}", flush=True)

    return Handler


def start_server(core: AlarmCore, port: int, debug: bool = False) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(core, debug))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, name="http", daemon=True).start()
    return server


# ------------------------------------------------- sistema: suspensão e tela
class SleepInhibitor:
    """Impede que o computador suspenda enquanto o programa estiver aberto.

    A tela pode apagar. Windows: SetThreadExecutionState (deve ser chamado da
    thread principal, que vive até o fim). macOS: caffeinate. Linux: systemd-inhibit.
    """
    ES_CONTINUOUS = 0x80000000
    ES_SYSTEM_REQUIRED = 0x00000001

    def __init__(self):
        self._proc = None

    def start(self) -> bool:
        system = platform.system()
        try:
            if system == "Windows":
                import ctypes
                ctypes.windll.kernel32.SetThreadExecutionState(
                    ctypes.c_uint(self.ES_CONTINUOUS | self.ES_SYSTEM_REQUIRED))
                return True
            pid = str(os.getpid())
            if system == "Darwin":
                cmd = ["caffeinate", "-i", "-s", "-w", pid]
            else:
                # termina sozinho quando este processo morre, mesmo com kill -9
                cmd = ["systemd-inhibit", "--what=sleep", "--who=despertador", "--mode=block",
                       "--why=Despertador aberto", "sh", "-c",
                       'while kill -0 "$0" 2>/dev/null; do sleep 5; done', pid]
            self._proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL,
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except (OSError, AttributeError) as exc:
            print(f"Aviso: não consegui impedir a suspensão do computador ({exc}). "
                  "Desative a suspensão manualmente.", file=sys.stderr)
            return False

    def stop(self) -> None:
        if platform.system() == "Windows":
            try:
                import ctypes
                ctypes.windll.kernel32.SetThreadExecutionState(ctypes.c_uint(self.ES_CONTINUOUS))
            except (OSError, AttributeError):
                pass
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()


def _spawn(cmd) -> None:
    try:
        subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass  # ferramenta ausente: melhor esforço


def wake_display() -> None:
    """Acende a tela (melhor esforço; não destrava uma sessão bloqueada)."""
    system = platform.system()
    if system == "Windows":
        try:
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(0x00000002 | 0x00000001)  # DISPLAY|SYSTEM
            ctypes.windll.user32.mouse_event(0x0001, 0, 0, 0, 0)                    # mexe o mouse
            ctypes.windll.user32.SendMessageW(0xFFFF, 0x0112, 0xF170, -1)           # monitor ligado
        except (OSError, AttributeError):
            pass
    elif system == "Darwin":
        _spawn(["caffeinate", "-u", "-t", "5"])
    else:
        _spawn(["xset", "dpms", "force", "on"])
        _spawn(["xset", "s", "reset"])


# ------------------------------------------------------------------ interface
def make_qr_image(payload: str, size: int):
    import qrcode
    from PIL import Image
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=3, box_size=10)
    qr.add_data(payload)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").get_image().convert("RGB")
    return img.resize((size, size), Image.NEAREST)


class App:
    def __init__(self, core, events, ip, port, key, windowed=False):
        import tkinter as tk
        from PIL import ImageTk
        self.tk, self.ImageTk = tk, ImageTk
        self.core, self.events, self.windowed = core, events, windowed
        self.ip, self.port, self.key = ip, port, key
        self.in_alarm = False
        self._ticks = 0

        root = self.root = tk.Tk()
        root.title("Despertador")
        root.configure(bg="white")
        root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.screen_min = min(root.winfo_screenwidth(), root.winfo_screenheight())

        self.idle = tk.Frame(root, bg="white")
        self.idle_qr = tk.Label(self.idle, bg="white")
        self.idle_qr.pack(pady=(20, 10))
        tk.Label(self.idle, text="Despertador ativo", font=("Helvetica", 20, "bold"), bg="white").pack()
        tk.Label(self.idle, text=f"Para parear: no celular, toque em “Parear com o computador” e escaneie este QR.\n"
                                 f"Endereço: {ip}:{port}", font=("Helvetica", 11), bg="white").pack(pady=6)
        self.seen_var = tk.StringVar(value="Nenhum contato do celular ainda.")
        tk.Label(self.idle, textvariable=self.seen_var, font=("Helvetica", 10), fg="#555", bg="white").pack()

        self.alarm = tk.Frame(root, bg="white")
        tk.Label(self.alarm, text="ACORDE! Escaneie o QR com o celular", font=("Helvetica", 30, "bold"),
                 bg="white", fg="#b00020").pack(pady=(20, 10))
        self.alarm_qr = tk.Label(self.alarm, bg="white")
        self.alarm_qr.pack()
        tk.Label(self.alarm, text="Depois digite aqui o código de 6 dígitos que aparecer no celular:",
                 font=("Helvetica", 18), bg="white").pack(pady=(14, 4))
        self.entry = tk.Entry(self.alarm, font=("Helvetica", 40), width=8, justify="center")
        self.entry.pack()
        self.msg_var = tk.StringVar()
        tk.Label(self.alarm, textvariable=self.msg_var, font=("Helvetica", 16), fg="#b00020", bg="white").pack(pady=6)
        for seq in ("<Return>", "<KP_Enter>"):
            self.entry.bind(seq, self._on_enter)

        self._show_idle(first=True)
        root.after(200, self._poll)
        root.after(1000, self._tick)

    # -- estados
    def _show_idle(self, first=False):
        self.in_alarm = False
        self.alarm.pack_forget()
        self.root.attributes("-fullscreen", False)
        self.root.attributes("-topmost", False)
        self._idle_image = self.ImageTk.PhotoImage(make_qr_image(pair_payload(self.ip, self.port, self.key), 320))
        self.idle_qr.configure(image=self._idle_image)
        self.idle.pack(expand=True)
        self.root.geometry("520x560")
        if not first:
            self.root.iconify()

    def _show_alarm(self, nonce):
        self.in_alarm = True
        self.idle.pack_forget()
        size = int(self.screen_min * 0.55)
        self._alarm_image = self.ImageTk.PhotoImage(make_qr_image(alarm_payload(nonce), size))
        self.alarm_qr.configure(image=self._alarm_image)
        self.msg_var.set("")
        self.entry.delete(0, "end")
        self.alarm.pack(expand=True)
        self.root.deiconify()
        if self.windowed:
            self.root.geometry("900x900")
        else:
            self.root.attributes("-fullscreen", True)
        self.root.attributes("-topmost", True)
        wake_display()
        self._grab_focus()

    def _grab_focus(self):
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.focus_force()
        self.entry.focus_force()

    # -- eventos
    def _on_close(self):
        if not self.in_alarm:
            self.root.iconify()  # fechar só minimiza; no alarme é ignorado

    def _on_enter(self, _event=None):
        if self.core.submit_code(self.entry.get(), time.time()):
            self._drain()
        else:
            self.msg_var.set("Código incorreto. Escaneie o QR e confira o código no celular.")
            self.entry.delete(0, "end")

    def _drain(self):
        while True:
            try:
                event = self.events.get_nowait()
            except queue.Empty:
                return
            if event[0] == "alarm_start":
                self._show_alarm(event[1])
            elif event[0] in ("alarm_stop", "dismissed") and self.in_alarm:
                self._show_idle()

    def _poll(self):
        self._drain()
        self.root.after(200, self._poll)

    def _tick(self):
        self.core.tick(time.time())
        self._ticks += 1
        if self.in_alarm:
            self._grab_focus()
            if self._ticks % 10 == 0:
                wake_display()
        if self.core.last_seen:
            self.seen_var.set("Último contato do celular: " + time.strftime("%H:%M:%S", time.localtime(self.core.last_seen)))
        self.root.after(1000, self._tick)

    def run(self):
        self.root.mainloop()


# ------------------------------------------------------------------------ main
def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Despertador - lado do computador")
    parser.add_argument("--debug", action="store_true", help="imprime o código esperado no terminal")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--ip", help="IP da LAN a colocar no QR de pareamento (padrão: detectado)")
    parser.add_argument("--windowed", action="store_true", help="alarme em janela comum (só para testes)")
    args = parser.parse_args(argv)

    key = load_or_create_key()
    ip = args.ip or get_lan_ip()
    events = queue.Queue()
    core = AlarmCore(key, events, debug=args.debug)
    try:
        server = start_server(core, args.port, args.debug)
    except OSError as exc:
        print(f"Não consegui abrir a porta {args.port}: {exc}. Já há outro despertador rodando?", file=sys.stderr)
        return 1

    inhibitor = SleepInhibitor()
    inhibitor.start()
    print(f"Despertador ouvindo em {ip}:{args.port} (configuração: {CONFIG_PATH})", flush=True)
    try:
        App(core, events, ip, args.port, key, windowed=args.windowed).run()
    finally:
        inhibitor.stop()
        server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
