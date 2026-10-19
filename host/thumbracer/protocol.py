"""Wire format shared with the phone (mirror of Protocol.kt). Little-endian everywhere."""
import struct
from dataclasses import dataclass

MAGIC = b"TR"
VERSION = 1

INPUT, HELLO, WELCOME, REJECT, PING, PONG, BYE = 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
DISCOVER, DISCOVER_REPLY = 0x10, 0x11
FEEDBACK = 0x20  # reserved for v2 telemetry; ignored in v1

REJECT_BUSY, REJECT_NO_VJOY, REJECT_VERSION = 1, 2, 3

HEADER = struct.Struct("<2sBB")
INPUT_FMT = struct.Struct("<2sBBHIIhHHHHBB")  # 26 bytes
assert INPUT_FMT.size == 26

BTN_SHIFT_UP, BTN_SHIFT_DOWN, BTN_NITRO, BTN_HANDBRAKE, BTN_ENGAGED = 1, 2, 4, 8, 16


@dataclass
class InputState:
    session: int
    seq: int
    t_ms: int
    steer: int      # -32767..32767
    gas: int        # 0..65535
    brake: int
    handbrake: int
    buttons: int
    flags: int


NEUTRAL = InputState(0, 0, 0, 0, 0, 0, 0, 0, 0)


def header(ptype: int) -> bytes:
    return HEADER.pack(MAGIC, VERSION, ptype)


def parse_header(data: bytes):
    """Return (version, type) or None if this is not one of our packets."""
    if len(data) < HEADER.size:
        return None
    magic, version, ptype = HEADER.unpack_from(data)
    return (version, ptype) if magic == MAGIC else None


def encode_input(s: InputState) -> bytes:
    return INPUT_FMT.pack(MAGIC, VERSION, INPUT, s.session, s.seq, s.t_ms, s.steer,
                          s.gas, s.brake, s.handbrake, s.buttons, s.flags, 0)


def decode_input(data: bytes) -> InputState:
    if len(data) != INPUT_FMT.size:
        raise ValueError(f"INPUT must be {INPUT_FMT.size} bytes, got {len(data)}")
    _, _, _, *fields, _reserved = INPUT_FMT.unpack(data)
    return InputState(*fields)


def _name(data: bytes, offset: int) -> str:
    n = data[offset]
    return data[offset + 1: offset + 1 + n].decode("utf-8", "replace")


def _with_name(prefix: bytes, name: str) -> bytes:
    raw = name.encode("utf-8")[:32]
    return prefix + bytes([len(raw)]) + raw


def encode_hello(name: str) -> bytes:
    return _with_name(header(HELLO), name)


def decode_hello(data: bytes) -> str:
    return _name(data, 4)


def encode_welcome(session: int, vjoy_id: int) -> bytes:
    return header(WELCOME) + struct.pack("<HB", session, vjoy_id)


def encode_reject(reason: int) -> bytes:
    return header(REJECT) + bytes([reason])


def encode_ping(session: int, nonce: int) -> bytes:
    return header(PING) + struct.pack("<HI", session, nonce)


def decode_ping(data: bytes):
    return struct.unpack_from("<HI", data, 4)


def encode_pong(nonce: int) -> bytes:
    return header(PONG) + struct.pack("<I", nonce)


def encode_bye(session: int) -> bytes:
    return header(BYE) + struct.pack("<H", session)


def decode_session(data: bytes) -> int:
    """Session id at offset 4 (BYE)."""
    return struct.unpack_from("<H", data, 4)[0]


def encode_discover(nonce: int) -> bytes:
    return header(DISCOVER) + struct.pack("<I", nonce)


def decode_discover(data: bytes) -> int:
    return struct.unpack_from("<I", data, 4)[0]


def encode_discover_reply(nonce: int, data_port: int, name: str) -> bytes:
    return _with_name(header(DISCOVER_REPLY) + struct.pack("<IH", nonce, data_port), name)


def decode_discover_reply(data: bytes):
    nonce, port = struct.unpack_from("<IH", data, 4)
    return nonce, port, _name(data, 10)


def seq_newer(seq: int, last: int) -> bool:
    """Wrap-safe u32 compare: True if seq comes after last."""
    return 0 < ((seq - last) & 0xFFFFFFFF) < 0x80000000
