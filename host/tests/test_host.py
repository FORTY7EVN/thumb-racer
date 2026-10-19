import socket
import time
import unittest

from thumbracer import protocol as p
from thumbracer.app import DEFAULTS
from thumbracer.server import Host
from thumbracer.vjoy_out import AXIS_CENTER, AXIS_MAX, AXIS_MIN, to_axes
from thumbracer.xbox_out import XUSB_A, XUSB_RB, XUSB_X, to_xbox


class ProtocolTest(unittest.TestCase):
    def test_input_roundtrip(self):
        s = p.InputState(7, 0xFFFFFFFF, 123, -32767, 65535, 0, 1000, 0b10101, 0b100001)
        data = p.encode_input(s)
        self.assertEqual(len(data), 26)
        self.assertEqual(data[:4], b"TR\x01\x01")
        self.assertEqual(p.decode_input(data), s)

    def test_names_and_discovery(self):
        self.assertEqual(p.decode_hello(p.encode_hello("Redmi Note 13")), "Redmi Note 13")
        self.assertEqual(p.decode_discover_reply(p.encode_discover_reply(42, 47101, "PC")), (42, 47101, "PC"))
        self.assertEqual(len(p.decode_hello(p.encode_hello("x" * 100))), 32)

    def test_seq_wraps(self):
        self.assertTrue(p.seq_newer(1, 0))
        self.assertTrue(p.seq_newer(2, 0xFFFFFFFF))
        self.assertFalse(p.seq_newer(5, 5))
        self.assertFalse(p.seq_newer(4, 5))

    def test_garbage_header(self):
        self.assertIsNone(p.parse_header(b"XX\x01\x01"))
        self.assertIsNone(p.parse_header(b"T"))


class AxisTest(unittest.TestCase):
    def test_ranges(self):
        a = to_axes(p.NEUTRAL, {})
        self.assertEqual((a["x"], a["y"], a["z"], a["rx"]), (AXIS_CENTER, AXIS_MIN, AXIS_MIN, AXIS_MIN))
        full = to_axes(p.InputState(0, 0, 0, 32767, 65535, 65535, 65535, 0, 0), {})
        self.assertEqual((full["x"], full["y"], full["z"], full["rx"]), (AXIS_MAX - 1, AXIS_MAX, AXIS_MAX, AXIS_MAX))
        self.assertEqual(to_axes(p.InputState(0, 0, 0, -32767, 0, 0, 0, 0, 0), {})["x"], AXIS_MIN)

    def test_invert_and_combined(self):
        self.assertEqual(to_axes(p.NEUTRAL, {"invert_gas": True})["y"], AXIS_MAX)
        c = to_axes(p.InputState(0, 0, 0, 0, 0, 65535, 0, 0, 0), {"combined_pedals": True})
        self.assertEqual(c["y"], AXIS_MIN)
        self.assertEqual(to_axes(p.NEUTRAL, {"combined_pedals": True})["y"], AXIS_CENTER)


class XboxTest(unittest.TestCase):
    def test_mapping(self):
        a = to_xbox(p.InputState(0, 0, 0, -32767, 65535, 32768, 0, p.BTN_SHIFT_UP | p.BTN_NITRO | p.BTN_HANDBRAKE, 0), {})
        self.assertEqual((a["lx"], a["rt"], a["lt"]), (-32767, 255, 127))
        self.assertEqual(a["buttons"], XUSB_RB | XUSB_A | XUSB_X)
        self.assertEqual(to_xbox(p.NEUTRAL, {}), {"lx": 0, "rt": 0, "lt": 0, "buttons": 0})
        self.assertEqual(to_xbox(p.InputState(0, 0, 0, 100, 0, 0, 0, 0, 0), {"invert_steer": True})["lx"], -100)


class FakeOut:
    def __init__(self):
        self.applied, self.neutrals = [], 0

    def apply(self, s):
        self.applied.append(s)

    def neutral(self):
        self.neutrals += 1


class HostLoopbackTest(unittest.TestCase):
    def setUp(self):
        cfg = {**DEFAULTS, "bind": "127.0.0.1", "data_port": 0, "discovery_port": 0, "pc_name": "test-pc",
               "drop_timeout_ms": 600}
        self.out = FakeOut()
        self.host = Host(cfg, self.out)
        self.host.start()
        self.addr = ("127.0.0.1", self.host.data_port)
        self.phone = self.sock()

    def tearDown(self):
        self.host.stop()
        self.phone.close()

    def sock(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        return s

    def connect(self, s):
        s.sendto(p.encode_hello("phone"), self.addr)
        data, _ = s.recvfrom(64)
        return data

    def test_discovery(self):
        self.phone.sendto(p.encode_discover(99), ("127.0.0.1", self.host.discovery_port))
        data, _ = self.phone.recvfrom(64)
        self.assertEqual(p.decode_discover_reply(data), (99, self.host.data_port, "test-pc"))

    def test_session_input_watchdog_and_busy(self):
        data = self.connect(self.phone)
        self.assertEqual(p.parse_header(data)[1], p.WELCOME)
        session = int.from_bytes(data[4:6], "little")

        for seq, steer in ((1, 100), (3, 300), (2, 200), (3, 999)):  # 2 is late, second 3 is a duplicate
            self.phone.sendto(p.encode_input(p.InputState(session, seq, 0, steer, 0, 0, 0, 0, 1)), self.addr)
        self.phone.sendto(p.encode_input(p.InputState(session + 1, 9, 0, 5, 0, 0, 0, 0, 1)), self.addr)  # wrong session
        self.phone.sendto(b"TR\x01\x01junk", self.addr)  # malformed
        time.sleep(0.1)
        self.assertEqual([s.steer for s in self.out.applied], [100, 300])
        self.assertAlmostEqual(self.host.status()["lost_pct"], 100 / 3)  # 2 received, seq 2 missing

        other = self.sock()
        self.assertEqual(self.connect(other), p.encode_reject(p.REJECT_BUSY))
        other.close()

        self.phone.sendto(p.encode_ping(session, 77), self.addr)
        self.assertEqual(self.phone.recvfrom(64)[0], p.encode_pong(77))

        time.sleep(0.35)  # > 250 ms without input
        self.assertEqual(self.out.neutrals, 1)
        time.sleep(0.5)  # > drop timeout without any packet
        self.assertIsNone(self.host.status()["phone"])

    def test_bye_frees_host(self):
        session = int.from_bytes(self.connect(self.phone)[4:6], "little")
        self.phone.sendto(p.encode_input(p.InputState(session, 1, 0, 1, 0, 0, 0, 0, 1)), self.addr)
        self.phone.sendto(p.encode_bye(session), self.addr)
        time.sleep(0.05)
        self.assertEqual(self.out.neutrals, 1)
        other = self.sock()
        self.assertEqual(p.parse_header(self.connect(other))[1], p.WELCOME)
        other.close()


if __name__ == "__main__":
    unittest.main()
