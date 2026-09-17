"""
PhoneCam Windows Client
=======================
Receives H.264 stream from the PhoneCam Android app (via ADB port forwarding)
and feeds it into a virtual camera that OBS can use.

Requirements:
    pip install pyvirtualcam opencv-python numpy av

Usage:
    1. Connect phone via USB-C, enable USB debugging
    2. Run: adb forward tcp:8080 tcp:8080
    3. Open PhoneCam app on phone, tap "Start Streaming"
    4. Run this script: python phonecam_client.py
    5. In OBS: Sources + -> Video Capture Device -> "OBS Virtual Camera"
"""

import argparse
import contextlib
import multiprocessing as mp
import socket
import struct
import subprocess
import sys
import time
from multiprocessing import shared_memory

import cv2
import numpy as np

try:
    import pyvirtualcam
except ImportError:
    print("ERROR: pyvirtualcam not installed.")
    print("Install with:  pip install pyvirtualcam")
    print("You also need OBS Studio installed for the virtual camera driver.")
    sys.exit(1)

try:
    import av
except ImportError:
    print("ERROR: PyAV not installed.")
    print("Install with:  pip install av")
    sys.exit(1)


# ─────────────────────────────────────────────
#  Configuration
# ─────────────────────────────────────────────

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8080
DEFAULT_WIDTH = 1920
DEFAULT_HEIGHT = 1080
DEFAULT_FPS = 60

RECONNECT_DELAY = 2  # seconds between reconnect attempts
FRAME_TIMEOUT = 5  # seconds without a frame before reconnecting
SOCKET_TIMEOUT = 8  # per-read socket timeout (catches silent hangs)

SESSION_MAGIC = b"PCAMH264"  # 8 bytes, then BE u32 w,h,fps
PACKET_MAGIC = b"PCAM"  # 4 bytes, then BE u32 len, BE u32 rot, u8 front
MAX_NAL = 8 * 1024 * 1024
SHM_HEADER_SIZE = 64  # reserved bytes before pixel data in shared memory


# ─────────────────────────────────────────────
#  ADB Helpers
# ─────────────────────────────────────────────


def check_adb_device() -> bool:
    """Restart ADB and return True when an authorized USB device is present."""
    try:
        print("[ADB] Restarting ADB server...")
        subprocess.run(
            ["adb", "kill-server"], capture_output=True, timeout=5, check=False
        )
        time.sleep(1)
        subprocess.run(
            ["adb", "start-server"], capture_output=True, timeout=10, check=False
        )
        time.sleep(1)
        result = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, timeout=15, check=False
        )
        lines = [l.strip() for l in result.stdout.splitlines() if l.strip()]
        devices = [l for l in lines[1:] if "\tdevice" in l]
        if devices:
            print(f"[ADB] Device found: {devices[0].split(chr(9))[0]}")
            return True
        print("[ADB] No device found. Check USB cable and USB Debugging setting.")
        return False
    except FileNotFoundError:
        print("[ADB] 'adb' not found in PATH.")
        return False
    except subprocess.TimeoutExpired:
        print("[ADB] ADB timed out - try 'adb kill-server' in a terminal.")
        return False


