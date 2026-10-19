"""Discovery reply, one-phone session, input receiver and watchdog. No control math lives here."""
import random
import socket
import threading
import time

from . import protocol as p


class Host:
    def __init__(self, cfg: dict, out, on_input=None):
        self.cfg, self.out, self.on_input = cfg, out, on_input
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.data_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.data_sock.bind((cfg["bind"], cfg["data_port"]))
        self.data_sock.settimeout(0.2)
        self.disc_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.disc_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.disc_sock.bind((cfg["bind"], cfg["discovery_port"]))
        self.disc_sock.settimeout(0.2)
        self.data_port = self.data_sock.getsockname()[1]
        self.discovery_port = self.disc_sock.getsockname()[1]
        self._drop()
        self.last_state = p.NEUTRAL

    # --- session state (call with lock held) ---
    def _drop(self):
        self.addr = self.session = self.last_seq = None
        self.phone_name = ""
        self.last_input = self.last_seen = 0.0
        self.neutralized = True
        self.received = self.lost = 0

    def _neutral(self):
        if not self.neutralized:
            self.out.neutral()
            self.neutralized = True
            self.last_state = p.NEUTRAL

    # --- threads ---
    def start(self):
        self.threads = [threading.Thread(target=f, daemon=True)
                        for f in (self._discovery_loop, self._data_loop, self._watchdog_loop)]
        for t in self.threads:
            t.start()

    def stop(self):
        self.stop_event.set()
        for t in self.threads:
            t.join(1)
        with self.lock:
            self.neutralized = False
            self._neutral()
        self.data_sock.close()
        self.disc_sock.close()

    def _discovery_loop(self):
        while not self.stop_event.is_set():
            try:
                data, addr = self.disc_sock.recvfrom(64)
            except (socket.timeout, OSError):
                continue
            h = p.parse_header(data)
            if h and h[1] == p.DISCOVER and len(data) >= 8:
                reply = p.encode_discover_reply(p.decode_discover(data), self.data_port, self.cfg["pc_name"])
                self.disc_sock.sendto(reply, addr)

    def _data_loop(self):
        while not self.stop_event.is_set():
            try:
                data, addr = self.data_sock.recvfrom(128)
            except (socket.timeout, OSError):
                continue
            h = p.parse_header(data)
            if not h:
                continue
            version, ptype = h
            try:
                with self.lock:
                    self._handle(version, ptype, data, addr, time.monotonic())
            except (ValueError, IndexError, UnicodeError):
                continue  # malformed packet from the network: drop it

    def _handle(self, version, ptype, data, addr, now):
        if ptype == p.HELLO:
            if version != p.VERSION:
                self.data_sock.sendto(p.encode_reject(p.REJECT_VERSION), addr)
            elif self.addr and self.addr != addr:
                self.data_sock.sendto(p.encode_reject(p.REJECT_BUSY), addr)
            else:
                self._neutral()
                self._drop()
                self.addr, self.session = addr, random.randint(1, 0xFFFF)
                self.phone_name, self.last_seen = p.decode_hello(data), now
                self.data_sock.sendto(p.encode_welcome(self.session, self.cfg["vjoy_id"]), addr)
            return
        if version != p.VERSION or addr != self.addr:
            return
        if ptype == p.INPUT:
            s = p.decode_input(data)
            if s.session != self.session:
                return
            if self.last_seq is not None:
                if not p.seq_newer(s.seq, self.last_seq):
                    return  # late or duplicate
                self.lost += (s.seq - self.last_seq - 1) & 0xFFFFFFFF
            self.last_seq, self.received = s.seq, self.received + 1
            self.last_input = self.last_seen = now
            self.out.apply(s)
            self.neutralized, self.last_state = False, s
            if self.on_input:
                self.on_input(s)
        elif ptype == p.PING and len(data) >= 10:
            session, nonce = p.decode_ping(data)
            if session == self.session:
                self.last_seen = now
                self.data_sock.sendto(p.encode_pong(nonce), addr)
        elif ptype == p.BYE and len(data) >= 6 and p.decode_session(data) == self.session:
            self._neutral()
            self._drop()

    def _watchdog_loop(self):
        while not self.stop_event.wait(0.01):
            now = time.monotonic()
            with self.lock:
                if not self.addr:
                    continue
                if now - self.last_input > self.cfg["neutral_timeout_ms"] / 1000:
                    self._neutral()
                if now - self.last_seen > self.cfg["drop_timeout_ms"] / 1000:
                    self._neutral()
                    self._drop()

    def status(self) -> dict:
        with self.lock:
            total = self.received + self.lost
            return {"phone": f"{self.phone_name} {self.addr[0]}" if self.addr else None,
                    "received": self.received,
                    "lost_pct": 100 * self.lost / total if total else 0.0,
                    "state": self.last_state}