def run_adb_forward(local_port: int, remote_port: int) -> bool:
    """Forward localhost:local_port to the phone's remote_port. Never raises."""
    try:
        r = subprocess.run(
            ["adb", "forward", f"tcp:{local_port}", f"tcp:{remote_port}"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if r.returncode == 0:
            print(f"[ADB] Forwarding localhost:{local_port} -> phone:{remote_port}")
            return True
        print(f"[ADB] Forward failed: {r.stderr.strip()}")
        return False
    except (OSError, subprocess.SubprocessError) as e:
        print(f"[ADB] Forward error: {e}")
        return False


# ─────────────────────────────────────────────
#  H.264 reader (PyAV/FFmpeg decode in a child PROCESS)
# ─────────────────────────────────────────────
# Wire protocol (see MainActivity.java):
#   session: "PCAMH264" + BE u32 w,h,fps
#   packet:  "PCAM" + BE u32 payloadLen + BE u32 rotation + u8 front + Annex-B NAL
#
# The decoder lives in a separate process because pyvirtualcam's cam.send()
# (~13ms @1080p, GIL-held) otherwise starves the decode thread down to ~15fps.
# Frames are handed over via shared memory; the parent only copies when a new
# frame id appears.

FRAME_MAX_BYTES = 1920 * 1080 * 3  # biggest decoded frame (1080x1920 portrait)
SHM_SIZE = SHM_HEADER_SIZE + FRAME_MAX_BYTES

#: Rotations the phone may report per packet (anything else is treated as 0).
VALID_ROTATIONS = (0, 90, 180, 270)


def _read_exact(sock: socket.socket, n: int, last_frame_ts: float) -> bytes:
    """Read exactly n bytes or raise ConnectionError on EOF/timeout-stall."""
    buf = bytearray()
    while len(buf) < n:
        try:
            chunk = sock.recv(n - len(buf))
        except TimeoutError:  # socket.timeout aliases this
            if last_frame_ts and (time.time() - last_frame_ts) > FRAME_TIMEOUT:
                raise ConnectionError("frame timeout")
            continue
        if not chunk:
            raise ConnectionError("server closed connection")
        buf += chunk
    return bytes(buf)


def parse_packet_header(head: bytes) -> tuple[int, int, int]:
    """Parse a 13-byte packet header into (payload_len, rotation, front).

    Raises ValueError on bad magic, length, or rotation.
    """
    if len(head) != 13 or head[:4] != PACKET_MAGIC:
        raise ValueError(f"packet desync: {head[:4]!r}")
    nal_len, rot = struct.unpack(">II", head[4:12])
    front = head[12]
    if nal_len <= 0 or nal_len > MAX_NAL:
        raise ValueError(f"bad NAL length: {nal_len}")
    if rot not in VALID_ROTATIONS:
        raise ValueError(f"bad rotation: {rot}")
    return nal_len, rot, front


def apply_orientation(frame: np.ndarray, rotation: int, front: bool) -> np.ndarray:
    """Rotate (0/90/180/270 clockwise) then mirror front-camera frames."""
    if rotation == 90:
        frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
    elif rotation == 180:
        frame = cv2.rotate(frame, cv2.ROTATE_180)
    elif rotation == 270:
        frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
    if front:
        frame = cv2.flip(frame, 1)
    return frame


def letterbox(frame: np.ndarray, width: int, height: int) -> np.ndarray:
    """Fit frame into a width×height canvas, preserving aspect ratio.

    Black bars fill the remainder; pixels are never stretched.
    """
    h, w = frame.shape[:2]
    scale = min(width / w, height / h)
    nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
    interp = cv2.INTER_LINEAR if scale > 1 else cv2.INTER_AREA
    resized = cv2.resize(frame, (nw, nh), interpolation=interp)
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    y0, x0 = (height - nh) // 2, (width - nw) // 2
    canvas[y0 : y0 + nh, x0 : x0 + nw] = resized
    return canvas


def _decode_worker(
    host: str,
    port: int,
    shm_name: str,
    lock: mp.Lock,
    frame_id: mp.Value,
    frame_ts: mp.Value,
    fps_val: mp.Value,
    w_val: mp.Value,
    h_val: mp.Value,
    stalls: mp.Value,
    stop_ev: mp.Event,
) -> None:
    """Child process: socket -> FFmpeg H.264 -> oriented BGR -> shared memory."""
    shm = shared_memory.SharedMemory(name=shm_name)
    last_ts = 0.0
    fps_start = time.time()
    fps_count = 0

    def publish(frame: np.ndarray) -> None:
        nonlocal fps_start, fps_count, last_ts
        h, w = frame.shape[:2]
        n = h * w * 3
        with lock:
            # Single copy straight into shared memory (no intermediate bytes()).
            dest = np.frombuffer(
                shm.buf[SHM_HEADER_SIZE : SHM_HEADER_SIZE + n], dtype=np.uint8
            )
            np.copyto(dest, frame.ravel())
            w_val.value = w
            h_val.value = h
            frame_id.value += 1
            last_ts = time.time()
            frame_ts.value = last_ts
        fps_count += 1
        elapsed = last_ts - fps_start
        if elapsed >= 1.0:
            with lock:
                fps_val.value = fps_count / elapsed
            fps_count = 0
            fps_start = last_ts

    while not stop_ev.is_set():
        sock = None
        try:
            print(f"[Stream] Connecting to {host}:{port} ...", flush=True)
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(SOCKET_TIMEOUT)
            sock.connect((host, port))

            magic = _read_exact(sock, 8, last_ts)
            if magic != SESSION_MAGIC:
                raise ConnectionError(f"bad session magic: {magic!r}")
            w, h, fps = struct.unpack(">III", _read_exact(sock, 12, last_ts))
            print(f"[Stream] H.264 session: {w}x{h}@{fps}fps", flush=True)

            codec = av.CodecContext.create("h264", "r")
            # FRAME threading (multi-core decode). Default is SLICE, which is
            # single-threaded for typical single-slice phone streams.
            with contextlib.suppress(AttributeError, ValueError, av.FFmpegError):
                codec.thread_type = av.codec.context.ThreadType.FRAME
                codec.thread_count = 4
            print("[Stream] Connected - receiving frames", flush=True)

            while not stop_ev.is_set():
                # Packet: "PCAM" + BE u32 len + BE u32 rot + u8 front + NAL
                head = _read_exact(sock, 13, last_ts)
                try:
                    nal_len, rot, front = parse_packet_header(head)
                except ValueError as e:
                    raise ConnectionError(str(e))
                nal = _read_exact(sock, nal_len, last_ts)

                try:
                    packets = codec.parse(nal)
                except av.FFmpegError as e:
                    print(f"[Stream] parse error: {e}", flush=True)
                    continue
                for packet in packets:
                    try:
                        frames = codec.decode(packet)
                    except av.FFmpegError as e:
                        print(f"[Stream] decode error: {e}", flush=True)
                        break
                    for av_frame in frames:
                        oriented = apply_orientation(
                            av_frame.to_ndarray(format="bgr24"), rot, bool(front)
                        )
                        publish(oriented)

        except ConnectionRefusedError:
            if not stop_ev.is_set():
                print(
                    "[Stream] Connection refused (is PhoneCam app streaming?)"
                    f" - retry in {RECONNECT_DELAY}s",
                    flush=True,
                )
        except (ConnectionError, OSError) as e:
            if not stop_ev.is_set():
                print(f"[Stream] {e} - retry in {RECONNECT_DELAY}s", flush=True)
        except Exception as e:  # noqa: BLE001 - keep-alive loop must never die
            if not stop_ev.is_set():
                print(
                    f"[Stream] Unexpected error: {e} - retry in {RECONNECT_DELAY}s",
                    flush=True,
                )
        finally:
            if sock:
                with contextlib.suppress(OSError):
                    sock.close()

        if not stop_ev.is_set():
            with lock:
                stalls.value += 1
            stop_ev.wait(RECONNECT_DELAY)

    shm.close()


class H264Reader:
    """Parent-side handle: latest decoded frame via shared memory (same API as before)."""

    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        ctx = mp.get_context("spawn")
        self._lock = ctx.Lock()
        self._frame_id = ctx.Value("Q", 0)
        self._frame_ts = ctx.Value("d", 0.0)
        self._fps = ctx.Value("d", 0.0)
        self._w = ctx.Value("i", 0)
        self._h = ctx.Value("i", 0)
        self._stalls = ctx.Value("i", 0)
        self._stop = ctx.Event()
        self._shm = shared_memory.SharedMemory(create=True, size=SHM_SIZE)
        self._proc = ctx.Process(
            target=_decode_worker,
            args=(
                host,
                port,
                self._shm.name,
                self._lock,
                self._frame_id,
                self._frame_ts,
                self._fps,
                self._w,
                self._h,
                self._stalls,
                self._stop,
            ),
            daemon=True,
            name="H264Decoder",
        )
        self._seen_id = 0
        self._frame = None

    def start(self) -> None:
        """Spawn the decoder child process."""
        self._proc.start()

    def stop(self) -> None:
        """Signal the child to exit, then release shared memory (never raises)."""
        try:
            self._stop.set()
            self._proc.join(timeout=3)
            if self._proc.is_alive():
                self._proc.terminate()
        finally:
            with contextlib.suppress(OSError):
                self._shm.close()
            with contextlib.suppress(OSError):
                self._shm.unlink()

    def get_frame(self) -> np.ndarray | None:
        """Return the newest decoded frame, or the cached one if unchanged."""
        with self._lock:
            fid = self._frame_id.value
            if fid == 0 or fid == self._seen_id:
                return self._frame
            w, h = self._w.value, self._h.value
            n = w * h * 3
            if w <= 0 or h <= 0 or n > FRAME_MAX_BYTES:
                return self._frame
            end = SHM_HEADER_SIZE + n
            arr = (
                np.frombuffer(self._shm.buf[SHM_HEADER_SIZE:end], dtype=np.uint8)
                .reshape(h, w, 3)
                .copy()
            )
            self._seen_id = fid
            self._frame = arr
            return arr

    @property
    def fps(self) -> float:
        with self._lock:
            return self._fps.value

    @property
    def stall_count(self) -> int:
        with self._lock:
            return self._stalls.value

    @property
    def is_alive(self) -> bool:
        with self._lock:
            ts = self._frame_ts.value
        if not ts:
            return True  # haven't received first frame yet
        return (time.time() - ts) < FRAME_TIMEOUT


# ─────────────────────────────────────────────
#  Stats overlay
# ─────────────────────────────────────────────


def draw_overlay(frame: np.ndarray, fps: float) -> np.ndarray:
    """Stamp FPS + wall-clock time in the top-left corner (mutates frame)."""
    ts = time.strftime("%H:%M:%S", time.localtime())
    text = f"PhoneCam  {fps:.1f}fps  {ts}"
    (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
    cv2.rectangle(frame, (6, 6), (tw + 14, th + 14), (0, 0, 0), -1)
    cv2.putText(
        frame,
        text,
        (10, th + 8),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.45,
        (80, 220, 80),
        1,
        cv2.LINE_AA,
    )
    return frame


# ─────────────────────────────────────────────
#  Main
# ─────────────────────────────────────────────


def main() -> None:
    """Connect to the phone, decode H.264, and publish to the OBS virtual camera."""
    parser = argparse.ArgumentParser(description="PhoneCam -> OBS Virtual Camera")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    parser.add_argument("--fps", type=int, default=DEFAULT_FPS)
    parser.add_argument("--no-adb", action="store_true", help="Skip ADB setup")
    parser.add_argument(
        "--overlay", action="store_true", help="Stats overlay on stream"
    )
    parser.add_argument("--preview", action="store_true", help="Local preview window")
    args = parser.parse_args()

    print("=" * 55)
    print("  PhoneCam - Android Phone Camera -> OBS Virtual Camera")
    print("=" * 55)

    # ADB
    if not args.no_adb:
        print("\n[Setup] Checking ADB...")
        if check_adb_device():
            run_adb_forward(args.port, args.port)
        else:
            print("[Setup] No ADB device - continuing anyway...")
    else:
        print("[Setup] Skipping ADB (--no-adb)")

    print(
        f"\n[Stream] {args.host}:{args.port}  ->  "
        f"virtual cam {args.width}x{args.height}@{args.fps}fps"
    )

    reader = H264Reader(args.host, args.port)
    reader.start()

    # Wait for first frame (indefinitely - WiFi can take a while on a
    # congested network; Ctrl+C aborts).
    print("\n[Wait] Waiting for first frame - make sure PhoneCam app is streaming...")
    waited = 0
    while reader.get_frame() is None:
        time.sleep(0.5)
        sys.stdout.write(".")
        sys.stdout.flush()
        waited += 1
        if waited % 20 == 0:
            print(f"\n[Wait] still waiting ({waited // 2}s) - is the phone streaming?")

    print("\n[OK] Stream live!\n")
    print("     In OBS: Sources [+] -> Video Capture Device -> 'OBS Virtual Camera'")
    print("     Press Ctrl+C to stop.\n")

    last_frame = None
    frame_num = 0
    cached_src = None  # last frame object we letterboxed...
    cached_out = None  # ...and its resized canvas (reused while no new frame)

    try:
        with pyvirtualcam.Camera(
            width=args.width,
            height=args.height,
            fps=args.fps,
            fmt=pyvirtualcam.PixelFormat.BGR,
            print_fps=False,
        ) as cam:
            print(f"[VCam] Active on: {cam.device}\n")

            while True:
                frame = reader.get_frame()

                if frame is None:
                    # No frame yet - black
                    out = np.zeros((args.height, args.width, 3), dtype=np.uint8)
                    cached_src, cached_out = None, None
                elif not reader.is_alive:
                    # Stream stalled - freeze on last good frame, tint red slightly
                    out = (
                        last_frame
                        if last_frame is not None
                        else np.zeros((args.height, args.width, 3), dtype=np.uint8)
                    )
                    out = out.copy()
                    out[:, :, 2] = np.clip(out[:, :, 2].astype(int) + 30, 0, 255)
                    cached_src, cached_out = None, None
                else:
                    last_frame = frame
                    h, w = frame.shape[:2]
                    if w == args.width and h == args.height:
                        # Native size - send directly, no work.
                        out = frame
                        cached_src, cached_out = None, None
                    elif frame is cached_src and cached_out is not None:
                        # No new frame since last tick - reuse canvas.
                        out = cached_out
                    else:
                        out = letterbox(frame, args.width, args.height)
                        cached_src, cached_out = frame, out

                if args.overlay:
                    if out is cached_out or (frame is not None and out is frame):
                        out = out.copy()  # don't scribble on cached/published frames
                    out = draw_overlay(out, reader.fps)

                cam.send(out)

                if args.preview:
                    cv2.imshow("PhoneCam Preview  [q = close]", out)
                    if cv2.waitKey(1) & 0xFF == ord("q"):
                        args.preview = False
                        cv2.destroyAllWindows()

                frame_num += 1
                if frame_num % (args.fps * 5) == 0:
                    if reader.is_alive:
                        status = "OK"
                    else:
                        status = f"STALLED (reconnects: {reader.stall_count})"
                    print(
                        f"[Stats] {reader.fps:.1f} fps  |  "
                        f"Frame #{frame_num}  |  Stream {status}"
                    )

                cam.sleep_until_next_frame()

    except KeyboardInterrupt:
        print("\n\n[Stop] Shutting down...")
    except Exception as e:  # noqa: BLE001 - top-level guard reports instead of tracing
        print(f"\n[Error] {e}")
        print(
            "Make sure OBS Studio is installed - it provides the virtual camera driver."
        )
    finally:
        reader.stop()
        cv2.destroyAllWindows()
        print("[Done] PhoneCam stopped.")


if __name__ == "__main__":
    main()
